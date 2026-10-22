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
    /** Copy in /system (the factory one, also when an update in /data hides it), null when there is none. */
    val systemCode: Long? = null,
    val systemName: String? = null,
    /** Copy in /data (an app installed there, or the update of a system app), null when there is none. */
    val dataCode: Long? = null,
    val dataName: String? = null,
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
        val done: Long = 0,
        /** -1 when the server gives no size. */
        val total: Long = -1,
        /** Bytes per second (smoothed). */
        val speed: Long = 0,
        /** Seconds left, -1 when unknown. */
        val etaS: Long = -1,
    ) : Task()

    /** Download paused: [done] bytes kept, resumed from there. */
    data class Paused(
        val release: Release,
        val done: Long,
        val total: Long,
    ) : Task() {
        val progress get() = if (total > 0) done.toFloat() / total else -1f
    }

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

    /** Downloads asked to pause. */
    private val pauses =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet<String>()

    /** Version name each running install goes to (history). */
    private val targets = java.util.concurrent.ConcurrentHashMap<String, String>()

    // The list of the last check (releases included) until the next one: usable without network.
    private val _apps = MutableStateFlow(loadCatalog().let { apps -> loadReleases().let { r -> apps.map { AppState(it, release = r[it.packageName]) } } })
    val apps: StateFlow<List<AppState>> = _apps

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _message = MutableStateFlow<String?>(null)

    /** Last error of a check (network, GitHub rate limit), null when it went fine. */
    val message: StateFlow<String?> = _message

    private val editionState = MutableStateFlow(edition())
    val editionFlow: StateFlow<Edition> = editionState

    val lastCheck: Long get() = prefs.getLong(PREF_LAST_CHECK, 0)

    private val _offline = MutableStateFlow(false)

    /** No network at the last attempt: the list shown is the one kept from [lastCheck]. */
    val offline: StateFlow<Boolean> = _offline

    /** Network usable now; when Android does not tell (no permission, error), true: the request decides. */
    fun online(): Boolean =
        try {
            val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
            val net = cm?.activeNetwork
            when {
                cm == null -> true
                net == null -> false
                else -> cm.getNetworkCapabilities(net)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false
            }
        } catch (_: Exception) {
            true
        }

    /** At start: always a check when there is network, else the kept list (no failed request, no waiting). */
    fun checkIfOnline() {
        if (online()) {
            check()
        } else {
            _offline.value = true
        }
    }

    init {
        // Notifications of the downloads and installs (and the foreground service while they run).
        scope.launch {
            _apps.collect {
                org.librehu.ota.Transfers
                    .update(context, it)
            }
        }
    }

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
                if (!online()) {
                    _offline.value = true
                    return@launch
                }
                _offline.value = false
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
                if (error == null) {
                    prefs.edit().putLong(PREF_LAST_CHECK, System.currentTimeMillis()).apply()
                    saveReleases()
                }
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
                    if (s.systemAuto && withContext(Dispatchers.IO) { toSystem(pkg, apk, s) }) {
                        // Running from /system or /data already (module / TWRP ones are compiled by LibreHU-service
                        // at the next start): its native code now
                        if (s.compile) Installer.compileNow(pkg)
                        return@withLock
                    }
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
        // LibreHU Apps itself as well: installed last (install()), its /system copy is used from the next start
        if (!Root.isAvailable()) return false
        val state = _apps.value.firstOrNull { it.app.packageName == pkg } ?: return false
        val plan = SystemInstall.plan(context, apk, pkg)
        if (s.systemScope == SystemScope.SYSTEM_APPS && plan.current == null && !state.systemApp) return false
        val canModule = plan.manager != null && !plan.needsMetamodule
        val privApp = plan.recommendPrivApp
        // TWRP when asked, or (automatic) when /system already refused its blocks here and no module can do it.
        if (s.systemWay == SystemWay.RECOVERY || (s.systemWay == SystemWay.AUTO && !canModule && prefs.getBoolean(PREF_WRITE_PROTECTED, false))) {
            return toRecovery(pkg, apk, s, state, privApp, plan)
        }
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
        // Blocks refused by the eMMC in this start: remembered, and TWRP does it.
        if (result == InstallResult.ReadOnly(SystemInstall.WRITE_PROTECTED)) {
            prefs.edit().putBoolean(PREF_WRITE_PROTECTED, true).apply()
            if (s.systemWay != SystemWay.MODULE) return toRecovery(pkg, apk, s, state, privApp, plan)
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

    /**
     * The /system install of [pkg] as a TWRP zip added to OpenRecoveryScript ([pendingRecovery] until the reboot into
     * the recovery); also in /data when asked, so that the new version runs now.
     */
    private fun toRecovery(
        pkg: String,
        apk: File,
        s: OtaSettings,
        state: AppState,
        privApp: Boolean,
        plan: SystemInstall.Plan,
    ): Boolean {
        val outcome =
            runCatching {
                val zip = SystemInstall.recoveryZip(context, apk, pkg, privApp, plan)
                try {
                    zip.inputStream().use { Configs.flash(it, zip.name, append = pendingRecovery.value.isNotEmpty()) }
                } finally {
                    zip.delete()
                }
            }.getOrElse { Configs.Outcome.Failed(it.message ?: it.javaClass.simpleName) }
        if (outcome !is Configs.Outcome.Done) {
            History.add(context, state.app.name, "/system (TWRP)", false, (outcome as Configs.Outcome.Failed).reason)
            return false
        }
        History.add(context, state.app.name, "/system (TWRP)", true, context.getString(org.librehu.ota.R.string.recovery_waiting_short))
        addPendingRecovery(state.app.name)
        if (s.systemAlsoData && !plan.otherSignature) {
            Installer.install(context, apk, pkg, InstallMethod.ROOT, s.compile)
            refreshInstalled()
        }
        setTask(pkg, Task.RebootNeeded)
        return true
    }

    private val _pendingRecovery = MutableStateFlow(loadPendingRecovery())

    /** Apps whose /system install waits for the reboot into TWRP (OpenRecoveryScript written). */
    val pendingRecovery: StateFlow<List<String>> = _pendingRecovery

    private fun addPendingRecovery(name: String) {
        val list = (_pendingRecovery.value + name).distinct()
        _pendingRecovery.value = list
        prefs
            .edit()
            .putString(PREF_RECOVERY, list.joinToString("\n"))
            .putString(PREF_RECOVERY_BOOT, bootId())
            .apply()
    }

    /** Waiting installs of this start only: after a reboot TWRP has run them (or the script was dropped). */
    private fun loadPendingRecovery(): List<String> =
        if (prefs.getString(PREF_RECOVERY_BOOT, null) == bootId()) {
            prefs.getString(PREF_RECOVERY, null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
        } else {
            emptyList()
        }

    private fun bootId(): String = runCatching { File("/proc/sys/kernel/random/boot_id").readText().trim() }.getOrDefault("")

    /** Reboots into TWRP, which flashes the waiting zips then starts Android again. */
    fun rebootRecovery() {
        scope.launch { Configs.rebootRecovery() }
    }

    /**
     * Several apps into /system by TWRP at once: each one's current release (an update, or the same version again to
     * repair it, or a first install) is downloaded, made into a recovery zip in /system/priv-app or /system/app as its
     * plan recommends, and added to OpenRecoveryScript; [rebootRecovery] then flashes them all. Root.
     */
    fun flashSystemBulk(pkgs: List<String>) {
        scope.launch {
            if (!Root.isAvailable()) {
                _message.value = "root"
                return@launch
            }
            for (pkg in pkgs) {
                val state = _apps.value.firstOrNull { it.app.packageName == pkg } ?: continue
                val release = state.release ?: continue
                if (busyTask(pkg)) continue
                setTask(pkg, Task.Queued)
                val apk = downloads.withPermit { download(pkg, release) } ?: continue
                installing.withLock {
                    setTask(pkg, Task.Installing)
                    val plan = SystemInstall.plan(context, apk, pkg)
                    val ok =
                        toRecovery(
                            pkg,
                            apk,
                            OtaSettingsStore.value(context),
                            state,
                            plan.recommendPrivApp,
                            plan,
                        )
                    if (!ok) setTask(pkg, Task.Failed(context.getString(org.librehu.ota.R.string.bulk_failed)))
                }
            }
        }
    }

    /** Reboots Android (root). */
    fun reboot() {
        scope.launch { Root.run("svc power reboot || reboot") }
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
                systemDone(pkg, task.apk, task.plan, result)
            }
        }
    }

    /**
     * After [Task.SystemChoice]: the same install done by TWRP (the eMMC refuses writes to /system in the normal boot):
     * a zip for the recovery, OpenRecoveryScript told to flash it, then a reboot into the recovery.
     */
    fun installSystemRecovery(
        pkg: String,
        privApp: Boolean,
    ) {
        val s = _apps.value.firstOrNull { it.app.packageName == pkg } ?: return
        val task = s.task as? Task.SystemChoice ?: return
        scope.launch {
            setTask(pkg, Task.Installing)
            val outcome =
                runCatching {
                    val zip = SystemInstall.recoveryZip(context, task.apk, pkg, privApp, task.plan)
                    try {
                        zip.inputStream().use { Configs.flash(it, zip.name, append = pendingRecovery.value.isNotEmpty()) }
                    } finally {
                        zip.delete()
                    }
                }.getOrElse { Configs.Outcome.Failed(it.message ?: it.javaClass.simpleName) }
            if (outcome is Configs.Outcome.Done) {
                History.add(context, s.app.name, "/system (TWRP)", true, outcome.detail)
                // Also in /data first (setting); not LibreHU Apps itself, whose process would end before the reboot: at
                // boot Android drops an older /data copy under a newer /system one.
                val st = OtaSettingsStore.value(context)
                if (st.systemAlsoData && !task.plan.otherSignature && pkg != context.packageName) {
                    Installer.install(context, task.apk, pkg, InstallMethod.ROOT, st.compile)
                }
                setTask(pkg, Task.RebootNeeded)
                Configs.rebootRecovery()
            } else {
                val reason = (outcome as Configs.Outcome.Failed).reason
                History.add(context, s.app.name, "/system (TWRP)", false, reason)
                setTask(pkg, Task.Failed(reason))
            }
        }
    }

    /**
     * After a /system install by hand: the same APK in /data too (setting "Also in /data", on by default), so that the
     * new version runs at once and stays an update of the /system copy. LibreHU Apps itself last: its process ends.
     */
    private fun systemDone(
        pkg: String,
        apk: File,
        plan: SystemInstall.Plan,
        result: InstallResult,
    ) {
        val st = OtaSettingsStore.value(context)
        val data = result == InstallResult.RebootNeeded && st.systemAlsoData && !plan.otherSignature
        if (data && pkg != context.packageName) Installer.install(context, apk, pkg, InstallMethod.ROOT, st.compile)
        // onInstallResult may delete the downloaded APK
        val self = if (data && pkg == context.packageName) apk.copyTo(File(context.cacheDir, "apk/self-data.apk"), overwrite = true) else null
        onInstallResult(pkg, result)
        self?.let { Installer.install(context, it, pkg, InstallMethod.ROOT, st.compile) }
    }

    /**
     * Uninstalls [pkg] from /data ([data]: the app and its data, or the update of a system app, which then runs its
     * /system version again) and / or from /system ([system], root: gone after a reboot; the module LibreHU Apps made,
     * else the files in the partition, else a TWRP zip when the eMMC refuses the write). Both: nothing left after the
     * reboot. LibreHU Apps itself: /data last, its process ends there.
     */
    fun uninstall(
        pkg: String,
        data: Boolean,
        system: Boolean,
    ) {
        if (busyTask(pkg)) return
        val state = _apps.value.firstOrNull { it.app.packageName == pkg } ?: return
        scope.launch {
            setTask(pkg, Task.Installing)
            var reboot = false
            if (system) {
                var r = SystemInstall.remove(pkg)
                if (r == InstallResult.ReadOnly(SystemInstall.WRITE_PROTECTED)) {
                    // Blocks refused by the eMMC here: TWRP removes it at the next reboot into the recovery (not under
                    // dm-verity, whose reason is shown instead)
                    val zip = SystemInstall.recoveryRemoveZip(context, pkg)
                    val outcome =
                        if (zip == null) {
                            Configs.Outcome.Failed("/system")
                        } else {
                            runCatching {
                                try {
                                    zip.inputStream().use { Configs.flash(it, zip.name, append = pendingRecovery.value.isNotEmpty()) }
                                } finally {
                                    zip.delete()
                                }
                            }.getOrElse { Configs.Outcome.Failed(it.message ?: it.javaClass.simpleName) }
                        }
                    r =
                        if (outcome is Configs.Outcome.Done) {
                            addPendingRecovery(state.app.name)
                            InstallResult.RebootNeeded
                        } else {
                            InstallResult.Failed((outcome as Configs.Outcome.Failed).reason)
                        }
                }
                History.add(context, state.app.name, context.getString(org.librehu.ota.R.string.uninstall_system), r == InstallResult.RebootNeeded, r.toString())
                if (r != InstallResult.RebootNeeded) {
                    setTask(pkg, Task.Failed((r as? InstallResult.Failed)?.message ?: (r as? InstallResult.ReadOnly)?.detail ?: r.toString()))
                    return@launch
                }
                reboot = true
            }
            if (data) {
                val self = pkg == context.packageName
                if (self) setTask(pkg, if (reboot) Task.RebootNeeded else Task.Idle)
                val ok = Installer.uninstall(context, pkg)
                if (Root.isAvailable()) {
                    History.add(context, state.app.name, context.getString(org.librehu.ota.R.string.uninstall_data), ok, if (ok) "OK" else "pm uninstall")
                    if (!ok) {
                        setTask(pkg, Task.Failed("pm uninstall"))
                        return@launch
                    }
                } else {
                    waitUninstalled(pkg)
                }
            }
            refreshInstalled()
            setTask(pkg, if (reboot) Task.RebootNeeded else Task.Idle)
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
            pauses.remove(pkg)
            setTask(pkg, Task.Downloading(0f, total = release.size.takeIf { it > 0 } ?: -1))
            var speed = 0.0
            var lastDone = -1L
            var lastAt = 0L
            val apk =
                Installer.download(context, release.apkUrl, "$pkg.apk", release.size, { pkg in pauses }) { done, total ->
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (lastDone >= 0 && now > lastAt) {
                        // Smoothed over about two seconds (updates every 250 ms).
                        val instant = (done - lastDone) * 1000.0 / (now - lastAt)
                        speed = if (speed == 0.0) instant else speed * 0.85 + instant * 0.15
                    }
                    lastDone = done
                    lastAt = now
                    val eta = if (total > 0 && speed > 1) ((total - done) / speed).toLong() else -1L
                    setTask(pkg, Task.Downloading(if (total > 0) done.toFloat() / total else -1f, done, total, speed.toLong(), eta))
                }
            val info = Installer.archiveInfo(context, apk)
            if (info == null || info.packageName != pkg) {
                setTask(pkg, Task.Failed("APK: ${info?.packageName ?: "?"}"))
                null
            } else {
                apk
            }
        } catch (e: org.librehu.ota.install.DownloadPaused) {
            pauses.remove(pkg)
            setTask(pkg, Task.Paused(release, e.done, e.total))
            null
        } catch (e: Exception) {
            Log.w(TAG, "download $pkg: ${e.message}")
            setTask(pkg, Task.Failed(e.message ?: e.javaClass.simpleName))
            null
        }

    /** Pauses the download of [pkg] (the part already received is kept). */
    fun pause(pkg: String) {
        if (_apps.value.firstOrNull { it.app.packageName == pkg }?.task is Task.Downloading) pauses.add(pkg)
    }

    /** Resumes a paused download, then installs as before. */
    fun resume(pkg: String) {
        val t = _apps.value.firstOrNull { it.app.packageName == pkg }?.task as? Task.Paused ?: return
        install(pkg, t.release)
    }

    /** Drops a paused download. */
    fun cancel(pkg: String) {
        val t = _apps.value.firstOrNull { it.app.packageName == pkg }?.task
        if (t is Task.Downloading) {
            pauses.add(pkg)
            scope.launch {
                // Once the download has stopped on the pause.
                delay(800)
                Installer.discardPart(context, "$pkg.apk")
                setTask(pkg, Task.Idle)
            }
        } else if (t is Task.Paused) {
            Installer.discardPart(context, "$pkg.apk")
            setTask(pkg, Task.Idle)
        }
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

    private fun AppState.withInstalled(info: PackageInfo?): AppState {
        val flags = info?.applicationInfo?.flags ?: 0
        val system = flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
        val updated = flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
        // The /system copy hidden by an update in /data.
        val factory: PackageInfo? = if (updated) factoryInfo(app.packageName, info?.lastUpdateTime ?: 0) else null
        val inSystem =
            if (updated) {
                factory
            } else if (system) {
                info
            } else {
                null
            }
        val inData = if (info != null && (updated || !system)) info else null
        return copy(
            installedCode = info?.code(),
            installedName = info?.versionName,
            systemApp = Installer.isSystemApp(info),
            systemCode = inSystem?.code(),
            systemName = inSystem?.versionName,
            dataCode = inData?.code(),
            dataName = inData?.versionName,
        )
    }

    /** /system copies already read, by package and update time of the /data one. */
    private val factories = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, PackageInfo?>>()

    /**
     * Version of the /system copy hidden by an update: PackageManager's MATCH_FACTORY_ONLY (0x00200000, not in the
     * SDK), kept only when it really points to a system partition; else, with root, the APK of the copy that
     * `dumpsys package` names.
     */
    private fun factoryInfo(
        pkg: String,
        updateTime: Long,
    ): PackageInfo? {
        factories[pkg]?.takeIf { it.first == updateTime }?.let { return it.second }
        val pm = context.packageManager
        val fromFlag =
            try {
                pm.getPackageInfo(pkg, MATCH_FACTORY_ONLY).takeIf { p ->
                    p.applicationInfo?.sourceDir?.let { d -> SYSTEM_PARTITIONS.any { d.startsWith(it) } } == true
                }
            } catch (_: Exception) {
                null
            }
        if (fromFlag != null) {
            factories[pkg] = updateTime to fromFlag
            return fromFlag
        }
        // Root and dumpsys take a moment: on a thread, the list refreshed once known.
        if (factories.putIfAbsent(pkg, -1L to null) == null || factories[pkg]?.first != -1L) {
            factories[pkg] = -1L to null
            scope.launch {
                val info =
                    org.librehu.ota.install.SystemInstall
                        .systemApk(pkg)
                        ?.let { pm.getPackageArchiveInfo(it, 0) }
                factories[pkg] = updateTime to info
                if (info != null) refreshInstalled()
            }
        }
        return null
    }

    private fun PackageInfo.code(): Long =
        if (Build.VERSION.SDK_INT >= 28) {
            longVersionCode
        } else {
            @Suppress("DEPRECATION")
            versionCode.toLong()
        }

    private fun mergeCatalog(catalog: List<CatalogApp>) {
        _apps.update { old ->
            catalog.map { app ->
                old.firstOrNull { it.app.packageName == app.packageName }?.copy(app = app)
                    ?: AppState(app)
            }
        }
    }

    /** Releases of the last check, for the edition they were read for. */
    private fun saveReleases() {
        val apps = org.json.JSONObject()
        _apps.value.forEach { s -> s.release?.let { apps.put(s.app.packageName, it.toJson()) } }
        prefs
            .edit()
            .putString(PREF_RELEASES, org.json.JSONObject().put("edition", edition().name).put("apps", apps).toString())
            .apply()
    }

    private fun loadReleases(): Map<String, Release> =
        runCatching {
            val o = org.json.JSONObject(prefs.getString(PREF_RELEASES, null) ?: return emptyMap())
            if (o.optString("edition") != edition().name) return emptyMap()
            val apps = o.getJSONObject("apps")
            apps.keys().asSequence().associateWith { Release.fromJson(apps.getJSONObject(it)) }
        }.getOrDefault(emptyMap())

    private fun loadCatalog(): List<CatalogApp> =
        prefs.getString(PREF_CATALOG, null)?.let { runCatching { CatalogApp.parse(it) }.getOrNull() } ?: Catalog.BUILT_IN

    companion object {
        private const val TAG = "LibreHU-OTA"
        private const val MATCH_FACTORY_ONLY = 0x00200000
        private val SYSTEM_PARTITIONS = listOf("/system/", "/product/", "/vendor/", "/system_ext/")
        private const val PREF_EDITION = "edition"
        private const val PREF_CATALOG = "catalog"
        private const val PREF_RELEASES = "releases"
        private const val PREF_LAST_CHECK = "last_check"
        private const val PREF_WRITE_PROTECTED = "system_write_protected"
        private const val PREF_RECOVERY = "recovery_pending"
        private const val PREF_RECOVERY_BOOT = "recovery_pending_boot"
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
