package com.svifi.vitalyspeak

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ApiParsingTest {

    @Test fun `verbose reply gives text, word timings and language`() {
        val body = """{"text":" Hello world ","language":"English","words":[
            {"word":"Hello","start":0.1,"end":0.5},{"word":" world","start":0.6,"end":1.0}],
            "segments":[{"text":"Hello world","start":0.0,"end":1.0}]}"""
        val r = SpeechApi.parseVerbose(body)
        assertEquals("Hello world", r.text)
        assertEquals("english", r.language)
        assertEquals(2, r.units!!.size)
        assertEquals("world", r.units!![1].text)
        assertEquals(0.6, r.units!![1].start, 1e-9)
    }

    @Test fun `verbose reply without words falls back to segments`() {
        val r = SpeechApi.parseVerbose("""{"text":"Hi","segments":[{"text":" Hi ","start":0,"end":0.4}]}""")
        assertEquals(listOf("Hi"), r.units!!.map { it.text })
        assertNull(r.language)
    }

    @Test fun `api errors are reported, not treated as text`() {
        val r = SpeechApi.parsePlain("""{"error":{"message":"Invalid API Key"}}""")
        assertNull(r.text)
        assertEquals("Invalid API Key", r.error)
        assertNull(SpeechApi.parsePlain("not json").text)
    }

    @Test fun `chat reply is unwrapped and a cut-off reply fails`() {
        val ok = CleanupApi.parse("""{"choices":[{"finish_reason":"stop","message":{"content":"\"Clean text.\""}}]}""")
        assertEquals("Clean text.", ok.text)
        val cut = CleanupApi.parse("""{"choices":[{"finish_reason":"length","message":{"content":"Half"}}]}""")
        assertNull(cut.text)
        assertNotNull(cut.error)
    }

    @Test fun `unwrap keeps inner quotes and strips fences`() {
        assertEquals("He said \"hi\" twice", CleanupApi.unwrap("He said \"hi\" twice"))
        assertEquals("text", CleanupApi.unwrap("```\ntext\n```"))
        assertEquals("Привет", CleanupApi.unwrap("«Привет»"))
    }

    @Test fun `prompts carry languages, style and vocabulary`() {
        val p = CleanupApi.systemPrompt(CleanupApi.Style.NOTES, listOf("English", "Russian"), "", "Porvoo")
        assertTrue(p.contains("English, Russian"))
        assertTrue(p.contains("bullet"))
        assertTrue(p.contains("Porvoo"))
        val custom = CleanupApi.systemPrompt(CleanupApi.Style.CUSTOM, emptyList(), "Use British spelling.", "")
        assertTrue(custom.contains("Use British spelling."))
        assertFalse(custom.contains("Names and terms"))
        assertEquals(CleanupApi.Style.STANDARD, CleanupApi.style(null))
        assertEquals(CleanupApi.Style.FORMAL, CleanupApi.style("formal"))
    }

    @Test fun `wav header describes the pcm`() {
        val pcm = ByteArray(3200) { it.toByte() }
        val wav = Wav.fromPcm16(pcm)
        assertEquals(44 + pcm.size, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(36 + pcm.size, b.getInt(4))
        assertEquals(16000, b.getInt(24))
        assertEquals(32000, b.getInt(28))
        assertEquals(16, b.getShort(34).toInt())
        assertEquals(pcm.size, b.getInt(40))
        assertEquals(pcm[100], wav[144])
    }

    @Test fun `spacing joins dictated text naturally`() {
        assertEquals(" world", Spacing.join("Hello", "world", ""))
        assertEquals("world", Spacing.join("Hello ", "world", ""))
        assertEquals("Hello ", Spacing.join("", "Hello", "world"))
        assertEquals("there", Spacing.join("", "there", "."))
        assertEquals(" two ", Spacing.join("one", "two", "three"))
        assertEquals(", ok", Spacing.join("yes", ", ok", ""))
    }

    @Test fun `languages search and whisper names`() {
        assertTrue(Languages.SPEECH.containsAll(listOf("en", "ru", "fi", "yue", "haw")))
        assertEquals("russian", Languages.englishName("ru"))
        assertEquals("finnish", Languages.englishName("fi"))
        assertTrue(Languages.matches("fi", "finn", java.util.Locale.ENGLISH))
        assertTrue(Languages.matches("ru", "рус", java.util.Locale.ENGLISH))
        assertFalse(Languages.matches("ru", "fin", java.util.Locale.ENGLISH))
        assertTrue(Languages.whisperHint(listOf("en", "ru")).contains("Привет"))
        assertEquals("", Languages.whisperHint(listOf("haw")))
    }
}
