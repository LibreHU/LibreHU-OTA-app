package org.librehu.ota.data

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import org.librehu.ota.install.Installer
import org.librehu.ota.install.Root
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

enum class ConfigType {
    /** A file put at [ConfigItem.dest] (audio policy, CAN box config…), the previous one saved first. */
    FILE,

    /** A zip flashed by TWRP: written to the internal storage, then TWRP's OpenRecoveryScript and a reboot. */
    RECOVERY_ZIP,
}

/** A configuration file or recovery zip offered by the catalogue (`configs` of catalog.json). */
data class ConfigItem(
    val id: String,
    val name: String,
    val description: String,
    val type: ConfigType,
    val url: String,
    /** Destination path ([ConfigType.FILE]). */
    val dest: String = "",
    val mode: String = "0644",
    val owner: String = "root:root",
    /** SHA-256 of the download (hex); empty = not checked. */
    val sha256: String = "",
    /** A reboot is needed for the file to be used. */
    val reboot: Boolean = false,
) {
    companion object {
        fun parse(catalogJson: String?): List<ConfigItem> {
            val a = runCatching { JSONObject(catalogJson ?: return emptyList()).optJSONArray("configs") }.getOrNull() ?: return emptyList()
            return (0 until a.length()).mapNotNull { i ->
                runCatching {
                    val o = a.getJSONObject(i)
                    ConfigItem(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        description = o.optString("description"),
                        type = ConfigType.valueOf(o.optString("type", "FILE").uppercase()),
                        url = o.getString("url"),
                        dest = o.optString("dest"),
                        mode = o.optString("mode", "0644"),
                        owner = o.optString("owner", "root:root"),
                        sha256 = o.optString("sha256"),
                        reboot = o.optBoolean("reboot", false),
                    )
                }.getOrNull()
            }
        }
    }
}

/**
 * Configuration files and recovery zips, from the catalogue or chosen on the device. Root. A partition protected by
 * dm-verity is never written (the head unit would not boot): verity has to be disabled first by the user
 * (`fastboot --disable-verity flash vbmeta vbmeta.img`). Blocking: call from a background thread.
 */
object Configs {
    /** Result: null = done, else the reason. */
    sealed class Outcome {
        data class Done(
            val detail: String,
        ) : Outcome()

        data class Failed(
            val reason: String,
        ) : Outcome()
    }

    private const val BACKUP_DIR = "/sdcard/LibreHU/backup"

    /** TWRP sees the internal storage as /data/media/0 (the /sdcard view only exists under Android). */
    private const val FLASH_DIR = "/data/media/0/LibreHU/flash"
    private const val ORS = "/cache/recovery/openrecoveryscript"

    fun items(context: Context): List<ConfigItem> =
        ConfigItem.parse(context.getSharedPreferences("ota", Context.MODE_PRIVATE).getString("catalog", null))

    /** Downloads [item] and applies it. */
    fun apply(
        context: Context,
        item: ConfigItem,
    ): Outcome {
        if (!Root.isAvailable()) return Outcome.Failed("root")
        val file =
            try {
                Installer.download(context, item.url, "config-${item.id}") {}
            } catch (e: Exception) {
                return Outcome.Failed(e.message ?: e.javaClass.simpleName)
            }
        try {
            if (item.sha256.isNotBlank() && !sha256(file).equals(item.sha256.trim(), ignoreCase = true)) {
                return Outcome.Failed("SHA-256")
            }
            val r =
                when (item.type) {
                    ConfigType.FILE -> putFile(file.inputStream(), item.dest, item.mode, item.owner)
                    ConfigType.RECOVERY_ZIP -> flash(file.inputStream(), "${item.id}.zip")
                }
            History.add(context, item.name, item.dest.ifEmpty { item.url.substringAfterLast('/') }, r is Outcome.Done, outcomeText(r))
            return r
        } finally {
            file.delete()
        }
    }

    /** A file chosen on the device, put at [dest]. */
    fun applyLocal(
        context: Context,
        uri: Uri,
        dest: String,
        mode: String = "0644",
        owner: String = "root:root",
    ): Outcome {
        if (!Root.isAvailable()) return Outcome.Failed("root")
        val input = context.contentResolver.openInputStream(uri) ?: return Outcome.Failed("file")
        val r = input.use { putFile(it, dest, mode, owner) }
        History.add(context, uri.lastPathSegment ?: "file", dest, r is Outcome.Done, outcomeText(r))
        return r
    }

