package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test

class CheckersTest {

    private val modelsJson = """
    {"object":"list","data":[
      {"id":"whisper-large-v3","object":"model","created":1693721698,"owned_by":"OpenAI","active":true},
      {"id":"whisper-large-v3-turbo","object":"model","created":1728413088,"owned_by":"OpenAI","active":true},
      {"id":"llama-3.3-70b-versatile","object":"model","created":1733447754,"owned_by":"Meta","active":true},
      {"id":"openai/gpt-oss-120b","object":"model","created":1754408224,"owned_by":"OpenAI","active":true},
      {"id":"meta-llama/llama-prompt-guard-2-86m","object":"model","created":1,"owned_by":"Meta","active":true},
      {"id":"playai-tts","object":"model","created":1,"owned_by":"PlayAI","active":true},
      {"id":"old-model","object":"model","created":1,"owned_by":"x","active":false}
    ]}
    """.trimIndent()

    @Test fun `parses and drops inactive models`() {
        val ids = ModelChecker.parseModels(modelsJson).map { it.id }
        assertTrue("whisper-large-v3" in ids)
        assertFalse("old-model" in ids)
    }

    @Test fun `classifies speech and chat models`() {
        val ids = ModelChecker.parseModels(modelsJson).map { it.id }
        assertEquals(listOf("whisper-large-v3", "whisper-large-v3-turbo"), ids.filter(ModelChecker::isStt))
        assertEquals(listOf("llama-3.3-70b-versatile", "openai/gpt-oss-120b"), ids.filter(ModelChecker::isChat))
    }

    @Test fun `new models only reported after first check`() {
        assertEquals(emptyList<String>(), ModelChecker.newSince(emptySet(), listOf("a", "b")))
        assertEquals(listOf("c"), ModelChecker.newSince(setOf("a", "b"), listOf("a", "b", "c")))
    }

    @Test fun `parses recommendation`() {
        val r = ModelChecker.parseRecommendation("""{"stt":"whisper-large-v3","llm":"x","note":"hi"}""")!!
        assertEquals("whisper-large-v3", r.stt); assertEquals("x", r.llm); assertEquals("hi", r.note)
        assertNull(ModelChecker.parseRecommendation("not json"))
    }

    @Test fun `version from tag`() {
        assertEquals(42L, UpdateChecker.versionFromTag("build-42"))
        assertNull(UpdateChecker.versionFromTag("latest"))
    }

    @Test fun `parses github release`() {
        val r = UpdateChecker.parseRelease("""
          {"tag_name":"build-7","name":"VitalySpeak build 7","body":"notes",
           "assets":[{"name":"VitalySpeak-7.apk","browser_download_url":"https://x/VitalySpeak-7.apk"}]}
        """)!!
        assertEquals(7L, r.versionCode)
        assertEquals("https://x/VitalySpeak-7.apk", r.apkUrl)
        assertNull(UpdateChecker.parseRelease("""{"tag_name":"build-7","assets":[]}"""))
    }
}
