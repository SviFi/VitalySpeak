package com.svifi.vitalyspeak

import android.content.Context
import java.util.Locale

/**
 * Languages: what Whisper can transcribe, and what the app's interface is translated into.
 *
 * Speech languages are stored as Whisper codes, primary first. Telling Whisper which
 * languages to expect matters: with one language it's passed directly (no guessing at all);
 * with several, the app checks Whisper's detected language and re-transcribes with the
 * primary language if it guessed one you don't speak.
 */
object Languages {

    /** Every language Whisper large-v3 supports (Whisper's own codes). */
    val SPEECH = listOf(
        "af", "am", "ar", "as", "az", "ba", "be", "bg", "bn", "bo", "br", "bs", "ca", "cs", "cy",
        "da", "de", "el", "en", "es", "et", "eu", "fa", "fi", "fo", "fr", "gl", "gu", "ha", "haw",
        "he", "hi", "hr", "ht", "hu", "hy", "id", "is", "it", "ja", "jw", "ka", "kk", "km", "kn",
        "ko", "la", "lb", "ln", "lo", "lt", "lv", "mg", "mi", "mk", "ml", "mn", "mr", "ms", "mt",
        "my", "ne", "nl", "nn", "no", "oc", "pa", "pl", "ps", "pt", "ro", "ru", "sa", "sd", "si",
        "sk", "sl", "sn", "so", "sq", "sr", "su", "sv", "sw", "ta", "te", "tg", "th", "tk", "tl",
        "tr", "tt", "uk", "ur", "uz", "vi", "yi", "yo", "yue", "zh"
    )

    /** Interface translations shipped with the app (BCP-47 tags, as in res/values-*). */
    val UI = listOf(
        "en", "es", "pt-BR", "fr", "de", "it", "ru", "uk", "pl", "nl", "fi", "tr",
        "ar", "hi", "id", "vi", "th", "ja", "ko", "zh-CN", "zh-TW"
    )

    /** Whisper code → locale tag where they differ. */
    private fun localeFor(code: String): Locale = Locale.forLanguageTag(when (code) {
        "jw" -> "jv"; "no" -> "nb"; "haw" -> "haw"; "yue" -> "yue"; "tl" -> "fil"
        else -> code
    })

    /** "Russian (русский)" — name in the interface language, native name in brackets. */
    fun label(code: String, ui: Locale = Locale.getDefault()): String {
        val loc = localeFor(code)
        val inUi = loc.getDisplayName(ui).ifBlank { code }.replaceFirstChar { it.titlecase(ui) }
        val native = loc.getDisplayName(loc).ifBlank { inUi }.replaceFirstChar { it.titlecase(loc) }
        return if (native.equals(inUi, ignoreCase = true)) inUi else "$inUi ($native)"
    }

    /** English name, lowercase — matches the "language" field Whisper returns. */
    fun englishName(code: String): String = localeFor(code).getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.ENGLISH)

    /** Search: matches the name in the interface language, the native name, English name or code. */
    fun matches(code: String, query: String, ui: Locale = Locale.getDefault()): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        return label(code, ui).lowercase().contains(q) || englishName(code).contains(q) || code.startsWith(q)
    }

    // ---------- selected speech languages ----------

    fun selected(ctx: Context): List<String> {
        val raw = Prefs.get(ctx).getString(Prefs.SPEECH_LANGS, null)
        val list = raw?.split(',')?.map { it.trim() }?.filter { it in SPEECH }?.distinct()
        if (!list.isNullOrEmpty()) return list
        // Default: the phone's language (if Whisper knows it).
        val dev = Locale.getDefault().language.let { if (it == "nb") "no" else it }
        return listOf(if (dev in SPEECH) dev else "en")
    }

    fun save(ctx: Context, codes: List<String>) {
        Prefs.get(ctx).edit().putString(Prefs.SPEECH_LANGS, codes.distinct().joinToString(",")).apply()
    }

    /** Short sample phrases: as Whisper context they nudge recognition toward these languages. */
    private val SAMPLE = mapOf(
        "en" to "Hello, let's get started.", "ru" to "Привет, давай начнём.", "uk" to "Привіт, почнімо.",
        "de" to "Hallo, fangen wir an.", "fr" to "Bonjour, commençons.", "es" to "Hola, empecemos.",
        "it" to "Ciao, iniziamo.", "pt" to "Olá, vamos começar.", "nl" to "Hallo, laten we beginnen.",
        "pl" to "Cześć, zaczynajmy.", "fi" to "Hei, aloitetaan.", "sv" to "Hej, nu börjar vi.",
        "no" to "Hei, la oss begynne.", "da" to "Hej, lad os begynde.", "et" to "Tere, alustame.",
        "lv" to "Sveiki, sāksim.", "lt" to "Labas, pradėkime.", "cs" to "Ahoj, začněme.",
        "tr" to "Merhaba, başlayalım.", "ar" to "مرحبا، لنبدأ.", "he" to "שלום, בואו נתחיל.",
        "hi" to "नमस्ते, चलिए शुरू करते हैं।", "ja" to "こんにちは、始めましょう。", "ko" to "안녕하세요, 시작합시다.",
        "zh" to "你好，我们开始吧。", "vi" to "Xin chào, bắt đầu nhé.", "th" to "สวัสดี เริ่มกันเลย",
        "id" to "Halo, mari kita mulai.", "el" to "Γεια σας, ας ξεκινήσουμε.", "hu" to "Szia, kezdjük.",
        "ro" to "Salut, să începem.", "bg" to "Здравей, да започваме."
    )

    /** Whisper "prompt" for the selected languages (empty if none of them has a sample). */
    fun whisperHint(codes: List<String>): String = codes.take(3).mapNotNull { SAMPLE[it] }.joinToString(" ")
}
