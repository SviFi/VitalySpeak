package com.kafkasl.phonewhisper

import android.content.SharedPreferences
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Discovers which Groq models exist right now. Runs only from the settings screen
 * (on open, at most once a day, or on "Check now") — never during dictation.
 *
 * Sources:
 *  1. Groq GET /models — what your key can call, with release ("created") dates.
 *  2. Groq docs (models.md) — which models are Production (stable) vs Preview.
 *     If unreachable, a name heuristic (preview/beta/…) is used instead.
 *  3. recommended.json in the GitHub repo — hand-curated pick, changeable without a rebuild.
 */
object ModelChecker {
    const val RECOMMENDED_URL = "https://raw.githubusercontent.com/${UpdateChecker.REPO}/main/recommended.json"
    const val GROQ_DOCS_URL = "https://console.groq.com/docs/models.md"
    const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    private const val KEY_LAST_CHECK = "models_last_check"
    private const val KEY_KNOWN = "models_known"
    private const val KEY_STT_LIST = "models_stt"
    private const val KEY_CHAT_LIST = "models_chat"
    private const val KEY_NEW = "models_new"
    private const val KEY_CREATED = "models_created"
    private const val KEY_STABLE = "models_stable"
    private const val KEY_IGNORED = "models_ignored"
    private const val KEY_REC_STT = "models_rec_stt"
    private const val KEY_REC_LLM = "models_rec_llm"
    private const val KEY_REC_NOTE = "models_rec_note"

    /**
     * Models already judged when this build was made, so they aren't pushed as "upgrades"
     * just because they were released after the default. They stay selectable in the picker.
     *  - whisper-large-v3-turbo: faster but less accurate, notably for Russian
     *  - gpt-oss-20b: smaller than the default cleanup model
     *  - allam-2-7b, llama-3.1-8b-instant: small models
     */
    val ALREADY_EVALUATED = setOf("whisper-large-v3-turbo", "openai/gpt-oss-20b", "allam-2-7b", "llama-3.1-8b-instant")

    data class ModelInfo(val id: String, val created: Long, val ownedBy: String)

    data class Recommendation(val stt: String?, val llm: String?, val note: String?)

    data class Report(
        val stt: List<String>,
        val chat: List<String>,
        val newModels: List<String>,
        val recommendation: Recommendation?,
        val checkedAt: Long,
        /** id -> release time (epoch seconds), from Groq /models */
        val created: Map<String, Long> = emptyMap(),
        /** Production model ids from Groq docs; empty = unknown, fall back to name heuristic */
        val stable: Set<String> = emptySet(),
        val ignored: Set<String> = emptySet(),
        val error: String? = null
    ) {
        fun isStable(id: String) = ModelChecker.isStable(id, stable)
    }

    /** A newer stable model than the one in use. */
    data class Upgrade(val from: String, val to: String, val sameFamily: Boolean, val others: List<String>)

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

    /**
     * Model ids listed under "## … Production Models" in Groq's models.md.
     * Rows look like: | [![logo](…)Name](/docs/model/openai/gpt-oss-120b)openai/gpt-oss-120b | …
     * so the id is taken from the /docs/model/ link, with `backticked` ids as a fallback.
     */
    fun parseProductionIds(markdown: String): Set<String> {
        val ids = mutableSetOf<String>()
        var inProduction = false
        val link = Regex("\\(/docs/model/([^)\\s]+)\\)")
        val tick = Regex("^\\|\\s*`([^`]+)`")
        for (line in markdown.lineSequence()) {
            if (line.startsWith("## ")) {
                inProduction = line.contains("Production Models", ignoreCase = true)
                continue
            }
            if (!inProduction || !line.trim().startsWith("|")) continue
            val firstCell = line.trim().removePrefix("|").substringBefore(" |")
            // Enterprise-only models aren't usable with a normal key; don't call them stable picks.
            if (firstCell.contains("Enterprise")) continue
            (link.find(firstCell) ?: tick.find(line.trim()))?.let { ids += it.groupValues[1].trim() }
        }
        return ids
    }

    private val UNSTABLE_WORDS = listOf("preview", "beta", "alpha", "experimental", "-exp", "-rc", "test")

    fun isStable(id: String, production: Set<String>): Boolean =
        if (production.isNotEmpty()) id in production
        else UNSTABLE_WORDS.none { id.contains(it, ignoreCase = true) }

    /** "llama-3.3-70b-versatile" -> "llama-#-#b-versatile"; version numbers become "#". */
    fun family(id: String) = id.lowercase().replace(Regex("\\d+(\\.\\d+)*"), "#")

    /** All numbers in the id, for comparing versions within a family: 3.3/70 -> [3, 3, 70]. */
    fun versionOf(id: String): List<Int> = Regex("\\d+").findAll(id).map { it.value.toInt() }.toList()

