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
            )
        GitHub.token = state.value.githubToken
    }

    /** Same file as the rest of the app ("auto_check" was already there). */
    private fun prefs(context: Context) = context.getSharedPreferences("ota", Context.MODE_PRIVATE)
}
