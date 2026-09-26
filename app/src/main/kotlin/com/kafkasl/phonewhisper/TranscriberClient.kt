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
    data class Blocking(val text: String?, val error: String?, val httpCode: Int, val retryAfterSec: Long?)

    fun transcribeBlocking(wavData: ByteArray, apiKey: String, model: String, context: String): Blocking {
        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "json")
            .addFormDataPart("temperature", "0")
            .addFormDataPart("file", "audio.wav", wavData.toRequestBody("audio/wav".toMediaType()))
        if (context.isNotBlank()) builder.addFormDataPart("prompt", context.take(800))
        val request = Request.Builder()
            .url("${Groq.BASE_URL}/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey")
            .tag(String::class.java, model)
            .post(builder.build())
            .build()
        return try {
            Groq.client.newCall(request).execute().use { r ->
                val body = r.body?.string() ?: ""
                val parsed = parseResponse(body)
                val retry = r.header("retry-after")?.toDoubleOrNull()?.toLong()
                Blocking(if (r.isSuccessful) parsed.text else null,
                    if (r.isSuccessful) parsed.error else (parsed.error ?: "HTTP ${r.code}"), r.code, retry)
            }
        } catch (e: Exception) {
            Blocking(null, e.message ?: "network error", 0, null)
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
