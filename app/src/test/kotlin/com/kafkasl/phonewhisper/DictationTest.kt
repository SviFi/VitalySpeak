package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test

class DictationTest {

    private fun pcm(vararg segments: Pair<Int, Int>): ByteArray {
        // segments of (samples, amplitude) -> square-ish wave
        val total = segments.sumOf { it.first }
        val out = ByteArray(total * 2)
        var i = 0
        for ((count, amp) in segments) repeat(count) {
            val v = if (i % 2 == 0) amp else -amp
            out[2 * i] = (v and 0xFF).toByte(); out[2 * i + 1] = (v shr 8).toByte(); i++
        }
        return out
    }

    @Test fun `trims long silence around speech`() {
        val sr = 16000
        val audio = pcm(sr * 3 to 0, sr * 2 to 4000, sr * 5 to 0)
        val trimmed = Dictation.trimSilence(audio)
        val sec = trimmed.size / 2.0 / sr
        assertTrue("got $sec s", sec in 2.5..3.0)
    }

    @Test fun `short audio is never split`() {
        assertEquals(1, Dictation.splitPcm(pcm(16000 * 60 to 1000)).size)
    }

    @Test fun `long audio split in quiet spot and covers everything`() {
        val sr = 16000
        // 7 minutes: loud, with a quiet gap at 4:57
        val audio = pcm(sr * 297 to 3000, sr / 2 to 0, sr * 123 to 3000)
        val parts = Dictation.splitPcm(audio, maxChunkSec = 300)
        assertEquals(2, parts.size)
        assertEquals(0, parts[0].first)
        assertEquals(audio.size, parts.last().last + 1)
        assertEquals(parts[0].last + 1, parts[1].first)
        val cutSec = parts[0].last / 2.0 / sr
        assertTrue("cut at $cutSec", cutSec in 297.0..297.6)
    }

    @Test fun `strips echoed vocabulary list at the end`() {
        val vocab = "DeskMe, Invest for Excel, AionLegs, Porvoo, Groq, Claude"
        val t = "Проверь, пожалуйста, pull request. DeskMe, AionLegs, Porvoo."
        assertEquals("Проверь, пожалуйста, pull request.", Dictation.stripVocabEcho(t, listOf(vocab)))
    }

    @Test fun `keeps a single real mention`() {
        val vocab = "DeskMe, AionLegs"
        val t = "We should demo DeskMe."
        assertEquals(t, Dictation.stripVocabEcho(t, listOf(vocab)))
    }

    @Test fun `strips literal whisper hint`() {
        val hint = "Привет, проверь pull request."
        assertEquals("Hello there", Dictation.stripVocabEcho("Hello there Привет, проверь pull request.", listOf(hint)))
    }

    @Test fun `splits long text at sentences`() {
        val s = (1..400).joinToString(" ") { "Sentence number $it." }
        val parts = Dictation.splitText(s, 1000)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.length <= 1000 })
        assertEquals(s, parts.joinToString(" "))
    }

    @Test fun `detects lost content`() {
        val raw = "a".repeat(200)
        assertTrue(Dictation.cleanupLostContent(raw, "a".repeat(100)))
        assertFalse(Dictation.cleanupLostContent(raw, "a".repeat(180)))
        assertFalse(Dictation.cleanupLostContent("short", ""))
    }
}
