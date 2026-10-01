package com.svifi.vitalyspeak

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

/** Groq Whisper (OpenAI-compatible /audio/transcriptions). */
object SpeechApi {

    data class Reply(
        val text: String?,
        val error: String?,
        /** 0 = no HTTP response (network problem). */
        val httpCode: Int,
        val retryAfterSec: Long? = null,
        /** Timed words (or segments), relative to the start of the sent audio. */
        val units: List<Dictation.Timed>? = null,
        /** Language Whisper detected, e.g. "english" (verbose replies only). */
        val language: String? = null
    )

    /** Error message from an API error body, if it is one. */
    fun errorMessage(body: String): String? = try {
        JSONObject(body).optJSONObject("error")?.optString("message")?.ifBlank { null }
    } catch (_: Exception) { null }

    /** Parses a verbose_json reply: full text, timed words (or segments), detected language. */
    fun parseVerbose(body: String): Reply {
        val o = try { JSONObject(body) } catch (e: Exception) { return Reply(null, "Unreadable reply", 200) }
        if (!o.has("text")) return Reply(null, errorMessage(body) ?: "Reply without text", 200)
        fun timed(key: String, field: String): List<Dictation.Timed>? {
            val arr = o.optJSONArray(key) ?: return null
            val out = ArrayList<Dictation.Timed>(arr.length())
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val t = item.optString(field).trim()
                if (t.isNotEmpty()) out += Dictation.Timed(item.optDouble("start", 0.0), item.optDouble("end", 0.0), t)
            }
            return out
        }
        val units = timed("words", "word")?.takeIf { it.isNotEmpty() } ?: timed("segments", "text")
        return Reply(o.getString("text").trim(), null, 200, null, units,
            o.optString("language").lowercase().ifBlank { null })
    }

    /** Parses a plain json reply ({"text": …}). */
    fun parsePlain(body: String): Reply = try {
        val o = JSONObject(body)
        if (o.has("text")) Reply(o.getString("text").trim(), null, 200)
        else Reply(null, errorMessage(body) ?: "Reply without text", 200)
    } catch (_: Exception) { Reply(null, "Unreadable reply", 200) }

    /**
     * Reply formats, richest first. Groq wants `timestamp_granularities[]` (array form); if a
     * format is ever rejected we step down and remember it for the rest of the session.
     */
    private enum class Format { WORDS, SEGMENTS, PLAIN }
    @Volatile private var format = Format.WORDS

    private fun request(wav: ByteArray, apiKey: String, model: String, prompt: String,
                        language: String?, fmt: Format): Request {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("temperature", "0")
            .addFormDataPart("response_format", if (fmt == Format.PLAIN) "json" else "verbose_json")
            .addFormDataPart("file", "speech.wav", wav.toRequestBody("audio/wav".toMediaType()))
        if (fmt == Format.WORDS) {
            form.addFormDataPart("timestamp_granularities[]", "word")
            form.addFormDataPart("timestamp_granularities[]", "segment")
        }
        if (!language.isNullOrBlank()) form.addFormDataPart("language", language)
        if (prompt.isNotBlank()) form.addFormDataPart("prompt", prompt.take(800))
        return Request.Builder()
            .url("${Groq.BASE_URL}/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey")
            .tag(String::class.java, model)
            .post(form.build())
            .build()
    }

    /** Blocking transcription (background worker). [language]: Whisper code, or null = detect. */
    fun transcribe(wav: ByteArray, apiKey: String, model: String, prompt: String, language: String?): Reply {
        while (true) {
            val fmt = format
            val reply = try {
                Groq.client.newCall(request(wav, apiKey, model, prompt, language, fmt)).execute().use { r ->
                    val body = r.body?.string().orEmpty()
                    val retry = r.header("retry-after")?.toDoubleOrNull()?.toLong()
                    if (r.isSuccessful) {
                        (if (fmt == Format.PLAIN) parsePlain(body) else parseVerbose(body)).copy(httpCode = r.code)
                    } else Reply(null, errorMessage(body) ?: "HTTP ${r.code}", r.code, retry)
                }
            } catch (e: IOException) {
                Reply(null, e.message ?: "network error", 0)
            }
            val formatRejected = reply.httpCode == 400 && fmt != Format.PLAIN &&
                listOf("timestamp", "granular", "response_format", "verbose")
                    .any { reply.error?.contains(it, ignoreCase = true) == true }
            if (formatRejected) { format = if (fmt == Format.WORDS) Format.SEGMENTS else Format.PLAIN; continue }
            return reply
        }
    }

    /** Fire-and-forget transcription for the live preview bubble. */
    fun transcribeAsync(wav: ByteArray, apiKey: String, model: String, prompt: String,
                        language: String?, done: (Reply) -> Unit) {
        Groq.client.newCall(request(wav, apiKey, model, prompt, language, Format.PLAIN)).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = done(Reply(null, e.message, 0))
            override fun onResponse(call: Call, response: Response) {
                val body = response.use { it.body?.string().orEmpty() }
                done(if (response.isSuccessful) parsePlain(body)
                     else Reply(null, errorMessage(body) ?: "HTTP ${response.code}", response.code))
            }
        })
    }
}
