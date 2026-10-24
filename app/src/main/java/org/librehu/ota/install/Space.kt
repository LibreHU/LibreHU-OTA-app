package org.librehu.ota.install

import android.content.Context
import android.os.StatFs
import java.io.File

/** Free space checked before a download: one that fails at 90 % for want of room is avoided. */
object Space {
    /** Room kept free besides the file (Android, the compiled code of an app). */
    private const val MARGIN = 64L * 1024 * 1024

    fun free(dir: File): Long = runCatching { StatFs(dir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)

    /**
     * Null when [download] bytes still to come fit in the cache with [copies] more copies of [size] (the installed APK,
     * the zip handed to TWRP), else the megabytes missing.
     */
    fun missingMb(
        context: Context,
        download: Long,
        size: Long,
        copies: Int = 1,
    ): Long? {
        if (size <= 0 && download <= 0) return null
        val need = download.coerceAtLeast(0) + size.coerceAtLeast(0) * copies + MARGIN
        val free = free(context.cacheDir)
        return if (free >= need) null else (need - free + (1 shl 20) - 1) / (1 shl 20)
    }
}
