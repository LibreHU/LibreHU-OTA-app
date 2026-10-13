package org.librehu.ota.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.librehu.ota.install.InstallResult
import org.librehu.ota.install.Installer
import org.librehu.ota.install.Root
import org.librehu.ota.install.SystemInstall
import java.io.File

/** What the list shows for one app. */
data class AppState(
    val app: CatalogApp,
    val installedCode: Long? = null,
    val installedName: String? = null,
    val systemApp: Boolean = false,
    val release: Release? = null,
    val checkError: String? = null,
    val task: Task = Task.Idle,
) {
    val installed get() = installedCode != null
    val updateAvailable get() = release != null && installedCode != null && release.versionCode > installedCode
    val canInstall get() = release != null && installedCode == null
}

/** Commits from the installed version to another one: [added] when it is newer, [removed] when it is older. */
data class Changes(
    val added: List<String>,
    val removed: List<String>,
)

sealed class Task {
    data object Idle : Task()

    data class Downloading(
        val progress: Float,
    ) : Task()

    /** Downloaded, waiting for the installs before it (one at a time). */
    data object Queued : Task()

    data object Installing : Task()

    data object Done : Task()

    /** Installed with another signing key: uninstall first (data lost), or replace in /system for a system app. */
    data class Incompatible(
        val apk: File,
    ) : Task()

    /** Older than the installed version: Android refuses it over the installed one (see [InstallResult.Downgrade]). */
    data class Downgrade(
        val apk: File,
    ) : Task()

    /** APK downloaded for /system: the user picks priv-app or app ([plan] says which fits). */
    data class SystemChoice(
        val apk: File,
        val plan: SystemInstall.Plan,
    ) : Task()

    data object RebootNeeded : Task()

    data class Failed(
        val message: String,
    ) : Task()
}

