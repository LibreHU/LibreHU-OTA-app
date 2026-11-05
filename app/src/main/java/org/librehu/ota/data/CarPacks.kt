package org.librehu.ota.data

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.coroutines.resume

/**
 * Packs of car profiles by make (github.com/LibreHU/LibreHU-car-profiles, `dist/`), installed into LibreHU service:
 * the index lists each make's revision and the engine it needs; the service says which revisions it has and its
 * engine version; a pack is downloaded, checked (size, SHA-256 of the index) and handed to the service, which reads
 * it again all or nothing. Fixing a Renault updates the Renault pack only.
 */
object CarPacks {
    const val INDEX_URL = "https://raw.githubusercontent.com/LibreHU/LibreHU-car-profiles/main/dist/index.json"
    private const val BASE_URL = "https://raw.githubusercontent.com/LibreHU/LibreHU-car-profiles/main/dist/"
    private const val SERVICE = "org.librehu.service"
    private const val ACTION_QUERY = "org.librehu.service.QUERY_CAR_PACKS"
    private const val ACTION_INSTALL = "org.librehu.service.INSTALL_CAR_PACK"
    private const val ACTION_REMOVE = "org.librehu.service.REMOVE_CAR_PACK"

    /** Largest pack handed over in a broadcast (Binder transactions stop at 1 MB). */
    private const val MAX_BYTES = 512 * 1024

    data class Pack(
        val id: String,
        val name: String,
        val revision: Int,
        val engine: String,
        val makes: List<String>,
        val cars: Int,
        val file: String,
        val size: Int,
        val sha256: String,
    )

    /** What LibreHU service has: its engine version, and the revision of each installed pack (null: no service). */
    data class Installed(
        val engine: String,
        val packs: Map<String, Int>,
        /** SHA-256 of each installed pack file (services from v176). */
        val sha256: Map<String, String> = emptyMap(),
    ) {
        /** True when the pack [p] is installed as published: same SHA-256 (else same revision, older services). */
        fun upToDate(p: Pack): Boolean = sha256[p.id]?.equals(p.sha256, ignoreCase = true) ?: ((packs[p.id] ?: -1) >= p.revision)
    }

    fun parseIndex(text: String): List<Pack> {
        val root = JSONObject(text)
        if (root.optString("format") != "librehu-car-packs") throw IllegalStateException("not a car pack index")
        val arr = root.getJSONArray("packs")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val makes = o.optJSONArray("makes")
            Pack(
                id = o.getString("id"),
                name = o.optString("name", o.getString("id")),
                revision = o.optInt("revision"),
                engine = o.optString("engine", ">=1.0"),
                makes = (0 until (makes?.length() ?: 0)).map { makes!!.getString(it) },
                cars = o.optInt("cars"),
                file = o.getString("file"),
                size = o.optInt("size"),
                sha256 = o.getString("sha256"),
            )
        }
    }

    /** The index (network). */
    fun index(): List<Pack> = parseIndex(GitHub.get(INDEX_URL))

    /** True when the engine [version] ("2.5.0") meets [requirement] (">=2.1", "^2.1", "2.1"), as LibreHU service reads it. */
    fun satisfies(
        requirement: String,
        version: String,
    ): Boolean {
        fun parse(t: String) = Regex("(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?").matchEntire(t.trim())?.groupValues?.drop(1)?.map { it.ifEmpty { "0" }.toInt() }
        val v = parse(version) ?: return false
        val r = requirement.trim()
        val (req, sameMajor) =
            when {
                r.startsWith(">=") -> parse(r.substring(2)) to false
                r.startsWith("^") -> parse(r.substring(1)) to true
                else -> parse(r) to true
            }
        if (req == null) return false
        val cmp = (0..2).map { v[it].compareTo(req[it]) }.firstOrNull { it != 0 } ?: 0
        return cmp >= 0 && (!sameMajor || v[0] == req[0])
    }

    /** Asks LibreHU service which packs it has; null when it does not answer (not installed, too old). */
    suspend fun installed(context: Context): Installed? =
        ordered(context, Intent(ACTION_QUERY))?.let { (code, data) ->
            if (code != Activity.RESULT_OK || data == null) return@let null
            val o = JSONObject(data)
            val p = o.optJSONObject("packs")
            val h = o.optJSONObject("sha256")
            Installed(
                o.optString("engine"),
                p?.keys()?.asSequence()?.associateWith { p.getInt(it) }.orEmpty(),
                h?.keys()?.asSequence()?.associateWith { h.getString(it) }.orEmpty(),
            )
        }

    /** Downloads [p], checks it and has LibreHU service install it; returns null when done, else the reason. */
    suspend fun install(
        context: Context,
        p: Pack,
    ): String? {
        val bytes =
            try {
                download(BASE_URL + p.file)
            } catch (e: Exception) {
                return e.message ?: e.javaClass.simpleName
            }
        if (bytes.size > MAX_BYTES) return "pack too large (${bytes.size / 1024} kB)"
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (!sha.equals(p.sha256, ignoreCase = true)) return "download damaged (SHA-256 differs)"
        val answer =
            ordered(context, Intent(ACTION_INSTALL).putExtra("pack", bytes).putExtra("sha256", p.sha256))
                ?: return "LibreHU service did not answer (installed? up to date?)"
        return if (answer.first == Activity.RESULT_OK) null else answer.second ?: "refused"
    }

    /** Has LibreHU service remove the pack [id] (its make's built-in profiles come back). */
    suspend fun remove(
        context: Context,
        id: String,
    ): Boolean = ordered(context, Intent(ACTION_REMOVE).putExtra("id", id))?.first == Activity.RESULT_OK

    private fun download(url: String): ByteArray {
        val c = GitHub.open(url)
        try {
            if (c.responseCode !in 200..299) throw HttpException(c.responseCode, url)
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    /** An ordered broadcast to LibreHU service and its result (code, data), or null without an answer in 10 s. */
    private suspend fun ordered(
        context: Context,
        intent: Intent,
    ): Pair<Int, String?>? =
        withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine { cont ->
                val result =
                    object : BroadcastReceiver() {
                        override fun onReceive(
                            c: Context,
                            i: Intent,
                        ) {
                            if (cont.isActive) cont.resume(resultCode to resultData)
                        }
                    }
                // Initial code: still 0 (RESULT_CANCELED) if no receiver of the service ran.
                context.sendOrderedBroadcast(intent.setPackage(SERVICE), null, result, Handler(Looper.getMainLooper()), Int.MIN_VALUE, null, null)
            }
        }?.takeIf { it.first != Int.MIN_VALUE }
}
