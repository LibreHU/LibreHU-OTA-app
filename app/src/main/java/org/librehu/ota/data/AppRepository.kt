package org.librehu.ota.data

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.librehu.ota.install.InstallResult
import org.librehu.ota.install.Installer
import org.librehu.ota.install.Root
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

sealed class Task {
    data object Idle : Task()

    data class Downloading(
        val progress: Float,
    ) : Task()

    data object Installing : Task()

    data object Done : Task()

    /** Installed with another signing key: uninstall first (data lost), or replace in /system for a system app. */
    data class Incompatible(
        val apk: File,
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
        val state = _apps.value.firstOrNull { it.app.packageName == pkg } ?: return
        val release = state.release ?: return
        scope.launch {
            try {
                setTask(pkg, Task.Downloading(0f))
                val apk =
                    Installer.download(context, release.apkUrl, "$pkg.apk") { p -> setTask(pkg, Task.Downloading(p)) }
                val info = Installer.archiveInfo(context, apk)
                if (info == null || info.packageName != pkg) {
                    setTask(pkg, Task.Failed("APK: ${info?.packageName ?: "?"}"))
                    return@launch
                }
                setTask(pkg, Task.Installing)
                val result = Installer.install(context, apk, pkg)
                if (result == InstallResult.Incompatible) {
                    setTask(pkg, Task.Incompatible(apk))
                } else if (result != InstallResult.Pending) {
                    onInstallResult(pkg, result)
                }
            } catch (e: Exception) {
                Log.w(TAG, "install $pkg: ${e.message}")
                setTask(pkg, Task.Failed(e.message ?: e.javaClass.simpleName))
            }
        }
    }

    /** After [Task.Incompatible]: uninstall (data lost) then install again. */
    fun reinstall(pkg: String) {
        val task = _apps.value.firstOrNull { it.app.packageName == pkg }?.task as? Task.Incompatible ?: return
        scope.launch {
            setTask(pkg, Task.Installing)
            if (Root.isAvailable()) {
                Installer.uninstall(context, pkg)
                onInstallResult(pkg, Installer.install(context, task.apk, pkg))
            } else {
                // Android asks for the uninstall; installing again is a second tap once it is gone.
                Installer.uninstall(context, pkg)
                setTask(pkg, Task.Idle)
            }
        }
    }

    /** After [Task.Incompatible] for a system app: replace the APK in /system (root, reboot). */
    fun replaceSystem(pkg: String) {
        val task = _apps.value.firstOrNull { it.app.packageName == pkg }?.task as? Task.Incompatible ?: return
        scope.launch {
            setTask(pkg, Task.Installing)
            onInstallResult(pkg, Installer.replaceSystemApk(context, task.apk, pkg))
        }
    }

    fun dismiss(pkg: String) = setTask(pkg, Task.Idle)

    fun onInstallResult(
        pkg: String,
        result: InstallResult,
    ) {
        setTask(
            pkg,
            when (result) {
                InstallResult.Done -> Task.Done
                InstallResult.Pending -> Task.Installing
                InstallResult.RebootNeeded -> Task.RebootNeeded
                InstallResult.Incompatible -> Task.Incompatible(File(context.cacheDir, "apk/$pkg.apk"))
                is InstallResult.Failed -> if (result.message.isEmpty()) Task.Idle else Task.Failed(result.message)
            },
        )
        if (result == InstallResult.Done) {
            File(context.cacheDir, "apk/$pkg.apk").delete()
            refreshInstalled()
        }
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

        @Volatile
        private var instance: AppRepository? = null

        fun get(context: Context): AppRepository =
            instance ?: synchronized(this) {
                instance ?: AppRepository(context.applicationContext).also { instance = it }
            }
    }
}