/** Catalogue, installed versions, GitHub releases and installs. */
class AppRepository private constructor(
    private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("ota", Context.MODE_PRIVATE)
    private val checking = Mutex()

    /**
     * Installs one at a time: several `pm install` (or installer sessions) at once, and above all LibreHU Apps
     * replacing itself while others run, made "update all" fail or close the app.
     */
    private val installing = Mutex()
    private val downloads = kotlinx.coroutines.sync.Semaphore(OtaSettingsStore.value(context).parallelDownloads.coerceIn(1, 4))

    /** Version name each running install goes to (history). */
    private val targets = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val _apps = MutableStateFlow(loadCatalog().map { AppState(it) })
    val apps: StateFlow<List<AppState>> = _apps

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _message = MutableStateFlow<String?>(null)

    /** Last error of a check (network, GitHub rate limit), null when it went fine. */
    val message: StateFlow<String?> = _message

    private val editionState = MutableStateFlow(edition())
    val editionFlow: StateFlow<Edition> = editionState

    val lastCheck: Long get() = prefs.getLong(PREF_LAST_CHECK, 0)

    fun edition(): Edition = Edition.entries.firstOrNull { it.name == prefs.getString(PREF_EDITION, null) } ?: Edition.LIBREHU_SERVICE

    fun setEdition(e: Edition) {
        prefs.edit().putString(PREF_EDITION, e.name).apply()
        editionState.value = e
        check()
    }

    /** Installed versions now (cheap); releases with [check]. */
    fun refreshInstalled() {
        _apps.update { list -> list.map { it.withInstalled(Installer.installed(context, it.app.packageName)) } }
    }

    /** Catalogue from GitHub, then the release of every app for the current edition. */
    fun check() {
        scope.launch {
            if (!checking.tryLock()) return@launch
            try {
                _busy.value = true
                _message.value = null
                runCatching { GitHub.get(Catalog.REMOTE_URL) }
                    .mapCatching { json -> CatalogApp.parse(json).also { prefs.edit().putString(PREF_CATALOG, json).apply() } }
                    .onSuccess { catalog -> mergeCatalog(catalog) }
                refreshInstalled()
                val edition = edition()
                val results =
                    _apps.value
                        .map { s ->
                            async {
                                s.app.packageName to
                                    runCatching { GitHub.release(s.app, s.app.branchFor(edition)) }
                            }
                        }.awaitAll()
                        .toMap()
                // Only the GitHub limit is shown for the whole list; other errors stay on the app's card.
                var error: String? = null
                var failures = 0
                _apps.update { list ->
                    list.map { s ->
                        val r = results[s.app.packageName] ?: return@map s
                        r.exceptionOrNull()?.let { e ->
                            failures++
                            if (e is RateLimitException) error = "rate:${e.resetEpochS}"
                        }
                        // A failed request keeps the release seen before; a successful one replaces it (none for
                        // this edition = null).
                        s.copy(release = if (r.isSuccess) r.getOrNull() else s.release, checkError = r.exceptionOrNull()?.message)
                    }
                }
                if (error == null && failures == results.size && failures > 0) {
                    error = results.values.firstNotNullOfOrNull { it.exceptionOrNull()?.message } ?: "network"
                }
                _message.value = error
                if (error == null) prefs.edit().putLong(PREF_LAST_CHECK, System.currentTimeMillis()).apply()
            } finally {
                _busy.value = false
                checking.unlock()
            }
        }
    }

    /** Downloads and installs the release of [pkg]. */
    fun install(pkg: String) {
        val release = _apps.value.firstOrNull { it.app.packageName == pkg }?.release ?: return
        install(pkg, release)
    }

    /** Downloads and installs [release] of [pkg]: any build of [versions], older ones included. */
    fun install(
        pkg: String,
        release: Release,
    ) {
        if (busyTask(pkg)) return
        setTask(pkg, Task.Queued)
        scope.launch {
            val apk = downloads.withPermit { download(pkg, release) } ?: return@launch
            setTask(pkg, Task.Queued)
            // LibreHU Apps replaces itself last: its process ends with the install.
            if (pkg == context.packageName) {
                while (_apps.value.any { it.app.packageName != pkg && it.task.isRunning() }) delay(500)
            }
            installing.withLock {
                try {
                    setTask(pkg, Task.Installing)
                    targets[pkg] = release.versionName
                    val s = OtaSettingsStore.value(context)
                    if (s.systemAuto && withContext(Dispatchers.IO) { toSystem(pkg, apk, s) }) return@withLock
                    val result = withContext(Dispatchers.IO) { Installer.install(context, apk, pkg, s.method, s.compile) }
                    if (result == InstallResult.Incompatible) {
                        setTask(pkg, Task.Incompatible(apk))
                        record(pkg, false, "signature")
                    } else if (result == InstallResult.Downgrade) {
                        setTask(pkg, Task.Downgrade(apk))
                        record(pkg, false, "downgrade")
                    } else if (result != InstallResult.Pending) {
                        onInstallResult(pkg, result)
                    } else {
                        // Android's installer: the next one waits for this answer (or a minute).
                        var waited = 0
                        while (_apps.value.firstOrNull { it.app.packageName == pkg }?.task == Task.Installing && waited < 120) {
                            delay(500)
                            waited++
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "install $pkg: ${e.message}")
                    setTask(pkg, Task.Failed(e.message ?: e.javaClass.simpleName))
                    record(pkg, false, e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    /**
     * Automatic /system update (settings): priv-app or app as [SystemInstall.Plan.recommendPrivApp] says, as a root
     * module or written in /system ([SystemWay]), and also in /data when asked (running at once). True when done here,
     * false when this app is not concerned or /system refused it (then the normal install in /data).
     */
    private fun toSystem(
        pkg: String,
        apk: File,
        s: OtaSettings,
    ): Boolean {
        if (pkg == context.packageName || !Root.isAvailable()) return false
        val state = _apps.value.firstOrNull { it.app.packageName == pkg } ?: return false
        val plan = SystemInstall.plan(context, apk, pkg)
        if (s.systemScope == SystemScope.SYSTEM_APPS && plan.current == null && !state.systemApp) return false
        val canModule = plan.manager != null && !plan.needsMetamodule
        val privApp = plan.recommendPrivApp
        var result =
            when {
                s.systemWay == SystemWay.MODULE || (s.systemWay == SystemWay.AUTO && canModule) -> {
                    SystemInstall.installModule(context, apk, pkg, state.app.name, privApp, plan)
                }

                else -> {
                    SystemInstall.installDirect(apk, pkg, privApp, plan)
                }
            }
        // Read-only /system: a module still adds the app.
        if (result is InstallResult.ReadOnly && canModule) {
            result = SystemInstall.installModule(context, apk, pkg, state.app.name, privApp, plan)
        }
        if (result != InstallResult.RebootNeeded) {
            // /system refused (read-only without module, no root manager…): the usual install in /data instead.
            History.add(context, state.app.name, "/system", false, (result as? InstallResult.Failed)?.message ?: result.toString())
            return false
        }
        if (s.systemAlsoData && !plan.otherSignature) {
            // The same APK in /data: the new version runs now; /system has it from the next start.
            Installer.install(context, apk, pkg, InstallMethod.ROOT, s.compile)
        }
        onInstallResult(pkg, result)
        return true
    }

    /** Every available update, except the ignored apps; LibreHU Apps itself last. */
    fun updateAll() {
        val ignored = OtaSettingsStore.value(context).ignored
        _apps.value
            .filter { it.updateAvailable && it.app.packageName !in ignored }
            .sortedBy { it.app.packageName == context.packageName }
            .forEach { install(it.app.packageName) }
    }

    fun setIgnored(
        pkg: String,
        ignored: Boolean,
    ) = OtaSettingsStore.update(context) { it.copy(ignored = if (ignored) it.ignored + pkg else it.ignored - pkg) }

    private fun busyTask(pkg: String) =
        _apps.value
            .firstOrNull { it.app.packageName == pkg }
            ?.task
            ?.isRunning() == true

    private fun Task.isRunning() = this is Task.Queued || this is Task.Downloading || this is Task.Installing

    private fun record(
        pkg: String,
        ok: Boolean,
        result: String,
    ) {
        val s = _apps.value.firstOrNull { it.app.packageName == pkg } ?: return
        History.add(context, s.app.name, "${s.installedName ?: "—"} → ${targets.remove(pkg) ?: "?"}", ok, result)
    }

    /** Downloads [release] (or the current release) of [pkg] then asks where in /system ([Task.SystemChoice]). Root. */
    fun prepareSystem(
        pkg: String,
        release: Release? = null,
    ) {
        val r = release ?: _apps.value.firstOrNull { it.app.packageName == pkg }?.release ?: return
        scope.launch {
            val apk = download(pkg, r) ?: return@launch
            choose(pkg, apk)
        }
    }

    /** After [Task.Incompatible] for a system app: replace the copy in /system with the downloaded APK. */
    fun replaceSystem(pkg: String) {
        val task = _apps.value.firstOrNull { it.app.packageName == pkg }?.task as? Task.Incompatible ?: return
        scope.launch { choose(pkg, task.apk) }
    }

    /**
     * After [Task.SystemChoice]: /system/priv-app ([privApp]) or /system/app, as a root module ([module]) or written in
     * the partition; active after a reboot. A read-only /system comes back to the choice, with the reason.
     */
    fun installSystem(
        pkg: String,
        privApp: Boolean,
        module: Boolean,
    ) {
        val s = _apps.value.firstOrNull { it.app.packageName == pkg } ?: return
        val task = s.task as? Task.SystemChoice ?: return
        scope.launch {
            setTask(pkg, Task.Installing)
            val result =
                if (module) {
                    SystemInstall.installModule(context, task.apk, pkg, s.app.name, privApp, task.plan)
                } else {
                    SystemInstall.installDirect(task.apk, pkg, privApp, task.plan)
                }
            if (result is InstallResult.ReadOnly) {
                setTask(pkg, task.copy(plan = task.plan.copy(readOnly = result.detail)))
            } else {
                onInstallResult(pkg, result)
            }
        }
    }

    private suspend fun choose(
        pkg: String,
        apk: File,
    ) {
        setTask(pkg, Task.Installing)
        if (!Root.isAvailable()) {
            setTask(pkg, Task.Failed("root"))
            return
        }
        setTask(pkg, Task.SystemChoice(apk, withContext(Dispatchers.IO) { SystemInstall.plan(context, apk, pkg) }))
    }

    /** APK of [release] in the cache, checked to be [pkg]; null (and [Task.Failed]) when it went wrong. */
    private fun download(
        pkg: String,
        release: Release,
    ): File? =
        try {
            setTask(pkg, Task.Downloading(0f))
            val apk = Installer.download(context, release.apkUrl, "$pkg.apk") { p -> setTask(pkg, Task.Downloading(p)) }
            val info = Installer.archiveInfo(context, apk)
            if (info == null || info.packageName != pkg) {
                setTask(pkg, Task.Failed("APK: ${info?.packageName ?: "?"}"))
                null
            } else {
                apk
            }
        } catch (e: Exception) {
            Log.w(TAG, "download $pkg: ${e.message}")
            setTask(pkg, Task.Failed(e.message ?: e.javaClass.simpleName))
            null
        }

    /**
     * After [Task.Incompatible] or [Task.Downgrade]: uninstall then install the downloaded APK. [keepData] (root,
     * downgrade only): `pm uninstall -k`, the settings stay, but an older version may not read what a newer one wrote.
     */
    fun reinstall(
        pkg: String,
        keepData: Boolean = false,
    ) {
        val apk =
            when (val t = _apps.value.firstOrNull { it.app.packageName == pkg }?.task) {
                is Task.Incompatible -> t.apk
                is Task.Downgrade -> t.apk
                else -> return
            }
        scope.launch {
            setTask(pkg, Task.Installing)
            val root = Root.isAvailable()
            Installer.uninstall(context, pkg, keepData && root)
            // Without root Android asks for the uninstall: wait until it is done (or cancelled).
            if (!root && !waitUninstalled(pkg)) {
                setTask(pkg, Task.Idle)
                refreshInstalled()
                return@launch
            }
            refreshInstalled()
            val result = Installer.install(context, apk, pkg)
            if (result != InstallResult.Pending) onInstallResult(pkg, result)
        }
    }

    /** True once [pkg] is gone (system app: back to its /system version), false after a minute. */
    private suspend fun waitUninstalled(pkg: String): Boolean {
        val before = Installer.installed(context, pkg)?.let { it.lastUpdateTime } ?: return true
        repeat(60) {
            delay(1000)
            val now = Installer.installed(context, pkg) ?: return true
            if (now.lastUpdateTime != before) return true
        }
        return false
    }

    fun dismiss(pkg: String) = setTask(pkg, Task.Idle)

    fun onInstallResult(
        pkg: String,
        result: InstallResult,
    ) {
        if (result != InstallResult.Pending) {
            record(
                pkg,
                result == InstallResult.Done || result == InstallResult.RebootNeeded,
                when (result) {
                    InstallResult.Done -> "OK"
                    InstallResult.RebootNeeded -> "reboot"
                    InstallResult.Incompatible -> "signature"
                    InstallResult.Downgrade -> "downgrade"
                    is InstallResult.ReadOnly -> "/system: ${result.detail}"
                    is InstallResult.Failed -> result.message.ifEmpty { "cancelled" }
                    InstallResult.Pending -> ""
                },
            )
        }
        setTask(
            pkg,
            when (result) {
                InstallResult.Done -> Task.Done
                InstallResult.Pending -> Task.Installing
                InstallResult.RebootNeeded -> Task.RebootNeeded
                InstallResult.Incompatible -> Task.Incompatible(File(context.cacheDir, "apk/$pkg.apk"))
                InstallResult.Downgrade -> Task.Downgrade(File(context.cacheDir, "apk/$pkg.apk"))
                is InstallResult.ReadOnly -> Task.Failed("/system: ${result.detail}")
                is InstallResult.Failed -> if (result.message.isEmpty()) Task.Idle else Task.Failed(result.message)
            },
        )
        if (result == InstallResult.Done || result == InstallResult.RebootNeeded) {
            if (!OtaSettingsStore.value(context).keepApks) File(context.cacheDir, "apk/$pkg.apk").delete()
            refreshInstalled()
        }
    }

    /** Builds kept on GitHub for [pkg] and the current edition, newest first (one GitHub request). */
    suspend fun versions(pkg: String): List<Release> {
        val app = _apps.value.firstOrNull { it.app.packageName == pkg }?.app ?: return emptyList()
        return withContext(Dispatchers.IO) { GitHub.builds(app, app.branchFor(edition())) }
    }

    /**
     * Commits between the installed version of [pkg] and [release]: [Changes.added] when [release] is newer, else
     * [Changes.removed]. Null when the installed commit is unknown (its version name has no commit) .
     */
    suspend fun changes(
        pkg: String,
        release: Release,
    ): Changes? {
        val s = _apps.value.firstOrNull { it.app.packageName == pkg } ?: return null
        val installed = s.installedName?.let { COMMIT.find(it)?.groupValues?.get(1) } ?: return null
        val target = release.commit.ifEmpty { return null }
        if (target.startsWith(installed) || installed.startsWith(target)) return Changes(emptyList(), emptyList())
        return withContext(Dispatchers.IO) {
            val added = GitHub.changes(s.app, installed, target)
            Changes(added, if (added.isEmpty()) GitHub.changes(s.app, target, installed) else emptyList())
        }
    }

    /**
     * Last log lines of [pkg] (its processes, by Android user id, and its crashes): through root, or directly when
     * LibreHU Apps was granted READ_LOGS (`adb shell pm grant org.librehu.ota android.permission.READ_LOGS`, then
     * restart it). Null without either.
     */
    suspend fun logs(pkg: String): String? =
        withContext(Dispatchers.IO) {
            val uid =
                try {
                    context.packageManager.getApplicationInfo(pkg, 0).uid
                } catch (_: PackageManager.NameNotFoundException) {
                    null
                }
            val command = "logcat -d -v threadtime -v uid -b main,system,crash"
            val out =
                (
                    if (Root.isAvailable()) {
                        Root.run(command, timeoutS = 30)?.output
                    } else if (context.checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED) {
                        runCatching {
                            val p = ProcessBuilder(command.split(' ')).redirectErrorStream(true).start()
                            p.inputStream
                                .bufferedReader()
                                .readText()
                                .also { p.waitFor() }
                        }.getOrNull()
                    } else {
                        null
                    }
                ) ?: return@withContext null
            // `-v uid` prints the owner as a number or as its name (u0_a45 for 10045).
            val ids =
                buildSet {
                    if (uid != null) {
                        add(uid.toString())
                        if (uid >= 10_000) add("u${uid / 100_000}_a${uid % 100_000 - 10_000}")
                    }
                }
            out
                .lineSequence()
                .filter { line -> line.contains(pkg) || line.split(' ').filter { it.isNotEmpty() }.getOrNull(2) in ids }
                .toList()
                .takeLast(LOG_LINES)
                .joinToString("\n")
        }

    /** Apps with a newer release (the daily check's notification). */
    fun updates(): List<AppState> = _apps.value.filter { it.updateAvailable }

    private fun setTask(
        pkg: String,
        task: Task,
    ) = _apps.update { list -> list.map { if (it.app.packageName == pkg) it.copy(task = task) else it } }

    private fun AppState.withInstalled(info: PackageInfo?): AppState =
        copy(
            installedCode =
                info?.let {
                    if (Build.VERSION.SDK_INT >= 28) {
                        it.longVersionCode
                    } else {
                        @Suppress("DEPRECATION")
                        it.versionCode.toLong()
                    }
                },
            installedName = info?.versionName,
            systemApp = Installer.isSystemApp(info),
        )

    private fun mergeCatalog(catalog: List<CatalogApp>) {
        _apps.update { old ->
            catalog.map { app ->
                old.firstOrNull { it.app.packageName == app.packageName }?.copy(app = app)
                    ?: AppState(app)
            }
        }
    }

    private fun loadCatalog(): List<CatalogApp> =
        prefs.getString(PREF_CATALOG, null)?.let { runCatching { CatalogApp.parse(it) }.getOrNull() } ?: Catalog.BUILT_IN

    companion object {
        private const val TAG = "LibreHU-OTA"
        private const val PREF_EDITION = "edition"
        private const val PREF_CATALOG = "catalog"
        private const val PREF_LAST_CHECK = "last_check"
        private const val LOG_LINES = 2000

        /** Commit at the end of a `git describe` version name: `v1.2-3-gabc1234`, `abc1234`, `abc1234-dirty`. */
        private val COMMIT = Regex("""(?:-g|^)([0-9a-f]{7,40})(?:-dirty)?$""")

        @Volatile
        private var instance: AppRepository? = null

        fun get(context: Context): AppRepository =
            instance ?: synchronized(this) {
                instance ?: AppRepository(context.applicationContext).also { instance = it }
            }
    }
}
