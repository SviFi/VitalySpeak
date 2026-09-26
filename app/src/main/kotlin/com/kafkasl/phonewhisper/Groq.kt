package com.kafkasl.phonewhisper

import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Shared Groq configuration: one API key, one connection pool for all calls. */
object Groq {
    const val BASE_URL = "https://api.groq.com/openai/v1"

    const val DEFAULT_STT_MODEL = "whisper-large-v3"
    // llama-3.3-70b-versatile became Enterprise-only on Groq (Sep 2026), so it is not the default.
    const val DEFAULT_LLM_MODEL = "openai/gpt-oss-120b"

    /**
     * Whisper "prompt": biases spelling and tells the model that mixed Russian/English
     * is expected, which helps it keep code-switched speech instead of translating it.
     */
    const val DEFAULT_VOCAB =
        "Привет, проверь pull request в репозитории. DeskMe, Invest for Excel, AionLegs, Porvoo, Groq, Claude."

    // SharedPreferences keys
    const val PREFS = "phonewhisper"
    const val KEY_API = "api_key"
    const val KEY_STT_MODEL = "stt_model"
    const val KEY_LLM_MODEL = "llm_model"
    const val KEY_VOCAB = "stt_vocabulary"
    const val KEY_USE_CLEANUP = "use_post_processing"

    /** One client => one connection pool, so the warm-up connection is reused by the upload. */
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(2, 5, TimeUnit.MINUTES))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Fire-and-forget request that establishes DNS + TLS to api.groq.com while the user is
     * still speaking. The response is ignored; it never blocks or delays dictation.
     */
    fun warmUp(apiKey: String) {
        if (apiKey.isBlank()) return
        val req = Request.Builder()
            .url("$BASE_URL/models")
            .header("Authorization", "Bearer $apiKey")
            .head()
            .build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) { response.close() }
        })
    }
}
