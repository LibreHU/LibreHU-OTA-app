package org.librehu.ota.install

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Puts an APK in /system/priv-app or /system/app, as root, active after a reboot: as a root module (Magisk, KernelSU,
 * KernelSU Next, APatch mount it over /system at boot, the partition is not touched), or written in the partition
 * when it can be remounted read-write (no dm-verity).
 */
object SystemInstall {
    /** Root manager installing modules from the command line. */
    enum class Manager(
        val label: String,
        val install: String,
    ) {
        MAGISK("Magisk", "magisk --install-module"),
        KERNELSU("KernelSU", "/data/adb/ksud module install"),
        APATCH("APatch", "/data/adb/apd module install"),
    }

    /** Where an APK would go in /system and how. */
    data class Plan(
        /** Privileged permissions defined on this ROM that the APK requests: granted only from /system/priv-app. */
        val privileged: List<String>,
        /** Directory of the copy in /system (or /product, /vendor) when there is one. */
        val current: String?,
        /** The version installed in /data is signed with another key: Android deletes it, data included, at boot. */
        val otherSignature: Boolean,
        val manager: Manager?,
        /**
         * Official KernelSU 3.0 and later only mounts module files in /system with a metamodule (meta-overlayfs…);
         * true when that manager is installed without one. KernelSU Next, SukiSU, Magisk and APatch mount them
         * themselves.
         */
        val needsMetamodule: Boolean,
        /** Mounts of /system when writing it failed: read-only partition. */
        val readOnly: String? = null,
    ) {
        /** priv-app when the APK needs it, or when it already is one; plain /system/app otherwise. */
        val recommendPrivApp get() = privileged.isNotEmpty() || current?.contains("/priv-app/") == true
    }

    fun plan(
        context: Context,
        apk: File,
        pkg: String,
    ): Plan {
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_PERMISSIONS or SIGNATURES)
        val privileged =
            archive?.requestedPermissions.orEmpty().filter { name ->
                try {
                    pm.getPermissionInfo(name, 0).protectionLevel and PermissionInfo.PROTECTION_FLAG_PRIVILEGED != 0
                } catch (_: PackageManager.NameNotFoundException) {
                    false
                }
            }
        val installed =
            try {
                pm.getPackageInfo(pkg, SIGNATURES)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }

        @Suppress("DEPRECATION")
        val otherSignature =
            installed != null && !Installer.isSystemApp(installed) && archive != null &&
                installed.signatures?.toSet() != archive.signatures?.toSet()
        val probe =
            Root
                .run(
                    "if [ -x /data/adb/ksud ]; then echo KERNELSU; elif [ -x /data/adb/apd ]; then echo APATCH; " +
                        "elif command -v magisk >/dev/null; then echo MAGISK; fi; [ -e /data/adb/metamodule ] && echo META",
                )?.output
                .orEmpty()
        val manager = Manager.entries.firstOrNull { probe.contains(it.name) }
        val officialKsu = installed(context, "me.weishu.kernelsu") && !installed(context, "com.rifsxd.ksunext")
        return Plan(
            privileged = privileged.sorted(),
            current = systemCopy(pkg),
            otherSignature = otherSignature,
            manager = manager,
            needsMetamodule = manager == Manager.KERNELSU && officialKsu && !probe.contains("META"),
        )
    }

    /**
     * As a root module `librehu_<package>` named [name]: system/priv-app/<pkg> (or system/app) and the
     * privapp-permissions whitelist, a previous copy elsewhere in /system hidden with `.replace`. Removed from the root
     * manager's modules list.
     */
    fun installModule(
        context: Context,
        apk: File,
        pkg: String,
        name: String,
        privApp: Boolean,
        plan: Plan,
    ): InstallResult {
        val manager = plan.manager ?: return InstallResult.Failed("Magisk / KernelSU / APatch")
        val info = Installer.archiveInfo(context, apk) ?: return InstallResult.Failed("APK")
        if (info.packageName != pkg) return InstallResult.Failed("APK")
        val id = "librehu_" + pkg.replace('.', '_')
        val dir = "system/${if (privApp) "priv-app" else "app"}/$pkg"
        val zip = File(context.cacheDir, "apk/$id.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            fun text(
                path: String,
                content: String,
            ) {
                z.putNextEntry(ZipEntry(path))
                z.write(content.toByteArray())
                z.closeEntry()
            }
            text(
                "module.prop",
                """
                id=$id
                name=$name (${if (privApp) "priv-app" else "app"})
                version=${info.versionName}
                versionCode=${if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else 1}
                author=LibreHU Apps
                description=$pkg in /system/${if (privApp) "priv-app" else "app"}, added by LibreHU Apps.
                """.trimIndent() + "\n",
            )
            // Magisk runs META-INF's installer; KernelSU and APatch use their own and read customize.sh too.
            text("META-INF/com/google/android/update-binary", UPDATE_BINARY)
            text("META-INF/com/google/android/updater-script", "#MAGISK\n")
            text("customize.sh", "set_perm_recursive \"\$MODPATH/system\" 0 0 0755 0644\n")
            z.putNextEntry(ZipEntry("$dir/$pkg.apk"))
            apk.inputStream().use { it.copyTo(z) }
            z.closeEntry()
            if (privApp && plan.privileged.isNotEmpty()) {
                text("system/etc/permissions/privapp-permissions-$pkg.xml", whitelist(pkg, plan.privileged))
            }
            // The module's directory replaces the one in /system (stale oat), and hides a copy under another name.
            text("$dir/.replace", "")
            plan.current
                ?.takeIf { it.startsWith("/system/") && it.trimEnd('/') != "/$dir" }
                ?.let { text(it.removePrefix("/").trimEnd('/') + "/.replace", "") }
        }
        val tmp = "/data/local/tmp/$id.zip"
        try {
            Root.run("cat > $tmp", zip.inputStream()) ?: return InstallResult.Failed("copy")
            val out = Root.run("${manager.install} $tmp", timeoutS = 180) ?: return InstallResult.Failed("su")
            return if (out.exit == 0) {
                InstallResult.RebootNeeded
            } else {
                InstallResult.Failed(out.output.lines().lastOrNull { it.isNotBlank() } ?: "exit ${out.exit}")
            }
        } finally {
            Root.run("rm -f $tmp")
            zip.delete()
        }
    }

    /**
     * Written in the /system partition: /system/priv-app/<pkg> ([privApp], with the privileged permissions it asks for
     * whitelisted in /system/etc/permissions, without which Android 9 does not start when
     * `ro.control_privapp_permissions=enforce`), or /system/app/<pkg>. Replaces the copy there was in /system; the
     * version in /data, if any, stays an update of it with its data. [InstallResult.ReadOnly] when /system cannot be
     * remounted read-write (dm-verity, overlay of root modules).
     */
    fun installDirect(
        apk: File,
        pkg: String,
        privApp: Boolean,
        plan: Plan,
    ): InstallResult {
        // Written through the private read-write mount of the system partition ($S = /system there, see RwMount).
        val rel = "${if (privApp) "priv-app" else "app"}/$pkg"
        val dir = "\$S/$rel"
        val xml = "\$S/etc/permissions/privapp-permissions-$pkg.xml"
        val tmp = "/data/local/tmp/librehu-ota"
        Root.run("rm -rf $tmp && mkdir -p $tmp && cat > $tmp/base.apk", apk.inputStream()) ?: return InstallResult.Failed("copy")
        if (privApp && plan.privileged.isNotEmpty()) {
            Root.run("cat > $tmp/perms.xml", whitelist(pkg, plan.privileged).byteInputStream()) ?: return InstallResult.Failed("copy")
        }
        val script =
            listOfNotNull(
                // The previous copy, wherever it was (another directory name, or app instead of priv-app).
                plan.current
                    ?.trimEnd('/')
                    ?.takeIf { it != "/system/$rel" && it.startsWith("/system/") && Regex("[A-Za-z0-9_./-]+").matches(it) }
                    ?.let { "rm -rf \"\$S/${it.removePrefix("/system/")}\"" },
                "rm -rf \"$dir\"",
                "mkdir -p \"$dir\"",
                "cp $tmp/base.apk \"$dir/$pkg.apk\"",
                "chmod 755 \"$dir\"",
                "chmod 644 \"$dir/$pkg.apk\"",
                if (privApp && plan.privileged.isNotEmpty()) "cp $tmp/perms.xml \"$xml\" && chmod 644 \"$xml\"" else "rm -f \"$xml\"",
                "(chcon -R u:object_r:system_file:s0 \"$dir\" 2>/dev/null; chcon u:object_r:system_file:s0 \"$xml\" 2>/dev/null; true)",
                "sync",
            ).joinToString(" && ")
        val out =
            Root.run(
                "${RwMount.LIB}\nif ! rw_open /system; then rm -rf $tmp; exit $READ_ONLY_EXIT; fi\nS=\$RW\n" +
                    "$script\ne=\$?\nrw_close\nrm -rf $tmp\nexit \$e",
            ) ?: return InstallResult.Failed("su")
        return when {
            out.exit == 0 -> InstallResult.RebootNeeded
            out.exit == READ_ONLY_EXIT -> InstallResult.ReadOnly(readOnlyReason(out.output))
            else -> InstallResult.Failed(out.output.ifEmpty { "exit ${out.exit}" })
        }
    }

    private fun whitelist(
        pkg: String,
        permissions: List<String>,
    ) = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<!-- LibreHU Apps -->\n<permissions>\n")
        append("    <privapp-permissions package=\"$pkg\">\n")
        permissions.forEach { append("        <permission name=\"$it\"/>\n") }
        append("    </privapp-permissions>\n</permissions>\n")
    }

    /**
     * Directory of the /system copy of [pkg], also when an update in /data hides it: `dumpsys package` lists both
     * code paths.
     */
    private fun systemCopy(pkg: String): String? =
        Root
            .run("dumpsys package $pkg | grep codePath=")
            ?.output
            ?.lines()
            ?.map { it.substringAfter("codePath=").trim() }
            ?.firstOrNull { p -> SYSTEM_DIRS.any { p.startsWith(it) } }

    private fun installed(
        context: Context,
        pkg: String,
    ) = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private const val READ_ONLY_EXIT = 30

    /** Why [RwMount] could not open the partition, with what to do under dm-verity. */
    internal fun readOnlyReason(output: String): String {
        val line = output.lines().lastOrNull { it.isNotBlank() }?.trim() ?: return "read-only"
        return if (line.startsWith("VERITY")) {
            "dm-verity (${line.removePrefix("VERITY").trim()}): fastboot --disable-verity flash vbmeta vbmeta.img"
        } else {
            line
        }
    }

    /** Magisk's standard module installer (module_installer.sh). */
    private val UPDATE_BINARY =
        """
        #!/sbin/sh

        umask 022

        ui_print() { echo "${'$'}1"; }

        require_new_magisk() {
          ui_print "Please install Magisk v20.4+!"
          exit 1
        }

        OUTFD=${'$'}2
        ZIPFILE=${'$'}3

        mount /data 2>/dev/null

        [ -f /data/adb/magisk/util_functions.sh ] || require_new_magisk
        . /data/adb/magisk/util_functions.sh
        [ ${'$'}MAGISK_VER_CODE -lt 20400 ] && require_new_magisk

        install_module
        exit 0
        """.trimIndent() + "\n"

    @Suppress("DEPRECATION")
    private const val SIGNATURES = PackageManager.GET_SIGNATURES

    private val SYSTEM_DIRS = listOf("/system/", "/product/", "/vendor/")
}
