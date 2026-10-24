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
    /** Classic releases (tags, newest first) instead of rolling `latest-<branch>` ones; prereleases skipped. */
    val tagged: Boolean = false,
    /**
     * Regex the APK of a release must match, for a repository publishing several (KernelSU Next: normal and spoofed);
     * its first group, if any, is the versionCode when the release notes do not give one. Empty: the first APK.
     */
    val asset: String = "",
    /**
     * SHA-256 of the signing certificate(s) the APK must carry (comma separated), for an app outside LibreHU; a
     * LibreHU repository needs the LibreHU key without it ([org.librehu.ota.install.Signatures]).
     */
    val cert: String = "",
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
                asset = o.optString("asset"),
                cert = o.optString("cert"),
            )
        }

        /** Package names and repositories go into URLs and root commands (`pm`, modules): checked, others dropped. */
        private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        private val REPO = Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")

        fun parse(json: String): List<CatalogApp> {
            val apps = JSONObject(json).getJSONArray("apps")
            return List(apps.length()) { fromJson(apps.getJSONObject(it)) }.filter { PACKAGE.matches(it.packageName) && REPO.matches(it.repo) }
        }
    }
}

/** Built-in catalogue; `catalog.json` of the LibreHU-OTA-app repository replaces it when it can be downloaded. */
object Catalog {
    const val REMOTE_URL = "https://raw.githubusercontent.com/LibreHU/LibreHU-OTA-app/main/catalog.json"

    private val ALL = listOf("librehu-service", "ivi", "main")

    /** KernelSU_Next_<versionName>_<versionCode>-release.apk (manager/app/build.gradle.kts), not the -spoofed one. */
    private const val KSU_NEXT_APK = "^KernelSU_Next_v[0-9.]+_([0-9]+)-release\\.apk$"

    /**
     * Certificate of KernelSU Next's manager: the one its kernel accepts (KSU_NEXT_MANAGER_HASH, kernel/Kbuild of
     * KernelSU-Next, SHA-256 of the signing certificate).
     */
    private const val KSU_NEXT_CERT = "79e590113c4c4c0c222978e413a5faa801666957b1212a328e46c00c69821bf7"

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
            CatalogApp("helper", "LibreHU Helper", "LibreHU/LibreHU-helper-app", "org.librehu.helper", "Diagnostic et portage"),
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
            CatalogApp(
                "kernelsu-next",
                "KernelSU Next",
                "KernelSU-Next/KernelSU-Next",
                "com.rifsxd.ksunext",
                "Root du noyau LibreHU",
                tagged = true,
                asset = KSU_NEXT_APK,
                cert = KSU_NEXT_CERT,
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
                                .put("tagged", a.tagged)
                                .put("asset", a.asset)
                                .put("cert", a.cert),
                        )
                    }
                },
            ).toString(2)
}
