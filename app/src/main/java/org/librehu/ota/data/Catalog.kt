package org.librehu.ota.data

import org.json.JSONArray
import org.json.JSONObject

/** Integration branch of the apps: which head unit software they talk to. */
enum class Edition(
    val branch: String,
) {
    /** LibreHU-service replaces Jancar's ivi-services. */
    LIBREHU_SERVICE("librehu-service"),

    /** Jancar ivi-services (stock ROM). */
    IVI("ivi"),

    /** Generic Android, no head unit integration. */
    GENERIC("main"),
}

/** A LibreHU app published on GitHub. */
data class CatalogApp(
    val id: String,
    val name: String,
    /** owner/repository on GitHub. */
    val repo: String,
    val packageName: String,
    val description: String = "",
    /** Branches published as `latest-<branch>` releases; the first one is used when the edition's is missing. */
    val branches: List<String> = listOf("main"),
    /** Classic releases (tags, newest first) instead of rolling `latest-<branch>` ones. */
    val tagged: Boolean = false,
) {
    fun branchFor(edition: Edition): String = if (edition.branch in branches) edition.branch else branches.first()

    companion object {
        fun fromJson(o: JSONObject): CatalogApp {
            val b = o.optJSONArray("branches")
            return CatalogApp(
                id = o.getString("id"),
                name = o.getString("name"),
                repo = o.getString("repo"),
                packageName = o.getString("package"),
                description = o.optString("description"),
                branches = if (b == null) listOf("main") else List(b.length()) { b.getString(it) },
                tagged = o.optBoolean("tagged", false),
            )
        }

        fun parse(json: String): List<CatalogApp> {
            val apps = JSONObject(json).getJSONArray("apps")
            return List(apps.length()) { fromJson(apps.getJSONObject(it)) }
        }
    }
}

/** Built-in catalogue; `catalog.json` of the LibreHU-OTA-app repository replaces it when it can be downloaded. */
object Catalog {
    const val REMOTE_URL = "https://raw.githubusercontent.com/LibreHU/LibreHU-OTA-app/main/catalog.json"

    private val ALL = listOf("librehu-service", "ivi", "main")

    val BUILT_IN =
        listOf(
            CatalogApp("service", "LibreHU service", "LibreHU/LibreHU-service", "org.librehu.service", "MCU, audio, Bluetooth, OBD"),
            CatalogApp("launcher", "LibreHU Launcher", "LibreHU/LibreHU-Launcher-App", "org.librehu.launcher", branches = ALL),
            CatalogApp("dialer", "LibreHU Téléphone", "LibreHU/LibreHU-dialer-app", "org.librehu.dialer", branches = ALL),
            CatalogApp("fm", "LibreHU FM", "LibreHU/LibreHU-FM-app", "org.librehu.fm", branches = ALL),
            CatalogApp("widgets", "LibreHU Widgets", "LibreHU/LibreHU-widget-app", "org.librehu.widgets", branches = ALL),
            CatalogApp("btnremap", "LibreHU Touches", "LibreHU/LibreHU-BtnRemap-app", "org.librehu.btnremap", branches = ALL),
            CatalogApp("easysource", "LibreHU EasySource", "LibreHU/LibreHU-EasySource-app", "org.librehu.easysource"),
            CatalogApp("disc", "LibreHU Disc", "LibreHU/LibreHU-disc-app", "org.librehu.disc", "CD / DVD USB"),
            CatalogApp(
                "viper",
                "ViPER4Android LibreHU",
                "LibreHU/LibreHU-ViPER4Android-app",
                "com.llsl.viper4android",
                branches = listOf("librehu-service"),
            ),
            CatalogApp("mcutools", "MCU Toolkit", "LibreHU/MCU-tools-app", "com.dokt.jacmcu", "Diagnostic MCU"),
            CatalogApp(
                "headunit",
                "Android Auto (Open Headunit LibreHU)",
                "LibreHU/LibreHU-open-headunit-app",
                "org.librehu.headunit",
            ),
            CatalogApp(
                "carplay",
                "CarPlay (xcertplay LibreHU)",
                "LibreHU/LibreHU-xcertplay-app",
                "org.librehu.carplay",
                branches = listOf("master"),
            ),
            CatalogApp("ota", "LibreHU Apps (OTA)", "LibreHU/LibreHU-OTA-app", "org.librehu.ota"),
        )

    fun toJson(apps: List<CatalogApp>): String =
        JSONObject()
            .put(
                "apps",
                JSONArray().apply {
                    for (a in apps) {
                        put(
                            JSONObject()
                                .put("id", a.id)
                                .put("name", a.name)
                                .put("repo", a.repo)
                                .put("package", a.packageName)
                                .put("description", a.description)
                                .put("branches", JSONArray(a.branches))
                                .put("tagged", a.tagged),
                        )
                    }
                },
            ).toString(2)
}
