package org.librehu.ota.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/** One install, config or flash done by the app. */
data class HistoryEntry(
    val time: Long,
    val name: String,
    /** Version before → after (or the file / zip). */
    val detail: String,
    val ok: Boolean,
    val result: String,
)

/** Last operations (a JSON line each, at most [MAX] kept), for the settings screen. */
object History {
    private const val MAX = 200
    private val state = MutableStateFlow<List<HistoryEntry>>(emptyList())
    private var loaded = false

    fun get(context: Context): StateFlow<List<HistoryEntry>> {
        load(context)
        return state.asStateFlow()
    }

    @Synchronized
    fun add(
        context: Context,
        name: String,
        detail: String,
        ok: Boolean,
        result: String,
    ) {
        load(context)
        val e = HistoryEntry(System.currentTimeMillis(), name, detail, ok, result)
        val list = (listOf(e) + state.value).take(MAX)
        state.value = list
        runCatching {
            file(context).writeText(
                list.reversed().joinToString("\n") {
                    JSONObject()
                        .put("t", it.time)
                        .put("n", it.name)
                        .put("d", it.detail)
                        .put("ok", it.ok)
                        .put("r", it.result)
                        .toString()
                },
            )
        }
    }

    @Synchronized
    fun clear(context: Context) {
        state.value = emptyList()
        file(context).delete()
    }

    @Synchronized
    private fun load(context: Context) {
        if (loaded) return
        loaded = true
        state.value =
            runCatching {
                file(context)
                    .readLines()
                    .mapNotNull { line ->
                        runCatching {
                            val o = JSONObject(line)
                            HistoryEntry(o.getLong("t"), o.getString("n"), o.optString("d"), o.optBoolean("ok"), o.optString("r"))
                        }.getOrNull()
                    }.reversed()
            }.getOrDefault(emptyList())
    }

    private fun file(context: Context) = File(context.filesDir, "history.jsonl")
}
