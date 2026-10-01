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

    private fun w(s: Double, e: Double, t: String) = Dictation.Timed(s, e, t)

    @Test fun `splits words into content and command blocks`() {
        val units = listOf(
            w(0.0, 0.4, "Купить"), w(0.4, 0.8, "молоко"), w(0.8, 1.2, "and"), w(1.2, 1.6, "bread."),
            w(2.0, 2.4, "make"), w(2.4, 2.8, "it"), w(2.8, 3.2, "a"), w(3.2, 3.6, "list"),
            w(4.0, 4.5, "Next"), w(4.5, 5.0, "point.")
        )
        val b = Dictation.splitByCommands(units, listOf(1.9 to 3.7))
        assertEquals(3, b.size)
        assertEquals(Dictation.Block(false, "Купить молоко and bread."), b[0])
        assertEquals(Dictation.Block(true, "make it a list"), b[1])
        assertEquals(Dictation.Block(false, "Next point."), b[2])
        val tagged = Dictation.toTagged(b)
        assertTrue(tagged.contains("<command after=\"1\">make it a list</command>"))
        assertTrue(tagged.contains("<content id=\"2\">Next point.</content>"))
    }

    @Test fun `no commands means a single content block`() {
        val b = Dictation.splitByCommands(listOf(w(0.0, 1.0, "Hello"), w(1.0, 2.0, "world")), emptyList())
        assertEquals(listOf(Dictation.Block(false, "Hello world")), b)
    }

    @Test fun `windows end after commands`() {
        val blocks = listOf(Dictation.Block(false, "a".repeat(60)), Dictation.Block(true, "cmd1"),
            Dictation.Block(false, "b".repeat(60)), Dictation.Block(true, "cmd2"), Dictation.Block(false, "tail"))
        val w = Dictation.windows(blocks, maxChars = 50)
        assertEquals(3, w.size)
        assertTrue(w[0].last().isCommand)
        assertEquals("tail", w[2].single().text)
    }

    @Test fun `separates pending actions section`() {
        val (notes, items) = Dictation.splitPending("# Notes\n- one\n\nPending actions\n- Email Anna the list\n- Remind me at 5")
        assertEquals("# Notes\n- one", notes)
        assertEquals(listOf("- Email Anna the list", "- Remind me at 5"), items)
        assertEquals("plain" to emptyList<String>(), Dictation.splitPending("plain"))
    }

    @Test fun `trim range reports cut start`() {
        val sr = 16000
        val audio = pcm(sr * 3 to 0, sr * 2 to 4000, sr * 1 to 0)
        val r = Dictation.trimRange(audio)
        val startSec = r.first / 2.0 / sr
        assertTrue("start $startSec", startSec in 2.5..2.7)
    }

    @Test fun `command mark is snapped to the whole sentence despite timing drift`() {
        // Real case: user tapped Command, said "Can you translate it to Finnish?", but Whisper's
        // timings put "Can you translate" before the mark.
        val units = listOf(
            w(0.0, 0.3, "Okay,"), w(0.3, 0.5, "now"), w(0.5, 0.7, "I'm"), w(0.7, 1.0, "typing"),
            w(1.0, 1.2, "some"), w(1.2, 1.6, "text."),
            w(1.7, 1.9, "Can"), w(1.9, 2.0, "you"), w(2.0, 2.4, "translate"),
            w(2.6, 2.7, "it"), w(2.7, 2.8, "to"), w(2.8, 3.3, "Finnish?")
        )
        val b = Dictation.splitByCommands(units, listOf(2.5 to 3.4))
        assertEquals(listOf(
            Dictation.Block(false, "Okay, now I'm typing some text."),
            Dictation.Block(true, "Can you translate it to Finnish?")
        ), b)
    }

    @Test fun `snapping does not grab a long preceding sentence`() {
        // Sentence started 5 s before the mark: too far back, keep the mark as is.
        val units = (0 until 20).map { w(it * 0.3, it * 0.3 + 0.25, "w$it") } +
            listOf(w(6.0, 6.3, "make"), w(6.3, 6.6, "bullets."))
        val b = Dictation.splitByCommands(units, listOf(5.95 to 6.7))
        assertEquals(Dictation.Block(true, "make bullets."), b.last())
        assertFalse(b.first().isCommand)
    }
}
