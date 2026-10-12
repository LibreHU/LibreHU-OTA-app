package org.librehu.ota.data

import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** APK published on GitHub for one app and branch. */
data class Release(
    val tag: String,
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val apkName: String,
    val size: Long,
    val publishedAt: String,
    /** First line after the version block of the notes: the last commit. */
    val summary: String,
)

class RateLimitException(
    val resetEpochS: Long,
) : IOException("GitHub API rate limit")

/** Releases through the public GitHub API (no account: 60 requests per hour and per IP). */
object GitHub {
    private const val API = "https://api.github.com"
    private const val TIMEOUT_MS = 15_000

    /**
     * Release of [app] for [branch]: the rolling `latest-<branch>` prerelease (notes `versionCode=…`), or for a
     * [CatalogApp.tagged] app the newest release carrying an APK.
     */
    fun release(
        app: CatalogApp,
        branch: String,
    ): Release? {
        val list = JSONArray(get("$API/repos/${app.repo}/releases?per_page=30"))
        val wanted = "latest-" + branch.replace('/', '-')
        for (i in 0 until list.length()) {
            val r = list.getJSONObject(i)
            val tag = r.optString("tag_name")
            if (r.optBoolean("draft")) continue
            if (!app.tagged && tag != wanted) continue
            val assets = r.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val a = assets.getJSONObject(j)
                val name = a.optString("name")
                if (!name.endsWith(".apk")) continue
                val notes = parseNotes(r.optString("body"))
                return Release(
                    tag = tag,
                    versionCode = notes["versionCode"]?.toLongOrNull() ?: 0,
                    versionName = notes["versionName"] ?: tag,
                    apkUrl = a.getString("browser_download_url"),
                    apkName = name,
                    size = a.optLong("size"),
                    publishedAt = a.optString("updated_at", r.optString("published_at")),
                    summary = notes[""] ?: "",
                )
            }
        }
        return null
    }

    /** `key=value` lines of the notes; the first other line under key "". */
    private fun parseNotes(body: String): Map<String, String> {
        val map = HashMap<String, String>()
        for (line in body.lines()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            val eq = t.indexOf('=')
            if (eq > 0 && t.substring(0, eq).all { it.isLetterOrDigit() }) {
                map[t.substring(0, eq)] = t.substring(eq + 1)
            } else if ("" !in map) {
                map[""] = t
            }
        }
        return map
    }

    fun get(url: String): String {
        val c = open(url)
        try {
            val code = c.responseCode
            if ((code == 403 || code == 429) && c.getHeaderField("X-RateLimit-Remaining") == "0") {
                throw RateLimitException(c.getHeaderField("X-RateLimit-Reset")?.toLongOrNull() ?: 0)
            }
            if (code !in 200..299) throw IOException("HTTP $code $url")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "LibreHU-OTA")
        }
}
