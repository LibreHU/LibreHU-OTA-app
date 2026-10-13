package org.librehu.ota.data

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import org.librehu.ota.install.Installer
import org.librehu.ota.install.Root
import java.io.File
import java.security.MessageDigest
import java.util.Locale

data class ZipCategory(
    val id: String,
    val name: String,
    val description: String,
)

/** A flashable zip of the LibreHU-flashablesZips repository (its index.json). */
data class FlashZip(
    val id: String,
    val name: String,
    val category: String,
    val description: String,
    val warning: String,
    /** `ro.product.device` values it is made for; empty = any. */
    val devices: List<String>,
    val version: String,
    val url: String,
    val size: Long,
    val sha256: String,
    /** Image published alone, written straight to [partition] by root (TWRP itself, for instance). */
    val imageUrl: String,
    val imageSize: Long,
    val imageSha256: String,
    val partition: String,
    /** Id of the zip undoing this one. */
    val restore: String,
    /** Restore zip: shown from the zip it undoes only. */
    val hidden: Boolean,
    val upstream: String,
    val license: String,
) {
    val compatible get() = devices.isEmpty() || Build.DEVICE in devices
}

data class ZipIndex(
    val categories: List<ZipCategory>,
    val zips: List<FlashZip>,
    val generated: Long,
)

/**
 * Flashable zips by category, from `index.json` of the `zips` release of LibreHU-flashablesZips: flashed by TWRP
 * (OpenRecoveryScript, then a reboot to recovery), or, for an image, written straight to its partition as root.
 * Blocking: call from a background thread.
 */
object Zips {
    const val INDEX_URL = "https://github.com/LibreHU/LibreHU-flashablesZips/releases/download/zips/index.json"
    private const val PREF_INDEX = "zips_index_cache"
    private const val BACKUP_DIR = "/sdcard/LibreHU/backup"

    fun indexUrl(context: Context) = OtaSettingsStore.value(context).zipsIndexUrl.ifBlank { INDEX_URL }

    /** Index from the network, else the last one received (offline). */
    fun load(context: Context): Result<ZipIndex> {
        val prefs = context.getSharedPreferences("ota", Context.MODE_PRIVATE)
        val fresh = runCatching { GitHub.get(indexUrl(context)) }
        fresh.getOrNull()?.let { json -> runCatching { parse(json) }.onSuccess { prefs.edit().putString(PREF_INDEX, json).apply() } }
        val json =
            fresh.getOrNull() ?: prefs.getString(PREF_INDEX, null) ?: return Result.failure(fresh.exceptionOrNull() ?: Exception("index"))
        return runCatching { parse(json) }
    }

    fun parse(json: String): ZipIndex {
        val o = JSONObject(json)
        val fr = Locale.getDefault().language == "fr"
        val cats =
            o.optJSONArray("categories").objects().map { c ->
                ZipCategory(
                    c.getString("id"),
                    (if (fr) c.optString("name_fr") else "").ifEmpty { c.optString("name", c.getString("id")) },
                    (if (fr) c.optString("description_fr") else "").ifEmpty { c.optString("description") },
                )
            }
        val zips =
            o.optJSONArray("zips").objects().map { z ->
                FlashZip(
                    id = z.getString("id"),
                    name = z.optString("name", z.getString("id")),
                    category = z.optString("category"),
                    description = z.optString("description"),
                    warning = z.optString("warning"),
                    devices = z.optJSONArray("devices").strings(),
                    version = z.optString("version"),
                    url = z.getString("url"),
                    size = z.optLong("size"),
                    sha256 = z.optString("sha256"),
                    imageUrl = z.optString("image_url"),
                    imageSize = z.optLong("image_size"),
                    imageSha256 = z.optString("image_sha256"),
                    partition = z.optString("partition"),
                    restore = z.optString("restore"),
                    hidden = z.optBoolean("hidden"),
                    upstream = z.optString("upstream"),
                    license = z.optString("license"),
                )
            }
        return ZipIndex(cats, zips, o.optLong("generated"))
    }

