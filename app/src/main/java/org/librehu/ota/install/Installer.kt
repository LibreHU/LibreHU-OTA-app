package org.librehu.ota.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
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

    /** Put in /system (written there, or as a root module): active after a reboot. */
    data object RebootNeeded : InstallResult()

    /** /system could not be made writable ([detail]: its mounts); a root module still can add the app. */
    data class ReadOnly(
        val detail: String,
    ) : InstallResult()

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
}
