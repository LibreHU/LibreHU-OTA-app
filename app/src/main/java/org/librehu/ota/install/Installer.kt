package org.librehu.ota.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import org.librehu.ota.data.GitHub
import java.io.File
import java.io.IOException

/** Outcome of an install. */
sealed class InstallResult {
    data object Done : InstallResult()

    /** Android's installer asks the user ([InstallReceiver] opens its screen); the result comes later. */
    data object Pending : InstallResult()

    /** The installed app has another signature: it must be uninstalled first (its data is lost). */
    data object Incompatible : InstallResult()

    /**
     * Older version refused: Android only downgrades debuggable apps (or on debuggable ROMs). It has to be
     * uninstalled first, or as root with its data kept (`pm uninstall -k`).
     */
    data object Downgrade : InstallResult()

    /** System app replaced in /system: active after a reboot. */
    data object RebootNeeded : InstallResult()

    data class Failed(
        val message: String,
    ) : InstallResult()
}

/** Download, checks and installation of an APK (root `pm install`, or Android's package installer). */
object Installer {
    /** Downloads [url] into the cache, [onProgress] in 0..1 (or -1 without a size). */
    fun download(
        context: Context,
        url: String,
        name: String,
        onProgress: (Float) -> Unit,
    ): File {
        val dir = File(context.cacheDir, "apk").apply { mkdirs() }
        val dest = File(dir, name)
        val part = File(dir, "$name.part")
        val c = GitHub.open(url)
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            val total = c.contentLengthLong
            c.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - last > 256 * 1024) {
                            last = done
                            onProgress(if (total > 0) done.toFloat() / total else -1f)
                        }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        if (!part.renameTo(dest)) throw IOException("rename ${part.name}")
        return dest
    }

    /** Package of the downloaded APK, or null when Android cannot read it. */
    fun archiveInfo(
        context: Context,
        apk: File,
    ): PackageInfo? = context.packageManager.getPackageArchiveInfo(apk.path, 0)

    fun installed(
        context: Context,
        pkg: String,
    ): PackageInfo? =
        try {
            context.packageManager.getPackageInfo(pkg, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

    fun isSystemApp(info: PackageInfo?): Boolean = info?.applicationInfo?.let { it.flags and ApplicationInfo.FLAG_SYSTEM != 0 } == true

    /** Installs [apk]: silently as root when possible, otherwise through Android's installer. */
    fun install(
        context: Context,
        apk: File,
        pkg: String,
    ): InstallResult {
        if (Root.isAvailable()) {
            val out = Root.pmInstall(apk) ?: return InstallResult.Failed("su")
            if (out.output.contains("Success")) {
                // Compile now with the profile shipped in the APK (Compose's): smooth from the first start instead of
                // after Android's overnight optimisation.
                Thread({ Root.run("cmd package compile -m speed-profile -f $pkg", timeoutS = 300) }, "compile").start()
            }
            return when {
                out.output.contains("Success") -> InstallResult.Done
                out.output.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") ||
                    out.output.contains("INSTALL_FAILED_SHARED_USER_INCOMPATIBLE") -> InstallResult.Incompatible
                out.output.contains("INSTALL_FAILED_VERSION_DOWNGRADE") -> InstallResult.Downgrade
                else -> InstallResult.Failed(out.output.lines().lastOrNull { it.isNotBlank() } ?: "pm install ${out.exit}")
            }
        }
        return session(context, apk, pkg)
    }

    private fun session(
        context: Context,
        apk: File,
        pkg: String,
    ): InstallResult {
        val installer = context.packageManager.packageInstaller
        val params =
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(pkg)
                setSize(apk.length())
            }
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            apk.inputStream().use { input ->
                s.openWrite("base.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    s.fsync(out)
                }
            }
            val intent = Intent(context, InstallReceiver::class.java).putExtra(InstallReceiver.EXTRA_PACKAGE, pkg)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            s.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
        return InstallResult.Pending
    }

    /**
     * Uninstalls [pkg] (root silently, else Android's confirmation). [keepData] (root): `pm uninstall -k`, the data
     * stays for the version installed next. True when it is gone (root) or asked.
     */
    fun uninstall(
        context: Context,
        pkg: String,
        keepData: Boolean = false,
    ): Boolean {
        if (Root.isAvailable()) return Root.run("pm uninstall ${if (keepData) "-k " else ""}$pkg")?.output?.contains("Success") == true
        context.startActivity(
            Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return true
    }

    /** Where an APK would go in /system: [privileged] permissions it asks for, and the copy already there. */
    data class SystemPlan(
        /** Privileged permissions defined on this ROM that the APK requests: granted only from /system/priv-app. */
        val privileged: List<String>,
        /** Directory of the copy in /system (or /product, /vendor) when there is one. */
        val current: String?,
        /** The version installed in /data is signed with another key: Android deletes it, data included, at boot. */
        val otherSignature: Boolean,
    ) {
        /** priv-app when the APK needs it, or when it already is one; plain /system/app otherwise. */
        val recommendPrivApp get() = privileged.isNotEmpty() || current?.contains("/priv-app/") == true
    }

    fun systemPlan(
        context: Context,
        apk: File,
        pkg: String,
    ): SystemPlan {
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
            installed != null && !isSystemApp(installed) && archive != null &&
                installed.signatures?.toSet() != archive.signatures?.toSet()
        return SystemPlan(privileged.sorted(), systemCopy(pkg), otherSignature)
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

    /**
     * Installs [apk] in /system, as root: /system/priv-app/<pkg> ([privApp], with the privileged permissions it asks
     * for whitelisted in /system/etc/permissions, without which Android 9 does not start when
     * `ro.control_privapp_permissions=enforce`), or /system/app/<pkg>. Replaces the copy there was in /system; the
     * version in /data, if any, stays an update of it with its data. Active after a reboot.
     */
    fun installSystem(
        context: Context,
        apk: File,
        pkg: String,
        privApp: Boolean,
    ): InstallResult {
        if (!Root.isAvailable()) return InstallResult.Failed("root")
        if (archiveInfo(context, apk)?.packageName != pkg) return InstallResult.Failed("APK")
        val plan = systemPlan(context, apk, pkg)
        val dir = "/system/${if (privApp) "priv-app" else "app"}/$pkg"
        val xml = "/system/etc/permissions/privapp-permissions-$pkg.xml"
        val tmp = "/data/local/tmp/librehu-ota"
        Root.run("rm -rf $tmp && mkdir -p $tmp && cat > $tmp/base.apk", apk.inputStream()) ?: return InstallResult.Failed("copy")
        if (privApp && plan.privileged.isNotEmpty()) {
            val whitelist =
                buildString {
                    append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<!-- LibreHU Apps -->\n<permissions>\n")
                    append("    <privapp-permissions package=\"$pkg\">\n")
                    plan.privileged.forEach { append("        <permission name=\"$it\"/>\n") }
                    append("    </privapp-permissions>\n</permissions>\n")
                }
            Root.run("cat > $tmp/perms.xml", whitelist.byteInputStream()) ?: return InstallResult.Failed("copy")
        }
        val script =
            listOfNotNull(
                "(mount -o rw,remount /system 2>/dev/null || mount -o rw,remount /)",
                // The previous copy, wherever it was (another directory name, or app instead of priv-app).
                plan.current?.takeIf { it != dir && it.startsWith("/system/") }?.let { "rm -rf '$it'" },
                "rm -rf '$dir'",
                "mkdir -p '$dir'",
                "cp $tmp/base.apk '$dir/$pkg.apk'",
                "chmod 755 '$dir'",
                "chmod 644 '$dir/$pkg.apk'",
                if (privApp && plan.privileged.isNotEmpty()) "cp $tmp/perms.xml '$xml' && chmod 644 '$xml'" else "rm -f '$xml'",
                "(chcon -R u:object_r:system_file:s0 '$dir' 2>/dev/null; chcon u:object_r:system_file:s0 '$xml' 2>/dev/null; true)",
                "sync",
            ).joinToString(" && ")
        val out =
            Root.run(
                "$script; e=\$?; rm -rf $tmp; (mount -o ro,remount /system 2>/dev/null || mount -o ro,remount / 2>/dev/null); exit \$e",
            )
                ?: return InstallResult.Failed("su")
        return if (out.exit == 0) InstallResult.RebootNeeded else InstallResult.Failed(out.output.ifEmpty { "exit ${out.exit}" })
    }

    @Suppress("DEPRECATION")
    private const val SIGNATURES = PackageManager.GET_SIGNATURES

    private val SYSTEM_DIRS = listOf("/system/", "/product/", "/vendor/")
}
