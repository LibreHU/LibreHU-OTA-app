package org.librehu.ota.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.librehu.ota.R
import org.librehu.ota.install.InstallResult
import org.librehu.ota.install.Installer
import org.librehu.ota.install.Signatures
import org.librehu.ota.install.Space
import java.io.File

/**
 * Installs from a USB key, offline: the APKs, zips and images of a folder chosen with Android's file picker (its root
 * and the folders right under it). Each file is copied here then checked before anything is done:
 *  - an APK must carry the LibreHU key, or the key of the app already installed ([Signatures.checkLocal]);
 *  - a zip or an image must have the SHA-256 of an entry of the zips list received the last time the head unit was
 *    online ([Zips.load] keeps it), then it goes the same way as from the list (TWRP, or written to its partition).
 * Blocking: call from a background thread.
 */
object Offline {
    enum class Kind { APK, ZIP, IMAGE }

    data class Item(
        val name: String,
        val uri: Uri,
        val size: Long,
        val kind: Kind,
    )

    /** What the check found: [ok] files can be installed; [detail] says why, or what. */
    data class Checked(
        val item: Item,
        val file: File?,
        val ok: Boolean,
        val detail: String,
        val pkg: String = "",
        val zip: FlashZip? = null,
    )

    /** APKs, zips and images of the folder [tree] and of the folders right under it. */
    fun list(
        context: Context,
        tree: Uri,
    ): List<Item> {
        val out = mutableListOf<Item>()

        fun scan(
            docId: String,
            depth: Int,
        ) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val cols =
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                )
            runCatching {
                context.contentResolver.query(children, cols, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0)
                        val name = c.getString(1) ?: continue
                        if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                            if (depth < 1) scan(id, depth + 1)
                            continue
                        }
                        val kind =
                            when (name.substringAfterLast('.', "").lowercase()) {
                                "apk" -> Kind.APK
                                "zip" -> Kind.ZIP
                                "img" -> Kind.IMAGE
                                else -> continue
                            }
                        out += Item(name, DocumentsContract.buildDocumentUriUsingTree(tree, id), c.getLong(3), kind)
                    }
                }
            }
        }
        scan(DocumentsContract.getTreeDocumentId(tree), 0)
        return out.sortedWith(compareBy({ it.kind }, { it.name.lowercase() }))
    }

    /** Copies [item] here and checks it. */
    fun check(
        context: Context,
        item: Item,
    ): Checked {
        // The copy here, then the installed one (APK) or the one handed to TWRP (zip).
        Space.missingMb(context, item.size, item.size)?.let {
            return Checked(item, null, false, context.getString(R.string.no_space, it))
        }
        val dir = File(context.cacheDir, "offline").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, item.name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        try {
            val input = context.contentResolver.openInputStream(item.uri) ?: return Checked(item, null, false, "?")
            input.use { i -> file.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
        } catch (e: Exception) {
            file.delete()
            return Checked(item, null, false, e.message ?: e.javaClass.simpleName)
        }
        return when (item.kind) {
            Kind.APK -> checkApk(context, item, file)
            else -> checkZip(context, item, file)
        }
    }

    private fun checkApk(
        context: Context,
        item: Item,
        file: File,
    ): Checked {
        val info = Installer.archiveInfo(context, file) ?: return refuse(item, file, context.getString(R.string.offline_not_apk))
        val what = "${info.packageName} ${info.versionName ?: ""}".trim()
        return when (val c = Signatures.checkLocal(context, file, info.packageName)) {
            is Signatures.Check.Ok -> {
                val key = context.getString(if (c.librehu) R.string.offline_key_librehu else R.string.offline_key_installed)
                Checked(item, file, true, "$what · $key", pkg = info.packageName)
            }

            is Signatures.Check.Refused -> {
                refuse(item, file, "$what · ${context.getString(R.string.bad_signature, c.found)}")
            }

            Signatures.Check.Unchecked -> {
                refuse(item, file, what)
            }
        }
    }

    private fun checkZip(
        context: Context,
        item: Item,
        file: File,
    ): Checked {
        val index = Zips.load(context).getOrNull() ?: return refuse(item, file, context.getString(R.string.offline_no_index))
        val sum = Zips.sha256(file)
        val z =
            index.zips.firstOrNull {
                if (item.kind == Kind.IMAGE) it.imageSha256.equals(sum, ignoreCase = true) else it.sha256.equals(sum, ignoreCase = true)
            } ?: return refuse(item, file, context.getString(R.string.offline_unknown_zip))
        if (!z.compatible) return refuse(item, file, "${z.name} · device ${android.os.Build.DEVICE}")
        return Checked(item, file, true, "${z.name} ${z.version}".trim(), zip = z)
    }

    private fun refuse(
        item: Item,
        file: File,
        detail: String,
    ): Checked {
        file.delete()
        return Checked(item, null, false, detail)
    }

    /** Installs a file [check] accepted; the text of the outcome, and true when it went well. */
    fun install(
        context: Context,
        c: Checked,
    ): Pair<String, Boolean> {
        val file = c.file ?: return context.getString(R.string.cfg_failed, "?") to false
        try {
            return when (c.item.kind) {
                Kind.APK -> {
                    val s = OtaSettingsStore.value(context)
                    when (val r = Installer.install(context, file, c.pkg, s.method, s.compile)) {
                        InstallResult.Done -> context.getString(R.string.offline_installed) to true
                        InstallResult.Pending -> context.getString(R.string.offline_confirm) to true
                        is InstallResult.Failed -> context.getString(R.string.cfg_failed, r.message) to false
                        else -> context.getString(R.string.cfg_failed, r.toString()) to false
                    }.also { History.add(context, c.item.name, context.getString(R.string.offline_title), it.second, it.first) }
                }

                Kind.ZIP -> {
                    outcome(context, Zips.flashFile(context, c.zip!!, file), true)
                }

                Kind.IMAGE -> {
                    outcome(context, Zips.writeImageFile(context, c.zip!!, file), false)
                }
            }
        } finally {
            // An APK handed to Android's installer is read by it first (the session copied it already).
            file.delete()
        }
    }

    private fun outcome(
        context: Context,
        o: Configs.Outcome,
        zip: Boolean,
    ): Pair<String, Boolean> =
        when (o) {
            is Configs.Outcome.Done -> context.getString(if (zip) R.string.cfg_zip_ready else R.string.cfg_done, o.detail) to true
            is Configs.Outcome.Failed -> context.getString(R.string.cfg_failed, o.reason) to false
        }
}
