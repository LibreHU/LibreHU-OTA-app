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
    /** SHA-256 of the APK (notes `sha256=`), checked after the download; empty for builds published before it. */
    val sha256: String = "",
) {
    /** For the list kept between starts (shown without network). */
    fun toJson(): JSONObject =
        JSONObject()
            .put("tag", tag)
            .put("versionCode", versionCode)
            .put("versionName", versionName)
            .put("apkUrl", apkUrl)
            .put("apkName", apkName)
            .put("size", size)
            .put("publishedAt", publishedAt)
            .put("summary", summary)
            .put("commit", commit)
            .put("sha256", sha256)

    companion object {
        fun fromJson(o: JSONObject): Release =
            Release(
                tag = o.optString("tag"),
                versionCode = o.optLong("versionCode"),
                versionName = o.optString("versionName"),
                apkUrl = o.optString("apkUrl"),
                apkName = o.optString("apkName"),
                size = o.optLong("size", -1),
                publishedAt = o.optString("publishedAt"),
                summary = o.optString("summary"),
                commit = o.optString("commit"),
                sha256 = o.optString("sha256"),
            )
    }
}

class NotFoundException(
    url: String,
) : IOException("HTTP 404 $url")

class HttpException(
    val code: Int,
    url: String,
) : IOException("HTTP $code $url")

class RateLimitException(
    val resetEpochS: Long,
) : IOException("GitHub API rate limit")

/**
 * Releases of the LibreHU apps. The API (no account: 60 requests per hour and per IP) is spared: the release of a
 * branch is read from its `release.txt` file (published next to the APK by the CI, a download that does not count
 * against the API limit), API answers are kept with their ETag (an unchanged answer comes back as 304, free with a
 * token), commit comparisons are kept, and nothing is asked to the API until the limit is reset.
 */
object GitHub {
    private const val API = "https://api.github.com"
    private const val WEB = "https://github.com"
    private const val TIMEOUT_MS = 15_000

    /** API answers by URL, with their ETag. */
    private val etags =
        object : LinkedHashMap<String, Pair<String, String>>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, String>>?) = size > 64
        }

    /** Commit comparisons (never change for two given commits). */
    private val compared = HashMap<String, List<String>>()

    /** Epoch second when the API accepts requests again (rate limit reached). */
    @Volatile
    private var limitedUntilS = 0L

    /** Release of [app] for [branch]: the rolling `latest-<branch>` prerelease, or for a [CatalogApp.tagged] app the newest one. */
    fun release(
        app: CatalogApp,
        branch: String,
    ): Release? {
        if (app.tagged) return releases(app, "").firstOrNull()
        releaseFile(app, "latest-${slug(branch)}")?.let { return it }
        val r =
            try {
                JSONObject(get("$API/repos/${app.repo}/releases/tags/latest-${slug(branch)}"))
            } catch (e: NotFoundException) {
                return null
            }
        return parse(r, app)
    }

    /**
     * Release from the `release.txt` of [tag] (`apk=`, `size=`, `date=`, then the notes), without the API. Null when
     * the release has no such file (built before it existed) or github.com refuses: the API is asked instead.
     */
    private fun releaseFile(
        app: CatalogApp,
        tag: String,
    ): Release? {
        val base = "$WEB/${app.repo}/releases/download/$tag"
        val text =
            try {
                get("$base/release.txt")
            } catch (e: NotFoundException) {
                return null
            } catch (e: HttpException) {
                return null
            }
        val notes = parseNotes(text)
        val apk = notes["apk"]?.takeIf { it.endsWith(".apk") && '/' !in it } ?: return null
        return Release(
            tag = tag,
            versionCode = notes["versionCode"]?.toLongOrNull() ?: 0,
            versionName = notes["versionName"] ?: tag,
            apkUrl = "$base/$apk",
            apkName = apk,
            size = notes["size"]?.toLongOrNull() ?: 0,
            publishedAt = notes["date"] ?: "",
            summary = notes[""] ?: "",
            commit = notes["commit"] ?: "",
            sha256 = notes["sha256"]?.takeIf { Regex("[0-9a-fA-F]{64}").matches(it) } ?: "",
        )
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
        val key = "${app.repo}/$base...$head"
        synchronized(compared) { compared[key] }?.let { return it }
        val o = JSONObject(get("$API/repos/${app.repo}/compare/$base...$head"))
        val commits = o.optJSONArray("commits") ?: return emptyList()
        return List(commits.length()) { i ->
            commits
                .getJSONObject(i)
                .getJSONObject("commit")
                .optString("message")
                .lineSequence()
                .first()
        }.filterNot { it.startsWith("Merge branch") }.reversed().also { synchronized(compared) { compared[key] = it } }
    }

    private fun releases(
        app: CatalogApp,
        prefix: String,
    ): List<Release> {
        val list = JSONArray(get("$API/repos/${app.repo}/releases?per_page=100"))
        return (0 until list.length())
            .map { list.getJSONObject(it) }
            .filter { !it.optBoolean("draft") && it.optString("tag_name").startsWith(prefix) }
            .filter { !app.tagged || !it.optBoolean("prerelease") }
            .mapNotNull { parse(it, app) }
            .sortedByDescending { it.versionCode }
    }

    private fun parse(
        r: JSONObject,
        app: CatalogApp,
    ): Release? {
        val assets = r.optJSONArray("assets") ?: return null
        val pattern = app.asset.takeIf { it.isNotBlank() }?.let { runCatching { Regex(it) }.getOrNull() ?: return null }
        for (j in 0 until assets.length()) {
            val a = assets.getJSONObject(j)
            val name = a.optString("name")
            if (!name.endsWith(".apk")) continue
            val match = pattern?.let { it.find(name) ?: continue }
            val notes = parseNotes(r.optString("body"))
            val tag = r.optString("tag_name")
            return Release(
                tag = tag,
                versionCode =
                    notes["versionCode"]?.toLongOrNull()
                        ?: match?.groupValues?.getOrNull(1)?.toLongOrNull()
                        ?: 0,
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
        val api = URL(url).host == "api.github.com"
        if (api && limitedUntilS > System.currentTimeMillis() / 1000) throw RateLimitException(limitedUntilS)
        val cached = if (api) synchronized(etags) { etags[url] } else null
        val c = open(url)
        cached?.let { c.setRequestProperty("If-None-Match", it.first) }
        try {
            val code = c.responseCode
            if ((code == 403 || code == 429) && c.getHeaderField("X-RateLimit-Remaining") == "0") {
                val reset = c.getHeaderField("X-RateLimit-Reset")?.toLongOrNull() ?: 0
                if (api) limitedUntilS = reset
                throw RateLimitException(reset)
            }
            if (code == 304 && cached != null) return cached.second
            if (code == 404) throw NotFoundException(url)
            if (code !in 200..299) throw HttpException(code, url)
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val etag = c.getHeaderField("ETag")
            if (api && etag != null) synchronized(etags) { etags[url] = etag to body }
            return body
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
