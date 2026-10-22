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

/** A download stopped by a pause: [done] bytes kept in the `.part` file. */
class DownloadPaused(
    val done: Long,
    val total: Long,
) : IOException("paused")

/** Download, checks and installation of an APK (root `pm install`, or Android's package installer). */
object Installer {
    /** One compilation at a time (several updates in a row would load the CPU for minutes otherwise). */
    private val compiler =
        java.util.concurrent.Executors
            .newSingleThreadExecutor()

    /** Downloads [url] into the cache, [onProgress] in 0..1 (or -1 without a size). */
    fun download(
        context: Context,
        url: String,
        name: String,
        onProgress: (Float) -> Unit,
    ): File = download(context, url, name) { done, total -> onProgress(if (total > 0) done.toFloat() / total else -1f) }

    /**
     * Downloads [url] into the cache as [name], resuming a `.part` left by a pause (HTTP Range, with `If-Range` on the
     * ETag of the first response, so that a file replaced on the server starts again from zero). [onProgress] gets the
     * bytes done and the total (-1 when unknown), at most every 250 ms; [paused] is asked between two blocks and stops
     * it with [DownloadPaused], keeping the part. [expectedSize] (when known, > 0) is checked at the end.
     */
    fun download(
        context: Context,
        url: String,
        name: String,
        expectedSize: Long = 0,
        paused: () -> Boolean = { false },
        onProgress: (done: Long, total: Long) -> Unit,
    ): File {
        val dir = File(context.cacheDir, "apk").apply { mkdirs() }
        val dest = File(dir, name)
        val part = File(dir, "$name.part")
        // Where the part comes from: its URL and ETag; another URL starts again.
        val meta = File(dir, "$name.part.src")
        val previous = runCatching { meta.readLines() }.getOrDefault(emptyList())
        if (previous.firstOrNull() != url) part.delete()
        var start = if (part.exists()) part.length() else 0L
        val c = GitHub.open(url)
        if (start > 0) {
            c.setRequestProperty("Range", "bytes=$start-")
            previous.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { c.setRequestProperty("If-Range", it) }
        }
        try {
            val code = c.responseCode
            if (code == 416 && expectedSize > 0 && start == expectedSize) {
                // Everything was already there.
                return finish(part, dest, meta, expectedSize)
            }
            if (code == 416) {
                part.delete()
                meta.delete()
                throw IOException("HTTP 416")
            }
            if (code !in 200..299) throw IOException("HTTP $code")
            val append = code == 206
            if (!append) start = 0
            val length = c.contentLengthLong
            val total =
                if (length > 0) {
                    start + length
                } else if (expectedSize > 0) {
                    expectedSize
                } else {
                    -1L
                }
            meta.writeText("$url\n${c.getHeaderField("ETag").orEmpty()}\n")
            c.inputStream.use { input ->
                java.io.FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = start
                    var lastAt = 0L
                    onProgress(done, total)
                    while (true) {
                        if (paused()) throw DownloadPaused(done, total)
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastAt >= 250) {
                            lastAt = now
                            onProgress(done, total)
                        }
                    }
                    onProgress(done, total)
                }
            }
        } finally {
            c.disconnect()
        }
        return finish(part, dest, meta, expectedSize)
    }

    private fun finish(
        part: File,
        dest: File,
        meta: File,
        expectedSize: Long,
    ): File {
        if (expectedSize > 0 && part.length() != expectedSize) {
            val got = part.length()
            part.delete()
            meta.delete()
            throw IOException("size $got / $expectedSize")
        }
        dest.delete()
        if (!part.renameTo(dest)) throw IOException("rename ${part.name}")
        meta.delete()
        return dest
    }

    /** Drops what a paused download of [name] kept. */
    fun discardPart(
        context: Context,
        name: String,
    ) {
        val dir = File(context.cacheDir, "apk")
        File(dir, "$name.part").delete()
        File(dir, "$name.part.src").delete()
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

    /**
     * Native code for [pkg] now (root, in the background, one at a time): `speed`, the whole app. Android 9 installs an
     * app from outside a store as `speed-profile`, which is `verify` without a profile (no native code at all), and
     * compiles it only when the head unit is idle and charging, which almost never happens: the app stayed slow.
     * (`speed-profile` here did nothing either: the profile inside an APK is not read by `cmd package compile`.)
     */
    fun compileNow(pkg: String) {
        if (!Regex("[A-Za-z0-9_.]+").matches(pkg)) return
        compiler.execute {
            val out = Root.run("cmd package compile -m speed -f $pkg", timeoutS = 600)
            android.util.Log.i("LibreHU-OTA", "compile $pkg: ${out?.output?.trim()?.lines()?.lastOrNull()}")
        }
    }

    /** Installs [apk]: silently as root when possible, otherwise through Android's installer. */
    fun install(
        context: Context,
        apk: File,
        pkg: String,
        method: org.librehu.ota.data.InstallMethod = org.librehu.ota.data.InstallMethod.AUTO,
        compile: Boolean = true,
    ): InstallResult {
        val useRoot =
            when (method) {
                org.librehu.ota.data.InstallMethod.ANDROID -> false
                org.librehu.ota.data.InstallMethod.ROOT -> if (Root.isAvailable()) true else return InstallResult.Failed("root")
                org.librehu.ota.data.InstallMethod.AUTO -> Root.isAvailable()
            }
        if (useRoot) {
            val out = Root.pmInstall(apk) ?: return InstallResult.Failed("su")
            if (out.output.contains("Success") && compile) compileNow(pkg)
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
