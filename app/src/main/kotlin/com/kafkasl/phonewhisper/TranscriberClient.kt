package com.kafkasl.phonewhisper

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/** Speech-to-text via Groq's OpenAI-compatible /audio/transcriptions endpoint. */
object TranscriberClient {
    data class Result(val text: String?, val error: String?)

    fun parseResponse(json: String): Result = try {
        val obj = JSONObject(json)
        when {
            obj.has("text") -> Result(obj.getString("text").trim(), null)
            obj.has("error") -> Result(null, obj.getJSONObject("error").getString("message"))
            else -> Result(null, "Unknown response")
        }
    } catch (e: Exception) {
        Result(null, e.message ?: "Parse error")
    }

    /** Synchronous variant for the background part worker. [httpCode] 0 = network error. */
    data class Blocking(
        val text: String?, val error: String?, val httpCode: Int, val retryAfterSec: Long?,
        /** Words (or segments) with times relative to this audio, when Groq returned them. */
        val units: List<Dictation.Timed>? = null
    )

    /**
     * Request formats, best first. Groq wants the array form `timestamp_granularities[]`; if a
     * format is ever rejected (HTTP 400), we fall back to the next and remember it.
     */
    private enum class Mode { WORDS, SEGMENTS, PLAIN }
    @Volatile private var mode = Mode.WORDS

    fun parseVerbose(json: String): Pair<String?, List<Dictation.Timed>?> = try {
        val o = JSONObject(json)
        val text = o.optString("text").trim()
        fun list(key: String, textKey: String) = o.optJSONArray(key)?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                Dictation.Timed(it.optDouble("start", 0.0), it.optDouble("end", 0.0), it.optString(textKey).trim())
            }.filter { it.text.isNotEmpty() }
        }
        val units = list("words", "word")?.takeIf { it.isNotEmpty() } ?: list("segments", "text")
        text to units
    } catch (_: Exception) { null to null }

    fun transcribeBlocking(wavData: ByteArray, apiKey: String, model: String, context: String): Blocking {
        while (true) {
            val m = mode
            val builder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", model)
                .addFormDataPart("response_format", if (m == Mode.PLAIN) "json" else "verbose_json")
                .addFormDataPart("temperature", "0")
                .addFormDataPart("file", "audio.wav", wavData.toRequestBody("audio/wav".toMediaType()))
            if (m == Mode.WORDS) {
                builder.addFormDataPart("timestamp_granularities[]", "word")
                builder.addFormDataPart("timestamp_granularities[]", "segment")
            }
            if (context.isNotBlank()) builder.addFormDataPart("prompt", context.take(800))
            val request = Request.Builder()
                .url("${Groq.BASE_URL}/audio/transcriptions")
                .header("Authorization", "Bearer $apiKey")
                .tag(String::class.java, model)
                .post(builder.build())
                .build()
            val result = try {
                Groq.client.newCall(request).execute().use { r ->
                    val body = r.body?.string() ?: ""
                    val retry = r.header("retry-after")?.toDoubleOrNull()?.toLong()
                    if (r.isSuccessful) {
                        if (m == Mode.PLAIN) {
                            val p = parseResponse(body)
                            Blocking(p.text, p.error, r.code, retry)
                        } else {
                            val (text, units) = parseVerbose(body)
                            Blocking(text, if (text == null) "Unreadable response" else null, r.code, retry, units)
                        }
                    } else Blocking(null, parseResponse(body).error ?: "HTTP ${r.code}", r.code, retry)
                }
            } catch (e: Exception) {
                Blocking(null, e.message ?: "network error", 0, null)
            }
            // Format rejected? Try the simpler one (never loops past PLAIN).
            val formatProblem = result.error?.let { e ->
                listOf("timestamp", "granular", "response_format", "verbose").any { e.contains(it, ignoreCase = true) }
            } ?: false
            if (result.httpCode == 400 && m != Mode.PLAIN && formatProblem) {
                mode = if (m == Mode.WORDS) Mode.SEGMENTS else Mode.PLAIN
                continue
            }
            return result
        }
    }

    fun transcribe(
        wavData: ByteArray,
        apiKey: String,
        model: String = Groq.DEFAULT_STT_MODEL,
        vocabulary: String = "",
        callback: (Result) -> Unit
    ) {
        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "json")
            .addFormDataPart("temperature", "0")
            .addFormDataPart("file", "audio.wav", wavData.toRequestBody("audio/wav".toMediaType()))
        // No "language" field: Whisper auto-detects, which is what we want for RU/EN mixing.
        if (vocabulary.isNotBlank()) builder.addFormDataPart("prompt", vocabulary.take(800))

        val request = Request.Builder()
            .url("${Groq.BASE_URL}/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey")
            .tag(String::class.java, model)
            .post(builder.build())
            .build()

        Groq.client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(Result(null, e.message))
            override fun onResponse(call: Call, response: Response) {
                val body = response.use { it.body?.string() ?: "" }
                if (!response.isSuccessful && body.isBlank()) {
                    callback(Result(null, "HTTP ${response.code}")); return
                }
                callback(parseResponse(body))
            }
        })
    }
}
