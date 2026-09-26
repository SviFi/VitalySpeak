package com.kafkasl.phonewhisper

import android.content.SharedPreferences
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Discovers which Groq models exist right now. Runs only from the settings screen
 * (on open, at most once a day, or on "Check now") — never during dictation.
 *
 * Two sources:
 *  1. Groq GET /models — the ground truth of what your key can call (free request).
 *  2. recommended.json in the GitHub repo — a hand-curated "use this" pick that can be
 *     changed without rebuilding the app. Optional; ignored if unreachable.
 */
object ModelChecker {
    const val RECOMMENDED_URL = "https://raw.githubusercontent.com/${UpdateChecker.REPO}/main/recommended.json"
    const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    private const val KEY_LAST_CHECK = "models_last_check"
    private const val KEY_KNOWN = "models_known"
    private const val KEY_STT_LIST = "models_stt"
    private const val KEY_CHAT_LIST = "models_chat"
    private const val KEY_NEW = "models_new"
    private const val KEY_REC_STT = "models_rec_stt"
    private const val KEY_REC_LLM = "models_rec_llm"
    private const val KEY_REC_NOTE = "models_rec_note"

    data class ModelInfo(val id: String, val created: Long, val ownedBy: String)

    data class Recommendation(val stt: String?, val llm: String?, val note: String?)

    data class Report(
        val stt: List<String>,
        val chat: List<String>,
        val newModels: List<String>,
        val recommendation: Recommendation?,
        val checkedAt: Long,
        val error: String? = null
    )

    // ---------- pure logic (unit-tested) ----------

    fun parseModels(json: String): List<ModelInfo> {
        val data = JSONObject(json).getJSONArray("data")
        return (0 until data.length()).map { data.getJSONObject(it) }
            // Groq marks retiring models with "active": false
            .filter { it.optBoolean("active", true) }
            .map { ModelInfo(it.getString("id"), it.optLong("created", 0), it.optString("owned_by", "")) }
    }

    fun isStt(id: String) = id.contains("whisper", ignoreCase = true)

    private val NON_CHAT = listOf("whisper", "tts", "orpheus", "guard", "playai", "distil", "embed")
    fun isChat(id: String) = NON_CHAT.none { id.contains(it, ignoreCase = true) }

    /** Models present now that weren't there last time. Empty on the very first check. */
    fun newSince(known: Set<String>, now: List<String>): List<String> =
        if (known.isEmpty()) emptyList() else now.filter { it !in known }

    fun parseRecommendation(json: String): Recommendation? = try {
        val o = JSONObject(json)
        Recommendation(
            o.optString("stt").ifBlank { null },
            o.optString("llm").ifBlank { null },
            o.optString("note").ifBlank { null }
        )
    } catch (_: Exception) { null }

    // ---------- cached state ----------

    fun shouldAutoCheck(p: SharedPreferences, now: Long = System.currentTimeMillis()) =
        now - p.getLong(KEY_LAST_CHECK, 0) > CHECK_INTERVAL_MS

    fun cached(p: SharedPreferences): Report = Report(
        stt = p.getString(KEY_STT_LIST, null).toList().ifEmpty { listOf(Groq.DEFAULT_STT_MODEL, "whisper-large-v3-turbo") },
        chat = p.getString(KEY_CHAT_LIST, null).toList().ifEmpty { listOf(Groq.DEFAULT_LLM_MODEL) },
        newModels = p.getString(KEY_NEW, null).toList(),
        recommendation = p.getString(KEY_REC_STT, null)?.let {
            Recommendation(it.ifBlank { null }, p.getString(KEY_REC_LLM, "")!!.ifBlank { null },
                p.getString(KEY_REC_NOTE, "")!!.ifBlank { null })
        },
        checkedAt = p.getLong(KEY_LAST_CHECK, 0)
    )

    /** User has seen the "new" list — clear the badge. */
    fun acknowledgeNew(p: SharedPreferences) { p.edit().remove(KEY_NEW).apply() }

    // ---------- network (call from a background thread) ----------

    fun check(p: SharedPreferences, apiKey: String): Report {
        val now = System.currentTimeMillis()
        val models = try {
            val req = Request.Builder()
                .url("${Groq.BASE_URL}/models")
                .header("Authorization", "Bearer $apiKey")
                .build()
            Groq.client.newCall(req).execute().use { r ->
                val body = r.body?.string() ?: ""
                if (!r.isSuccessful) {
                    val msg = try { JSONObject(body).getJSONObject("error").getString("message") }
                              catch (_: Exception) { "HTTP ${r.code}" }
                    return cached(p).copy(error = msg)
                }
                parseModels(body)
            }
        } catch (e: Exception) {
            return cached(p).copy(error = e.message ?: "Network error")
        }

        val ids = models.sortedByDescending { it.created }.map { it.id }
        val stt = ids.filter(::isStt)
        val chat = ids.filter(::isChat)

        val known = p.getStringSet(KEY_KNOWN, emptySet()) ?: emptySet()
        val fresh = newSince(known, stt + chat)
        val stillNew = (p.getString(KEY_NEW, null).toList() + fresh).distinct().filter { it in ids }

        val rec = try {
            Groq.client.newCall(Request.Builder().url(RECOMMENDED_URL).build()).execute().use { r ->
                if (r.isSuccessful) parseRecommendation(r.body?.string() ?: "") else null
            }
        } catch (_: Exception) { null }

        p.edit()
            .putLong(KEY_LAST_CHECK, now)
            .putStringSet(KEY_KNOWN, (stt + chat).toSet())
            .putString(KEY_STT_LIST, JSONArray(stt).toString())
            .putString(KEY_CHAT_LIST, JSONArray(chat).toString())
            .putString(KEY_NEW, JSONArray(stillNew).toString())
            .apply {
                if (rec != null) {
                    putString(KEY_REC_STT, rec.stt ?: "")
                    putString(KEY_REC_LLM, rec.llm ?: "")
                    putString(KEY_REC_NOTE, rec.note ?: "")
                }
            }
            .apply()

        return Report(stt, chat, stillNew, rec ?: cached(p).recommendation, now)
    }

    private fun String?.toList(): List<String> = try {
        if (this == null) emptyList() else JSONArray(this).let { a -> (0 until a.length()).map { a.getString(it) } }
    } catch (_: Exception) { emptyList() }
}
