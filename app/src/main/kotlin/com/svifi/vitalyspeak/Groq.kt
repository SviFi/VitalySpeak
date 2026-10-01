package com.svifi.vitalyspeak

import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Groq endpoint, default models and the single HTTP client every request shares. */
object Groq {
    const val BASE_URL = "https://api.groq.com/openai/v1"
    const val DEFAULT_STT_MODEL = "whisper-large-v3"
    const val DEFAULT_LLM_MODEL = "openai/gpt-oss-120b"
    const val KEYS_URL = "https://console.groq.com/keys"

    /** Shared so the warm-up connection is reused by the real upload. */
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(2, 5, TimeUnit.MINUTES))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(GroqUsage.interceptor)
        .build()

    /** Null if Groq accepts [apiKey]; otherwise a short reason (blocking: call off the main thread). */
    fun checkKey(apiKey: String): String? = try {
        val req = Request.Builder().url("$BASE_URL/models").header("Authorization", "Bearer $apiKey").build()
        client.newCall(req).execute().use { r ->
            if (r.isSuccessful) null
            else SpeechApi.errorMessage(r.body?.string().orEmpty()) ?: "HTTP ${r.code}"
        }
    } catch (e: IOException) { e.message ?: "network error" }

    /** Opens DNS + TLS to Groq while the user is still talking; the reply is ignored. */
    fun warmUp(apiKey: String) {
        if (apiKey.isBlank()) return
        val req = Request.Builder().url("$BASE_URL/models").header("Authorization", "Bearer $apiKey").head().build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) = response.close()
        })
    }
}
