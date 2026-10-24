package org.librehu.ota.bridge

import android.content.Context
import org.librehu.ota.CheckJob
import org.librehu.ota.R
import org.librehu.ota.data.AppRepository
import org.librehu.ota.data.Edition
import org.librehu.ota.data.InstallMethod
import org.librehu.ota.data.OtaSettings
import org.librehu.ota.data.OtaSettingsStore
import org.librehu.ota.data.SystemScope
import org.librehu.ota.data.SystemWay

/**
 * LibreHU Apps for LibreHU's phone app (through LibreHU-service's bridge, [BridgeKitProvider]): the apps (installed
 * version, the one available, check / install / update from the phone) and every setting (edition, checks,
 * installs, /system). Same labels and same store as the app's screens; the GitHub token and the zips index are typed
 * on the head unit.
 */
class BridgeProvider : BridgeKitProvider() {
    override fun pages(context: Context): List<KitPage> = listOf(apps(context), settings(context))

    private fun apps(c: Context): KitPage {
        val repo = AppRepository.get(c)
        return page(c, "apps", c.getString(R.string.app_name), "download") {
            button("check", s(R.string.check)) { repo.check() }
            if (repo.updates().isNotEmpty()) button("update_all", s(R.string.update_all), confirm = true) { repo.updateAll() }
            for (a in repo.apps.value) {
                val now = a.installedName ?: s(R.string.not_installed_short)
                val next = a.release?.let { " → ${it.versionName}" }.orEmpty()
                info("v_${a.app.id}", a.app.name, if (a.updateAvailable || a.canInstall) "$now$next" else now)
                when {
                    a.updateAvailable -> button("u_${a.app.id}", "${s(R.string.update)} · ${a.app.name}") { repo.install(a.app.packageName) }
                    a.canInstall -> button("i_${a.app.id}", "${s(R.string.install)} · ${a.app.name}", confirm = true) { repo.install(a.app.packageName) }
                }
            }
        }
    }

    private fun settings(c: Context): KitPage {
        val st = OtaSettingsStore.value(c)
        val repo = AppRepository.get(c)

        fun set(
            reschedule: Boolean = false,
            f: (OtaSettings) -> OtaSettings,
        ) {
            OtaSettingsStore.update(c, f)
            if (reschedule) CheckJob.schedule(c, force = true)
        }
        return page(c, "settings", c.getString(R.string.set_general), "settings") {
            pick("edition", s(R.string.edition), repo.edition(), s(R.string.edition_hint), label = {
                s(
                    when (it) {
                        Edition.LIBREHU_SERVICE -> R.string.edition_service
                        Edition.IVI -> R.string.edition_ivi
                        Edition.GENERIC -> R.string.edition_generic
                    },
                )
            }) { v -> repo.setEdition(v) }
            section(s(R.string.set_checks))
            switch("auto_check", s(R.string.auto_check), st.autoCheck) { v -> set(true) { it.copy(autoCheck = v) } }
            choice("interval", s(R.string.set_interval), st.intervalH, OtaSettings.INTERVALS.map { h -> h to if (h % 24 == 0) c.getString(R.string.set_days, h / 24) else "$h h" }) { v ->
                set(true) { it.copy(intervalH = v) }
            }
            switch("wifi_only", s(R.string.set_wifi_only), st.wifiOnly) { v -> set(true) { it.copy(wifiOnly = v) } }
            switch("notify", s(R.string.set_notify), st.notify) { v -> set { it.copy(notify = v) } }
            switch("auto_install", s(R.string.set_auto_install), st.autoInstall, s(R.string.set_auto_install_hint)) { v -> set { it.copy(autoInstall = v) } }
            switch("notify_transfers", s(R.string.set_notify_transfers), st.notifyTransfers, s(R.string.set_notify_transfers_hint)) { v ->
                set { it.copy(notifyTransfers = v) }
            }
            section(s(R.string.set_install))
            pick("method", s(R.string.set_method), st.method, label = {
                s(
                    when (it) {
                        InstallMethod.AUTO -> R.string.set_method_auto
                        InstallMethod.ROOT -> R.string.set_method_root
                        InstallMethod.ANDROID -> R.string.set_method_android
                    },
                )
            }) { v -> set { it.copy(method = v) } }
            switch("compile", s(R.string.set_compile), st.compile, s(R.string.set_compile_hint)) { v -> set { it.copy(compile = v) } }
            slider("parallel", s(R.string.set_parallel), st.parallelDownloads, 1, 4, hint = s(R.string.set_parallel_hint)) { v -> set { it.copy(parallelDownloads = v) } }
            switch("keep_apks", s(R.string.set_keep_apks), st.keepApks) { v -> set { it.copy(keepApks = v) } }
            section(s(R.string.set_system))
            switch("system_auto", s(R.string.set_system_auto), st.systemAuto, s(R.string.set_system_auto_hint)) { v -> set { it.copy(systemAuto = v) } }
            if (st.systemAuto) {
                pick("system_scope", s(R.string.set_system_scope), st.systemScope, label = {
                    s(if (it == SystemScope.ALL) R.string.set_system_scope_all else R.string.set_system_scope_system)
                }) { v -> set { it.copy(systemScope = v) } }
                pick("system_way", s(R.string.set_system_way), st.systemWay, s(R.string.set_system_way_hint), label = {
                    s(
                        when (it) {
                            SystemWay.AUTO -> R.string.set_system_way_auto
                            SystemWay.MODULE -> R.string.set_system_way_module
                            SystemWay.DIRECT -> R.string.set_system_way_direct
                            SystemWay.RECOVERY -> R.string.set_system_way_recovery
                        },
                    )
                }) { v -> set { it.copy(systemWay = v) } }
            }
            switch("system_data", s(R.string.set_system_data), st.systemAlsoData, s(R.string.set_system_data_hint)) { v -> set { it.copy(systemAlsoData = v) } }
        }
    }
}