    fun compareVersions(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val c = (a.getOrElse(i) { 0 }).compareTo(b.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    /**
     * Finds a newer stable model than [current] among [candidates].
     * - Same family with a higher version number (whisper-large-v3 -> v4) is a true upgrade.
     * - Otherwise, other stable models released after [current] are listed in [Upgrade.others]
     *   but not pushed as the upgrade, because newer is not always better (e.g. a smaller model).
     */
    fun findUpgrade(current: String, candidates: List<String>, r: Report): Upgrade? {
        val curCreated = r.created[current] ?: 0L
        val pool = candidates.filter { it != current && it !in r.ignored && r.isStable(it) }
        val sameFamily = pool
            .filter { family(it) == family(current) && compareVersions(versionOf(it), versionOf(current)) > 0 }
            .sortedWith { a, b -> compareVersions(versionOf(b), versionOf(a)) }
        val newer = pool
            .filter { curCreated > 0 && (r.created[it] ?: 0L) > curCreated }
            .sortedByDescending { r.created[it] ?: 0L }
        return when {
            sameFamily.isNotEmpty() ->
                Upgrade(current, sameFamily.first(), true, (newer - sameFamily.first()).filter { it !in sameFamily })
            newer.isNotEmpty() -> Upgrade(current, newer.first(), false, newer.drop(1))
            else -> null
        }
    }

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
        stt = p.getString(KEY_STT_LIST, null).parseList().ifEmpty { listOf(Groq.DEFAULT_STT_MODEL, "whisper-large-v3-turbo") },
        chat = p.getString(KEY_CHAT_LIST, null).parseList().ifEmpty { listOf(Groq.DEFAULT_LLM_MODEL) },
        newModels = p.getString(KEY_NEW, null).parseList(),
        recommendation = p.getString(KEY_REC_STT, null)?.let {
            Recommendation(it.ifBlank { null }, p.getString(KEY_REC_LLM, "")!!.ifBlank { null },
                p.getString(KEY_REC_NOTE, "")!!.ifBlank { null })
        },
        checkedAt = p.getLong(KEY_LAST_CHECK, 0),
        created = p.getString(KEY_CREATED, null).parseLongMap(),
        stable = p.getString(KEY_STABLE, null).parseList().toSet(),
        ignored = (p.getStringSet(KEY_IGNORED, emptySet()) ?: emptySet()) + ALREADY_EVALUATED
    )

    /** User has seen the "new" list — clear the badge. */
    fun acknowledgeNew(p: SharedPreferences) { p.edit().remove(KEY_NEW).apply() }

    /** "Don't suggest this model again." */
    fun ignore(p: SharedPreferences, id: String) {
        val cur = p.getStringSet(KEY_IGNORED, emptySet())?.toMutableSet() ?: mutableSetOf()
        cur += id
        p.edit().putStringSet(KEY_IGNORED, cur).apply()
    }

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
        val created = models.associate { it.id to it.created }

        val known = p.getStringSet(KEY_KNOWN, emptySet()) ?: emptySet()
        val fresh = newSince(known, stt + chat)
        val stillNew = (p.getString(KEY_NEW, null).parseList() + fresh).distinct().filter { it in ids }

        val rec = fetchText(RECOMMENDED_URL)?.let(::parseRecommendation)
        // Keep the previous stable list if the docs can't be fetched this time.
        val stable = fetchText(GROQ_DOCS_URL)?.let(::parseProductionIds)?.takeIf { it.isNotEmpty() }
            ?: p.getString(KEY_STABLE, null).parseList().toSet()

        val e = p.edit()
            .putLong(KEY_LAST_CHECK, now)
            .putStringSet(KEY_KNOWN, (stt + chat).toSet())
            .putString(KEY_STT_LIST, JSONArray(stt).toString())
            .putString(KEY_CHAT_LIST, JSONArray(chat).toString())
            .putString(KEY_NEW, JSONArray(stillNew).toString())
            .putString(KEY_CREATED, JSONObject(created as Map<*, *>).toString())
            .putString(KEY_STABLE, JSONArray(stable.toList()).toString())
        if (rec != null) {
            e.putString(KEY_REC_STT, rec.stt ?: "")
            e.putString(KEY_REC_LLM, rec.llm ?: "")
            e.putString(KEY_REC_NOTE, rec.note ?: "")
        }
        e.apply()

        return cached(p)
    }

    private fun fetchText(url: String): String? = try {
        Groq.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        }
    } catch (_: Exception) { null }

    private fun String?.parseList(): List<String> = try {
        if (this == null) emptyList() else JSONArray(this).let { a -> (0 until a.length()).map { a.getString(it) } }
    } catch (_: Exception) { emptyList() }

    private fun String?.parseLongMap(): Map<String, Long> = try {
        if (this == null) emptyMap() else JSONObject(this).let { o -> o.keys().asSequence().associateWith { o.getLong(it) } }
    } catch (_: Exception) { emptyMap() }
}
