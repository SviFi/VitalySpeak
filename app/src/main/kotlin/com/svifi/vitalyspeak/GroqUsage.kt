package com.svifi.vitalyspeak

import android.content.SharedPreferences
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONObject

/**
 * What we can know about Groq limits. Groq has no API for plan name, credits or account
 * usage, but every response carries rate-limit headers for that model:
 *   x-ratelimit-limit/remaining-requests   → requests per DAY
 *   x-ratelimit-limit/remaining-tokens     → tokens per MINUTE (chat models)
 * Audio seconds (Whisper's main free-tier limit) aren't reported, so we count them
 * ourselves per day, rounding each request up to Groq's 10 s billing minimum.
 */
object GroqUsage {
    /** Free-tier daily audio allowance per Whisper model (Groq docs, Sep 2026). */
    const val FREE_AUDIO_SEC_PER_DAY = 28_800L
    const val WARN_AT = 0.8

    data class ModelLimits(
        val model: String,
        val limitRequests: Long?, val remainingRequests: Long?, val resetRequests: String?,
        val limitTokens: Long?, val remainingTokens: Long?,
        val updatedAt: Long
    ) {
        val requestsUsedFraction: Double?
            get() = if (limitRequests != null && remainingRequests != null && limitRequests > 0)
                (limitRequests - remainingRequests).toDouble() / limitRequests else null
    }

    private var prefs: SharedPreferences? = null
    /** Called (on an OkHttp thread) once per day per metric when it crosses [WARN_AT]. */
    /** (audio?, percent used, model): shown by the service in the user's language. */
    @Volatile var onWarning: ((Boolean, Int, String) -> Unit)? = null

    fun init(p: SharedPreferences) { prefs = p }

    private const val KEY = "groq_usage"
    private const val KEY_AUDIO = "groq_audio_day"
    private const val KEY_WARNED = "groq_warned"

    private fun load(): JSONObject = try { JSONObject(prefs?.getString(KEY, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }

    fun all(): List<ModelLimits> {
        val o = load()
        return o.keys().asSequence().map { m ->
            val j = o.getJSONObject(m)
            ModelLimits(m,
                j.optLongOrNull("lr"), j.optLongOrNull("rr"), j.optString("resr").ifBlank { null },
                j.optLongOrNull("lt"), j.optLongOrNull("rt"), j.optLong("at"))
        }.sortedBy { it.model }.toList()
    }

    private fun JSONObject.optLongOrNull(k: String): Long? = if (has(k)) optLong(k) else null

    @Synchronized
    fun record(model: String, r: Response) {
        val p = prefs ?: return
        val lr = r.header("x-ratelimit-limit-requests")?.toLongOrNull()
        val rr = r.header("x-ratelimit-remaining-requests")?.toLongOrNull()
        val lt = r.header("x-ratelimit-limit-tokens")?.toLongOrNull()
        val rt = r.header("x-ratelimit-remaining-tokens")?.toLongOrNull()
        if (lr == null && lt == null) return
        val o = load()
        o.put(model, JSONObject().apply {
            lr?.let { put("lr", it) }; rr?.let { put("rr", it) }
            r.header("x-ratelimit-reset-requests")?.let { put("resr", it) }
            lt?.let { put("lt", it) }; rt?.let { put("rt", it) }
            put("at", System.currentTimeMillis())
        })
        p.edit().putString(KEY, o.toString()).apply()
        if (lr != null && rr != null && lr > 0 && (lr - rr).toDouble() / lr >= WARN_AT) {
            warnOnce("req:$model", false, ((lr - rr) * 100 / lr).toInt(), model)
        }
    }

    private fun today() = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    /** Audio seconds sent today per model: {day, model -> seconds}. */
    @Synchronized
    fun addAudio(model: String, seconds: Double) {
        val p = prefs ?: return
        val billed = maxOf(10.0, seconds)
        val o = audioToday()
        o.put(model, o.optDouble(model, 0.0) + billed)
        p.edit().putString(KEY_AUDIO, JSONObject().put("day", today()).put("m", o).toString()).apply()
        val used = o.optDouble(model, 0.0)
        if (used / FREE_AUDIO_SEC_PER_DAY >= WARN_AT) {
            warnOnce("audio:$model", true, (used * 100 / FREE_AUDIO_SEC_PER_DAY).toInt(), model)
        }
    }

    fun audioToday(): JSONObject {
        val raw = try { JSONObject(prefs?.getString(KEY_AUDIO, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        return if (raw.optString("day") == today()) raw.optJSONObject("m") ?: JSONObject() else JSONObject()
    }

    private fun warnOnce(key: String, audio: Boolean, pct: Int, model: String) {
        val p = prefs ?: return
        val stamp = "$key@${today()}"
        val warned = p.getStringSet(KEY_WARNED, emptySet()) ?: emptySet()
        if (stamp in warned) return
        p.edit().putStringSet(KEY_WARNED, warned.filter { it.endsWith(today()) }.toSet() + stamp).apply()
        onWarning?.invoke(audio, pct, model)
    }

    /** OkHttp interceptor: records limits from every api.groq.com response tagged with a model. */
    val interceptor = Interceptor { chain ->
        val req = chain.request()
        val resp = chain.proceed(req)
        val model = req.tag(String::class.java)
        if (model != null && req.url.host == "api.groq.com") {
            try { record(model, resp) } catch (_: Exception) {}
        }
        resp
    }
}
