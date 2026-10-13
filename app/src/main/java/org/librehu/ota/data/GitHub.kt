package org.librehu.ota.data

import org.json.JSONArray
import org.json.JSONObject
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
    /** Commit the APK was built from (notes `commit=`). */
    val commit: String = "",
)

class NotFoundException(
    url: String,
) : IOException("HTTP 404 $url")

class RateLimitException(
    val resetEpochS: Long,
) : IOException("GitHub API rate limit")

/** Releases through the public GitHub API (no account: 60 requests per hour and per IP). */
object GitHub {
    private const val API = "https://api.github.com"
    private const val TIMEOUT_MS = 15_000

    /** Release of [app] for [branch]: the rolling `latest-<branch>` prerelease, or for a [CatalogApp.tagged] app the newest one. */
    fun release(
        app: CatalogApp,
        branch: String,
    ): Release? {
        if (app.tagged) return releases(app, "").firstOrNull()
        val r =
            try {
                JSONObject(get("$API/repos/${app.repo}/releases/tags/latest-${slug(branch)}"))
            } catch (e: NotFoundException) {
                return null
            }
        return parse(r)
    }

    /** Numbered builds of [branch] (`build-<branch>-<versionCode>`, the last ones kept by the CI), newest first. */
    fun builds(
        app: CatalogApp,
        branch: String,
    ): List<Release> = releases(app, if (app.tagged) "" else "build-${slug(branch)}-")

    /** Commit messages from [base] (excluded) to [head], newest first; empty when GitHub cannot compare them. */
    fun changes(
        app: CatalogApp,
        base: String,
        head: String,
    ): List<String> {
        val o = JSONObject(get("$API/repos/${app.repo}/compare/$base...$head"))
        val commits = o.optJSONArray("commits") ?: return emptyList()
        return List(commits.length()) { i ->
            commits
                .getJSONObject(i)
                .getJSONObject("commit")
                .optString("message")
                .lineSequence()
                .first()
        }.filterNot { it.startsWith("Merge branch") }.reversed()
    }

    private fun releases(
        app: CatalogApp,
        prefix: String,
    ): List<Release> {
        val list = JSONArray(get("$API/repos/${app.repo}/releases?per_page=100"))
        return (0 until list.length())
            .map { list.getJSONObject(it) }
            .filter { !it.optBoolean("draft") && it.optString("tag_name").startsWith(prefix) }
            .mapNotNull(::parse)
            .sortedByDescending { it.versionCode }
    }

    private fun parse(r: JSONObject): Release? {
        val assets = r.optJSONArray("assets") ?: return null
        for (j in 0 until assets.length()) {
            val a = assets.getJSONObject(j)
            val name = a.optString("name")
            if (!name.endsWith(".apk")) continue
            val notes = parseNotes(r.optString("body"))
            val tag = r.optString("tag_name")
            return Release(
                tag = tag,
                versionCode = notes["versionCode"]?.toLongOrNull() ?: 0,
                versionName = notes["versionName"] ?: tag,
                apkUrl = a.getString("browser_download_url"),
                apkName = name,
                size = a.optLong("size"),
                publishedAt = a.optString("updated_at", r.optString("published_at")),
                summary = notes[""] ?: "",
                commit = notes["commit"] ?: "",
            )
        }
        return null
    }

    private fun slug(branch: String) = branch.replace('/', '-')

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
            if (code == 404) throw NotFoundException(url)
            if (code !in 200..299) throw IOException("HTTP $code $url")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** Personal token of the settings (5000 requests per hour instead of 60), sent to the API only. */
    @Volatile
    var token: String = ""

    fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "LibreHU-OTA")
            if (token.isNotBlank() && URL(url).host == "api.github.com") setRequestProperty("Authorization", "Bearer ${token.trim()}")
        }
}
