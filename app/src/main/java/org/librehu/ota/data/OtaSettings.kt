package org.librehu.ota.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How apps are installed. */
enum class InstallMethod {
    /** Root `pm install` when root answers, else Android's installer. */
    AUTO,

    /** Root only (fails without it). */
    ROOT,

    /** Android's installer (asks for each app). */
    ANDROID,
}

/** Which apps the automatic /system updates apply to. */
enum class SystemScope {
    /** Apps that already have a copy in /system (priv-app or app). */
    SYSTEM_APPS,

    /** Every LibreHU app. */
    ALL,
}

/** How an app is put in /system. */
enum class SystemWay {
    /** Root module when the root manager can mount it, else written in /system (module again if read-only). */
    AUTO,
    MODULE,
    DIRECT,
}

data class OtaSettings(
    val autoCheck: Boolean = true,
    /** Hours between background checks. */
    val intervalH: Int = 24,
    /** Background checks only on an unmetered network (Wi-Fi). */
    val wifiOnly: Boolean = false,
    /** Notification listing the apps to update. */
    val notify: Boolean = true,
    /** Updates installed by the background check (root only; LibreHU Apps itself last). */
    val autoInstall: Boolean = false,
    val method: InstallMethod = InstallMethod.AUTO,
    /** Compiles the app after a root install (speed-profile): smooth from its first start. */
    val compile: Boolean = true,
    /** Keeps the downloaded APKs (else deleted once installed). */
    val keepApks: Boolean = false,
    /** Apps downloaded at the same time (installs are always one after the other). */
    val parallelDownloads: Int = 2,
    /** GitHub token (no scope needed): 5000 requests per hour instead of 60. */
    val githubToken: String = "",
    /** Packages never updated by "update all" nor the background install. */
    val ignored: Set<String> = emptySet(),
    /** Updates go to /system (priv-app or app, as the APK needs) instead of only /data. Root. */
    val systemAuto: Boolean = false,
    val systemScope: SystemScope = SystemScope.SYSTEM_APPS,
    val systemWay: SystemWay = SystemWay.AUTO,
    /** Also installed in /data: the new version runs at once, /system has it after the reboot. */
    val systemAlsoData: Boolean = true,
    /** index.json of the flashable zips; empty = LibreHU's repository. */
    val zipsIndexUrl: String = "",
) {
    companion object {
        val INTERVALS = listOf(6, 12, 24, 48, 168)
    }
}

object OtaSettingsStore {
    private val state = MutableStateFlow(OtaSettings())
    private var loaded = false

    fun get(context: Context): StateFlow<OtaSettings> {
        load(context)
        return state.asStateFlow()
    }

    fun value(context: Context): OtaSettings {
        load(context)
        return state.value
    }

    fun update(
        context: Context,
        transform: (OtaSettings) -> OtaSettings,
    ) {
        load(context)
        val s = transform(state.value)
        state.value = s
        GitHub.token = s.githubToken
        prefs(context)
            .edit()
            .putBoolean("auto_check", s.autoCheck)
            .putInt("interval_h", s.intervalH)
            .putBoolean("wifi_only", s.wifiOnly)
            .putBoolean("notify", s.notify)
            .putBoolean("auto_install", s.autoInstall)
            .putString("method", s.method.name)
            .putBoolean("compile", s.compile)
            .putBoolean("keep_apks", s.keepApks)
            .putInt("parallel", s.parallelDownloads)
            .putString("github_token", s.githubToken)
            .putStringSet("ignored", s.ignored)
            .putBoolean("system_auto", s.systemAuto)
            .putString("system_scope", s.systemScope.name)
            .putString("system_way", s.systemWay.name)
            .putBoolean("system_also_data", s.systemAlsoData)
            .putString("zips_index", s.zipsIndexUrl)
            .apply()
    }

    @Synchronized
    private fun load(context: Context) {
        if (loaded) return
        loaded = true
        val p = prefs(context)
        val d = OtaSettings()
        state.value =
            OtaSettings(
                autoCheck = p.getBoolean("auto_check", d.autoCheck),
                intervalH = p.getInt("interval_h", d.intervalH),
                wifiOnly = p.getBoolean("wifi_only", d.wifiOnly),
                notify = p.getBoolean("notify", d.notify),
                autoInstall = p.getBoolean("auto_install", d.autoInstall),
                method = InstallMethod.entries.firstOrNull { it.name == p.getString("method", null) } ?: d.method,
                compile = p.getBoolean("compile", d.compile),
                keepApks = p.getBoolean("keep_apks", d.keepApks),
                parallelDownloads = p.getInt("parallel", d.parallelDownloads),
                githubToken = p.getString("github_token", "").orEmpty(),
                ignored = p.getStringSet("ignored", emptySet())?.toSet().orEmpty(),
                systemAuto = p.getBoolean("system_auto", d.systemAuto),
                systemScope = SystemScope.entries.firstOrNull { it.name == p.getString("system_scope", null) } ?: d.systemScope,
                systemWay = SystemWay.entries.firstOrNull { it.name == p.getString("system_way", null) } ?: d.systemWay,
                systemAlsoData = p.getBoolean("system_also_data", d.systemAlsoData),
                zipsIndexUrl = p.getString("zips_index", "").orEmpty(),
            )
        GitHub.token = state.value.githubToken
    }

    /** Same file as the rest of the app ("auto_check" was already there). */
    private fun prefs(context: Context) = context.getSharedPreferences("ota", Context.MODE_PRIVATE)
}
