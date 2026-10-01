package com.svifi.vitalyspeak

import okhttp3.Request
import org.json.JSONObject

/**
 * Checks GitHub Releases for a newer APK. Every push to main is built by GitHub Actions
 * and published as release "build-<N>", where N is also the APK's versionCode.
 * Runs only from the settings screen, never during dictation.
 */
object UpdateChecker {
    const val REPO = "SviFi/VitalySpeak"
    private const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"

    data class Release(val versionCode: Long, val name: String, val apkUrl: String, val notes: String)

    /** "build-42" -> 42, "v1.2-17" -> 17, garbage -> null */
    fun versionFromTag(tag: String): Long? =
        Regex("(\\d+)$").find(tag.trim())?.groupValues?.get(1)?.toLongOrNull()

    fun parseRelease(json: String): Release? = try {
        val o = JSONObject(json)
        val tag = o.getString("tag_name")
        val assets = o.optJSONArray("assets")
        val apk = assets?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk") }
                ?.getString("browser_download_url")
        }
        val code = versionFromTag(tag)
        if (code == null || apk == null) null
        else Release(code, o.optString("name", tag), apk, o.optString("body", ""))
    } catch (_: Exception) { null }

    /** Blocking; call from a background thread. Returns a release only if it's newer. */
    fun newerThan(installedVersionCode: Long): Release? = try {
        val req = Request.Builder()
            .url(LATEST_URL)
            .header("Accept", "application/vnd.github+json")
            .build()
        Groq.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) null
            else parseRelease(r.body?.string() ?: "")?.takeIf { it.versionCode > installedVersionCode }
        }
    } catch (_: Exception) { null }
}
