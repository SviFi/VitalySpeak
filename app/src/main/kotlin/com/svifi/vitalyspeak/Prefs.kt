package com.svifi.vitalyspeak

import android.content.Context
import android.content.SharedPreferences

/** Every stored setting in one place. */
object Prefs {
    const val FILE = "vitalyspeak"
    private const val LEGACY_FILE = "phonewhisper"

    // Groq + models
    const val API_KEY = "api_key"
    const val STT_MODEL = "stt_model"
    const val LLM_MODEL = "llm_model"
    const val VOCAB = "stt_vocabulary"

    // Dictation behaviour
    const val CLEANUP = "use_post_processing"
    const val STYLE = "cleanup_style"                // standard | formal | notes | custom
    const val CUSTOM_PROMPT = "custom_post_processing_prompt"
    const val ONLY_WHILE_TYPING = "overlay_only_while_typing"
    const val LIVE_PREVIEW = "live_preview"
    const val KEEP_AUDIO = "keep_audio"
    const val COMMAND_KEYS = "command_keys"
    const val LAST_DICTATION = "last_dictation"

    // Languages
    const val SPEECH_LANGS = "speech_languages"      // comma-separated Whisper codes, primary first
    const val UI_LANG = "ui_language"                // BCP-47 tag or "" for system

    // Onboarding / consent
    const val DISCLOSURE_ACCEPTED = "a11y_disclosure_accepted"
    const val ASKED_NOTIFICATIONS = "asked_notifications"

    fun get(ctx: Context): SharedPreferences {
        val p = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        migrate(ctx, p)
        return p
    }

    @Volatile private var migrated = false

    /** One-time copy from the pre-1.0 settings file, keeping everything the user set. */
    private fun migrate(ctx: Context, p: SharedPreferences) {
        if (migrated) return
        synchronized(this) {
            if (migrated) return
            migrated = true
            if (p.getBoolean("migrated_v1", false)) return
            val old = ctx.applicationContext.getSharedPreferences(LEGACY_FILE, Context.MODE_PRIVATE)
            val e = p.edit()
            for ((k, v) in old.all) when (v) {
                is String -> e.putString(k, v)
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is Long -> e.putLong(k, v)
                is Float -> e.putFloat(k, v)
                is Set<*> -> e.putStringSet(k, v.filterIsInstance<String>().toSet())
            }
            if (old.all.isNotEmpty()) {
                // Existing installs were tuned for English + Russian: keep that behaviour.
                if (!p.contains(SPEECH_LANGS)) e.putString(SPEECH_LANGS, "en,ru")
                // Old builds had a built-in term list; keep it for them (new installs start empty).
                if (!old.contains(VOCAB)) e.putString(VOCAB, "DeskMe, Invest for Excel, AionLegs, Porvoo, Groq, Claude")
                // Old builds stored the whole prompt text; map it to a style.
                val oldPrompt = old.getString("post_processing_prompt", null)
                if (oldPrompt != null && !oldPrompt.startsWith("You are an automated speech-to-text cleanup engine")) {
                    e.putString(STYLE, "custom")
                    if (!old.contains(CUSTOM_PROMPT)) e.putString(CUSTOM_PROMPT, oldPrompt)
                }
                // The legacy accessibility service was already consented to by the user.
                e.putBoolean(DISCLOSURE_ACCEPTED, true)
            }
            e.putBoolean("migrated_v1", true).apply()
        }
    }
}
