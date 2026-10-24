package org.librehu.ota.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject

/**
 * Settings pages of this app for LibreHU's phone app, read and changed through LibreHU-service's bridge, which asks
 * `<package>.bridge` (format of LibreHU-service's docs/bridge.md): `call("pages")` → Bundle `json` = the pages and
 * their items, `call("set", "<page>/<item>", {value})` → the item as it is now, `call("action", "<page>/<item>")`.
 * Signature permission `org.librehu.permission.BRIDGE`, checked here: Android does not check a provider's permission
 * for `call()` (ContentProvider.Transport.call), only for query / insert / update / delete.
 */
abstract class BridgeKitProvider : ContentProvider() {
    /** The pages, built afresh at each call (their items read the settings as they are now). */
    abstract fun pages(context: Context): List<KitPage>

    override fun onCreate() = true

    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle? {
        val ctx = context ?: return null
        if (ctx.checkCallingPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) throw SecurityException("needs $PERMISSION")
        val app = ctx.applicationContext
        return when (method) {
            "pages" -> {
                Bundle().apply { putString("json", JSONArray(pages(app).map { it.toJson() }).toString()) }
            }

            "set", "action" -> {
                val pageId = arg?.substringBefore('/') ?: return null
                val itemId = arg.substringAfter('/')
                val item = pages(app).firstOrNull { it.id == pageId }?.items?.firstOrNull { it.id == itemId } ?: return null
                if (method == "action") item.run?.invoke() else item.set?.invoke(extras?.getString("value"))
                // Read again: the item as the app now has it.
                val now = pages(app).firstOrNull { it.id == pageId }?.items?.firstOrNull { it.id == itemId } ?: item
                Bundle().apply { putString("json", now.toJson().toString()) }
            }

            else -> {
                null
            }
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = 0

    companion object {
        const val PERMISSION = "org.librehu.permission.BRIDGE"
    }
}

/** One element of a page; [set] gets the value as text (`"true"`, a number, an option's `v`). */
class KitItem(
    val id: String,
    val type: String,
    val label: String,
    val hint: String = "",
    val value: Any? = null,
    val min: Int = 0,
    val max: Int = 0,
    val step: Int = 1,
    val unit: String = "",
    val display: String? = null,
    val options: List<Pair<String, String>> = emptyList(),
    val confirm: Boolean = false,
    val set: ((String?) -> Unit)? = null,
    val run: (() -> Unit)? = null,
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("type", type)
            .put("label", label)
            .apply {
                if (hint.isNotEmpty()) put("hint", hint)
                if (type == "slider") put("min", min).put("max", max).put("step", step)
                if (type == "text") put("max", max)
                if (unit.isNotEmpty()) put("unit", unit)
                if (options.isNotEmpty()) put("options", JSONArray(options.map { JSONObject().put("v", it.first).put("l", it.second) }))
                if (confirm) put("confirm", true)
                if (value != null) put("value", value)
                if (display != null) put("display", display)
            }
}

class KitPage(
    val id: String,
    val title: String,
    val icon: String,
    val items: List<KitItem>,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("icon", icon).put("items", JSONArray(items.map { it.toJson() }))
}

/** Builds a page: `page("id", "Title", "icon") { switch(…); slider(…) }`. */
class KitBuilder(
    private val context: Context,
) {
    val items = ArrayList<KitItem>()
    private var sections = 0

    fun s(res: Int): String = context.getString(res)

    fun section(label: String) {
        items += KitItem("s${++sections}", "section", label)
    }

    fun info(
        id: String,
        label: String,
        value: String,
    ) {
        items += KitItem(id, "info", label, value = value)
    }

    fun switch(
        id: String,
        label: String,
        value: Boolean,
        hint: String = "",
        set: (Boolean) -> Unit,
    ) {
        items += KitItem(id, "switch", label, hint, value, set = { set(it == "true") })
    }

    fun slider(
        id: String,
        label: String,
        value: Int,
        min: Int,
        max: Int,
        step: Int = 1,
        unit: String = "",
        hint: String = "",
        display: ((Int) -> String)? = null,
        set: (Int) -> Unit,
    ) {
        items +=
            KitItem(id, "slider", label, hint, value, min, max, step, unit, display?.invoke(value), set = { v ->
                v?.toDoubleOrNull()?.let { set(it.toInt().coerceIn(min, max)) }
            })
    }

    fun <T> choice(
        id: String,
        label: String,
        value: T,
        options: List<Pair<T, String>>,
        hint: String = "",
        set: (T) -> Unit,
    ) {
        val keys = options.map { it.first.toString() to it.second }
        items +=
            KitItem(id, "choice", label, hint, value.toString(), options = keys, set = { v ->
                options.firstOrNull { it.first.toString() == v }?.let { set(it.first) }
            })
    }

    /** One of an enum's values, labelled by [label] (its name otherwise). */
    inline fun <reified E : Enum<E>> pick(
        id: String,
        title: String,
        value: E,
        hint: String = "",
        noinline label: (E) -> String = { it.name },
        noinline set: (E) -> Unit,
    ) = choice(id, title, value, enumValues<E>().map { it to label(it) }, hint, set)

    fun text(
        id: String,
        label: String,
        value: String,
        max: Int = 64,
        hint: String = "",
        set: (String) -> Unit,
    ) {
        items += KitItem(id, "text", label, hint, value, max = max, set = { set((it ?: "").take(max)) })
    }

    fun button(
        id: String,
        label: String,
        confirm: Boolean = false,
        hint: String = "",
        run: () -> Unit,
    ) {
        items += KitItem(id, "button", label, hint, confirm = confirm, run = run)
    }
}

fun page(
    context: Context,
    id: String,
    title: String,
    icon: String,
    build: KitBuilder.() -> Unit,
): KitPage = KitPage(id, title, icon, KitBuilder(context).apply(build).items)
