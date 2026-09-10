package com.noop.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Update check against the project's PUBLIC releases API (GitHub): reads the latest version and
 * compares it to the installed one. Called both from the Settings "Check for updates" button and,
 * throttled, at launch by [com.noop.ui.UpdateGate] to prompt a one-tap in-app APK update. Nothing
 * about the user is sent; it just reads a version number and the release's APK asset URL.
 * (Android already holds INTERNET for the opt-in AI Coach, so this adds no new capability.)
 */
object UpdateCheck {

    private const val ENDPOINT = "https://api.github.com/repos/MatB57/noop/releases/latest"

    sealed interface Result {
        data class UpToDate(val version: String) : Result

        /**
         * A newer release is available. [url] is the release page (browser fallback); [apkUrl] is the
         * direct `.apk` asset for an in-app download+install, or null when the release has no APK
         * attached (then only the browser path is offered). [apkSize] is bytes, 0 if unknown.
         */
        data class Available(
            val version: String,
            val url: String,
            val notes: String,
            val apkUrl: String? = null,
            val apkSize: Long = 0L,
        ) : Result

        object Failed : Result
    }

    /** Fetch the latest release and classify it against [currentVersion]. Never throws — any error
     *  (offline, rate-limited, malformed) resolves to [Result.Failed] so the caller shows a calm
     *  "try again" rather than crashing. */
    suspend fun check(currentVersion: String): Result = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 12_000
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            try {
                if (conn.responseCode != 200) return@runCatching Result.Failed
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val latest = json.getString("tag_name").removePrefix("v")
                val url = json.getString("html_url")
                val notes = cleanNotes(json.optString("body", ""))
                if (!isNewer(latest, currentVersion)) return@runCatching Result.UpToDate(latest)

                // First `.apk` asset on the release → the in-app download target.
                var apkUrl: String? = null
                var apkSize = 0L
                json.optJSONArray("assets")?.let { assets ->
                    for (i in 0 until assets.length()) {
                        val a = assets.optJSONObject(i) ?: continue
                        if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                            apkUrl = a.optString("browser_download_url").ifBlank { null }
                            apkSize = a.optLong("size", 0L)
                            break
                        }
                    }
                }
                Result.Available(latest, url, notes, apkUrl, apkSize)
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(Result.Failed)
    }

    /**
     * True iff [latest] is a strictly newer version than [current]. Compares dot-separated numeric
     * segments left to right — so `1.40 > 1.39` and `1.9 < 1.10`, both of which a plain string compare
     * gets WRONG. Tolerant of a leading "v" and any non-numeric suffix (e.g. the demo flavour's
     * "1.39-demo", or build metadata). Pure + unit-tested.
     */
    fun isNewer(latest: String, current: String): Boolean {
        val a = segments(latest)
        val b = segments(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun segments(s: String): List<Int> =
        s.trim().removePrefix("v").removePrefix("V")
            .takeWhile { it.isDigit() || it == '.' }   // stop at "-demo" / build metadata
            .split(".")
            .mapNotNull { it.toIntOrNull() }

    /** Turn a GitHub release body into a short, readable "what's new" for an inline preview: drop the
     *  "Downloads"/footer boilerplate, strip the heaviest markdown markers, and cap the length. */
    fun cleanNotes(body: String): String {
        var s = body.substringBefore("Downloads")
        for (marker in listOf("**", "## ", "# ")) s = s.replace(marker, "")
        s = s.trim()
        return if (s.length > 700) s.take(700).trim() + "…" else s
    }
}