    /** A zip chosen on the device, flashed by TWRP at the next start (which is done now: reboot to recovery). */
    fun flashLocal(
        context: Context,
        uri: Uri,
    ): Outcome {
        if (!Root.isAvailable()) return Outcome.Failed("root")
        val name =
            (uri.lastPathSegment?.substringAfterLast('/') ?: "update").replace(Regex("[^A-Za-z0-9._-]"), "_").let {
                if (it.endsWith(".zip")) it else "$it.zip"
            }
        val input = context.contentResolver.openInputStream(uri) ?: return Outcome.Failed("file")
        val r = input.use { flash(it, name) }
        History.add(context, name, "TWRP", r is Outcome.Done, outcomeText(r))
        return r
    }

    /** Reboots into the recovery, where TWRP runs the script written by [flash]. */
    fun rebootRecovery() {
        Root.run("svc power reboot recovery || reboot recovery")
    }

    private fun putFile(
        input: InputStream,
        dest: String,
        mode: String,
        owner: String,
    ): Outcome {
        if (!dest.startsWith("/") || dest.contains("..") || !Regex("[A-Za-z0-9_./+-]+").matches(dest)) return Outcome.Failed("path")
        if (!Regex("[0-7]{3,4}").matches(mode) || !Regex("[a-z0-9_]+(:[a-z0-9_]+)?").matches(owner)) return Outcome.Failed("mode")
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(java.util.Date())
        val backup = "$BACKUP_DIR${dest.replace('/', '_')}.$stamp"
        // Mount point of the destination (longest prefix in /proc/mounts), made writable unless dm-verity protects it.
        val script =
            """
            d='$dest'
            mp=${'$'}(awk '{print ${'$'}2}' /proc/mounts | while read m; do case "${'$'}d" in "${'$'}m"/*) echo "${'$'}{#m} ${'$'}m";; esac; done | sort -n | tail -n 1 | cut -d' ' -f2)
            [ -n "${'$'}mp" ] || mp=/
            dev=${'$'}(grep " ${'$'}mp " /proc/mounts | tail -n 1 | cut -d' ' -f1)
            opts=${'$'}(grep " ${'$'}mp " /proc/mounts | tail -n 1 | cut -d' ' -f4)
            if [ "${'$'}{opts#ro}" != "${'$'}opts" ]; then
              case "${'$'}dev" in /dev/block/dm-*) echo "VERITY ${'$'}mp ${'$'}dev"; exit 3;; esac
              blockdev --setrw "${'$'}dev" 2>/dev/null
              mount -o rw,remount "${'$'}mp" || { echo "READONLY ${'$'}mp"; exit 4; }
              wasro=1
            fi
            mkdir -p $BACKUP_DIR "${'$'}(dirname "${'$'}d")"
            [ -f "${'$'}d" ] && cp -p "${'$'}d" '$backup'
            cat > "${'$'}d.librehu" && mv -f "${'$'}d.librehu" "${'$'}d" && chmod $mode "${'$'}d" && chown $owner "${'$'}d"
            e=${'$'}?
            restorecon "${'$'}d" 2>/dev/null
            [ -n "${'$'}wasro" ] && mount -o ro,remount "${'$'}mp" 2>/dev/null
            exit ${'$'}e
            """.trimIndent()
        val out = Root.run(script, input) ?: return Outcome.Failed("su")
        return when (out.exit) {
            0 -> Outcome.Done(dest)
            3 -> Outcome.Failed("verity: " + out.output.substringAfter("VERITY ").trim())
            4 -> Outcome.Failed("read-only: " + out.output.substringAfter("READONLY ").trim())
            else -> Outcome.Failed(out.output.lines().lastOrNull { it.isNotBlank() } ?: "exit ${out.exit}")
        }
    }

    /** Zip [input] put in the internal storage as [name], TWRP's OpenRecoveryScript told to install it. */
    internal fun flash(
        input: InputStream,
        name: String,
    ): Outcome {
        val zip = "$FLASH_DIR/$name"
        val out =
            Root.run(
                "mkdir -p $FLASH_DIR /cache/recovery && cat > '$zip' && echo 'install $zip' > $ORS && chmod 0644 '$zip' $ORS",
                input,
            ) ?: return Outcome.Failed("su")
        return if (out.exit == 0) Outcome.Done(zip) else Outcome.Failed(out.output.ifEmpty { "exit ${out.exit}" })
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun outcomeText(o: Outcome) =
        when (o) {
            is Outcome.Done -> "OK"
            is Outcome.Failed -> o.reason
        }
}
