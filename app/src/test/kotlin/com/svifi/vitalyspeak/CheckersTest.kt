package com.svifi.vitalyspeak

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

    private val docsMd = """
# Supported Models
## [Production Models](#production-models)
| MODEL ID | SPEED |
|---|---|
| `llama-3.1-8b-instant` | 560 |
| `llama-3.3-70b-versatile` | 280 |
| `whisper-large-v3` | - |
| `whisper-large-v3-turbo` | - |
## [Preview Models](#preview-models)
| `qwen/qwen3-32b` | 400 |
## [Deprecated Models](#deprecated-models)
| `old-model` | - |
""".trimIndent()

    @Test fun `parses production ids from groq docs`() {
        val ids = ModelChecker.parseProductionIds(docsMd)
        assertEquals(setOf("llama-3.1-8b-instant", "llama-3.3-70b-versatile", "whisper-large-v3", "whisper-large-v3-turbo"), ids)
    }

    @Test fun `parses real groq docs rows with links and skips enterprise`() {
        val md = """
## [Production Models](#production-models)
| MODEL ID | SPEED (T/SEC) | PRICE |
| [![Meta](https://console.groq.com/_next/image?url=%2FMeta_logo.png&w=48&q=75)Llama 3.3 70B](/docs/model/llama-3.3-70b-versatile)Enterprisellama-3.3-70b-versatile | 280 | ContactSales |
| [![OpenAI](https://console.groq.com/_next/static/media/openailogo.523c87a0.svg)GPT OSS 120B](/docs/model/openai/gpt-oss-120b)openai/gpt-oss-120b | 500 | x |
| [![OpenAI](https://console.groq.com/_next/static/media/openailogo.523c87a0.svg)Whisper](/docs/model/whisper-large-v3)whisper-large-v3 | \\- | x |
## [Preview Models](#preview-models)
| [![Alibaba Cloud](https://console.groq.com/_next/image?url=%2Fqwen_logo.png&w=48&q=75)Qwen/Qwen3.8-27B](/docs/model/qwen/qwen3.8-27b)qwen/qwen3.8-27b | 450 | x |
""".trimIndent()
        assertEquals(setOf("openai/gpt-oss-120b", "whisper-large-v3"), ModelChecker.parseProductionIds(md))
    }

    @Test fun `stability falls back to name heuristic`() {
        assertTrue(ModelChecker.isStable("whisper-large-v4", emptySet()))
        assertFalse(ModelChecker.isStable("llama-5-preview", emptySet()))
        assertFalse(ModelChecker.isStable("qwen/qwen3-32b", setOf("whisper-large-v3")))
    }

    private fun report(created: Map<String, Long>, stable: Set<String> = emptySet(), ignored: Set<String> = emptySet()) =
        ModelChecker.Report(created.keys.toList(), created.keys.toList(), emptyList(), null, 1L, created, stable, ignored)

    @Test fun `same family newer version is an upgrade`() {
        val r = report(mapOf("whisper-large-v3" to 100L, "whisper-large-v4" to 200L, "whisper-large-v5-preview" to 300L))
        val up = ModelChecker.findUpgrade("whisper-large-v3", r.stt, r)!!
        assertEquals("whisper-large-v4", up.to)
        assertTrue(up.sameFamily)
    }

    @Test fun `preview and ignored models are never suggested`() {
        val r = report(mapOf("whisper-large-v3" to 100L, "whisper-large-v3-turbo" to 200L, "whisper-x-beta" to 300L),
            ignored = setOf("whisper-large-v3-turbo"))
        assertNull(ModelChecker.findUpgrade("whisper-large-v3", r.stt, r))
    }

    @Test fun `newer stable model in another family is offered but flagged`() {
        val r = report(mapOf("llama-3.3-70b-versatile" to 100L, "llama-3.1-8b-instant" to 50L, "kimi-k3" to 300L, "glm-5" to 200L),
            stable = setOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant", "kimi-k3", "glm-5"))
        val up = ModelChecker.findUpgrade("llama-3.3-70b-versatile", r.chat, r)!!
        assertEquals("kimi-k3", up.to)
        assertFalse(up.sameFamily)
        assertEquals(listOf("glm-5"), up.others)
    }

    @Test fun `current model is newest - no upgrade`() {
        val r = report(mapOf("llama-3.3-70b-versatile" to 100L, "llama-3.1-8b-instant" to 50L))
        assertNull(ModelChecker.findUpgrade("llama-3.3-70b-versatile", r.chat, r))
    }
}