    /** Downloads [z], checks it, and has TWRP flash it at the next start in recovery. */
    fun flash(
        context: Context,
        z: FlashZip,
    ): Configs.Outcome {
        if (!Root.isAvailable()) return Configs.Outcome.Failed("root")
        if (!z.compatible) return Configs.Outcome.Failed("device ${Build.DEVICE}")
        val file =
            try {
                Installer.download(context, z.url, "zip-${z.id}.zip") {}
            } catch (e: Exception) {
                return Configs.Outcome.Failed(e.message ?: e.javaClass.simpleName)
            }
        try {
            if (z.sha256.isNotBlank() && !sha256(file).equals(z.sha256, ignoreCase = true)) return Configs.Outcome.Failed("SHA-256")
            val r = file.inputStream().use { Configs.flash(it, "${z.id}.zip") }
            History.add(
                context,
                z.name,
                "${z.version} · TWRP",
                r is Configs.Outcome.Done,
                if (r is Configs.Outcome.Failed) r.reason else "OK",
            )
            return r
        } finally {
            file.delete()
        }
    }

    /**
     * Writes the image of [z] to its partition now (root, no recovery needed): device and size checked, the current
     * partition copied to /sdcard/LibreHU/backup first, the written blocks read back and compared.
     */
    fun writeImage(
        context: Context,
        z: FlashZip,
    ): Configs.Outcome {
        if (!Root.isAvailable()) return Configs.Outcome.Failed("root")
        if (!z.compatible) return Configs.Outcome.Failed("device ${Build.DEVICE}")
        if (z.imageUrl.isBlank() || !Regex("[a-z0-9_]+").matches(z.partition)) return Configs.Outcome.Failed("image")
        val file =
            try {
                Installer.download(context, z.imageUrl, "img-${z.id}.img") {}
            } catch (e: Exception) {
                return Configs.Outcome.Failed(e.message ?: e.javaClass.simpleName)
            }
        try {
            val sum = sha256(file)
            if (z.imageSha256.isNotBlank() && !sum.equals(z.imageSha256, ignoreCase = true)) return Configs.Outcome.Failed("SHA-256")
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(java.util.Date())
            val size = file.length()
            val script =
                """
                part=""
                for p in /dev/block/by-name/${z.partition} /dev/block/platform/*/by-name/${z.partition} /dev/block/platform/*/*/by-name/${z.partition}; do
                  [ -e "${'$'}p" ] && { part=${'$'}p; break; }
                done
                [ -n "${'$'}part" ] || { echo "NOPART"; exit 3; }
                psize=${'$'}(blockdev --getsize64 "${'$'}part")
                [ $size -le "${'$'}psize" ] || { echo "TOOBIG ${'$'}psize"; exit 4; }
                mkdir -p $BACKUP_DIR && dd if="${'$'}part" of=$BACKUP_DIR/${z.partition}-$stamp.img bs=1048576 2>/dev/null || { echo "BACKUP"; exit 5; }
                cat > "${'$'}part" && sync
                w=${'$'}(head -c $size "${'$'}part" | sha256sum | cut -d' ' -f1)
                [ "${'$'}w" = "$sum" ] || { echo "VERIFY"; exit 6; }
                echo "OK ${'$'}part"
                """.trimIndent()
            val out = Root.run(script, file.inputStream(), timeoutS = 300) ?: return Configs.Outcome.Failed("su")
            val r =
                when (out.exit) {
                    0 -> Configs.Outcome.Done("/dev/block/by-name/${z.partition} ($BACKUP_DIR/${z.partition}-$stamp.img)")
                    3 -> Configs.Outcome.Failed("partition ${z.partition}?")
                    4 -> Configs.Outcome.Failed("size")
                    5 -> Configs.Outcome.Failed("backup")
                    6 -> Configs.Outcome.Failed("verify: reflash $BACKUP_DIR/${z.partition}-$stamp.img")
                    else -> Configs.Outcome.Failed(out.output.ifEmpty { "exit ${out.exit}" })
                }
            History.add(
                context,
                z.name,
                "${z.version} → ${z.partition}",
                r is Configs.Outcome.Done,
                if (r is Configs.Outcome.Failed) r.reason else "OK",
            )
            return r
        } finally {
            file.delete()
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }
}
