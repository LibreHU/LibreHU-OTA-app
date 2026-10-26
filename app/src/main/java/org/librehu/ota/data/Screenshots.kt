package org.librehu.ota.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** A screenshot: image URL and its caption (may be empty). */
data class Shot(
    val url: String,
    val caption: String,
)

/**
 * Screenshots of the apps (gallery of the LibreHU site, `assets/shots/index.json`) and image loading for them and for
 * the zips' screenshots: https only, cached on disk (cache dir) and in memory, decoded at the size they are shown.
 * Blocking: call from a background thread.
 */
object Screenshots {
    const val SITE = "https://librehu.github.io/assets/shots/"

    /** Catalogue id → folder of the site's gallery, when they differ. */
    private val FOLDER = mapOf("mcutools" to "mcu")

    @Volatile
    private var apps: Map<String, List<Shot>>? = null

    /** Screenshots of the catalogue app [appId], empty when the gallery has none or cannot be read. */
    fun forApp(appId: String): List<Shot> {
        val all = apps ?: runCatching { parse(GitHub.get(SITE + "index.json")) }.getOrNull()?.also { apps = it } ?: return emptyList()
        return all[FOLDER[appId] ?: appId].orEmpty()
    }

    private fun parse(json: String): Map<String, List<Shot>> {
        val fr = Locale.getDefault().language == "fr"
        val o = JSONObject(json).getJSONObject("apps")
        return o.keys().asSequence().associateWith { app ->
            val a = o.getJSONArray(app)
            (0 until a.length()).map { a.getJSONObject(it) }.filter { SAFE_FILE.matches(it.optString("file")) }.map {
                Shot(SITE + it.getString("file"), (if (fr) it.optString("fr") else "").ifEmpty { it.optString("en") })
            }
        }
    }

    private val SAFE_FILE = Regex("[a-z0-9_-]+/[A-Za-z0-9._-]+\\.(png|jpg|jpeg|webp)")

    private val memory =
        object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
            override fun sizeOf(
                key: String,
                value: Bitmap,
            ) = value.byteCount
        }

    /** [url] decoded to about [maxWidth] pixels wide, or null (not https, network, not an image). */
    fun bitmap(
        context: Context,
        url: String,
        maxWidth: Int,
    ): Bitmap? {
        if (!url.startsWith("https://")) return null
        val key = "$maxWidth $url"
        memory.get(key)?.let { return it }
        val file = cached(context, url) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) {
            file.delete()
            return null
        }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        memory.put(key, bmp)
        return bmp
    }

    /** Image file in the cache dir, downloaded once (at most 8 MB). */
    private fun cached(
        context: Context,
        url: String,
    ): File? {
        val dir = File(context.cacheDir, "shots").apply { mkdirs() }
        val name = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(dir, name)
        if (file.length() > 0) return file
        return runCatching {
            val tmp = File(dir, "$name.part")
            val c = GitHub.open(url)
            try {
                if (c.responseCode !in 200..299) return null
                c.inputStream.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16)
                        var total = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > 8L shl 20) return null
                            out.write(buf, 0, n)
                        }
                    }
                }
            } finally {
                c.disconnect()
            }
            if (tmp.renameTo(file)) file else null
        }.getOrNull()
    }
}
