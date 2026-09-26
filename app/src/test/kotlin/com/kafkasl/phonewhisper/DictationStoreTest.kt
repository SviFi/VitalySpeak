package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class DictationStoreTest {

    private fun tone(samples: Int, amp: Int): ByteArray {
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val v = if (i % 2 == 0) amp else -amp
            out[2 * i] = (v and 0xFF).toByte(); out[2 * i + 1] = (v shr 8).toByte()
        }
        return out
    }

    private fun store() = DictationStore(Files.createTempDirectory("vs").toFile())

    @Test fun `writes parts at quiet points and keeps every byte`() {
        val s = store().newSession()
        val closed = mutableListOf<Int>()
        val w = ChunkWriter(s, sampleRate = 1000, maxChunkSec = 10, graceSec = 5) { closed += it }
        // 11 s speech, 0.5 s quiet, 8 s speech, fed in odd-sized buffers
        val audio = tone(11_000, 3000) + tone(500, 0) + tone(8_000, 3000)
        var off = 0
        val sizes = intArrayOf(333, 1001, 77, 4096)
        var k = 0
        while (off < audio.size) {
            val n = minOf(sizes[k++ % sizes.size], audio.size - off)
            w.write(audio.copyOfRange(off, off + n), n); off += n
        }
        val parts = w.close()
        assertEquals(2, parts)
        assertEquals(listOf(0, 1), closed)
        val total = (0 until parts).sumOf { s.partPcm(it).length() }
        assertEquals(audio.size.toLong(), total)
        // First part ends inside the quiet gap (11.0–11.5 s)
        val firstSec = s.partPcm(0).length() / 2.0 / 1000
        assertTrue("first part $firstSec s", firstSec in 11.0..11.6)
    }

    @Test fun `hard cut when never quiet`() {
        val s = store().newSession()
        val w = ChunkWriter(s, sampleRate = 1000, maxChunkSec = 10, graceSec = 2) {}
        val audio = tone(25_000, 3000)
        w.write(audio, audio.size)
        assertEquals(3, w.close())
        assertEquals(audio.size.toLong(), (0 until 3).sumOf { s.partPcm(it).length() })
    }

    @Test fun `joined transcript only when every part is done`() {
        val st = store()
        val s = st.newSession()
        val w = ChunkWriter(s, sampleRate = 1000, maxChunkSec = 1, graceSec = 0) {}
        val a = tone(2_500, 3000); w.write(a, a.size); w.close()
        assertEquals(3, s.partCount())
        s.writeTranscript(0, "one"); s.writeTranscript(2, "three")
        assertNull(s.joinedTranscript())
        assertEquals(listOf(1), s.missingParts())
        s.writeTranscript(1, " two ")
        assertEquals("one two three", s.joinedTranscript())
    }

    @Test fun `sessions survive and are listed until deleted`() {
        val st = store()
        val s1 = st.newSession(1000)
        val s2 = st.newSession(2000)
        s2.isRecording = false
        assertEquals(listOf(s1.id, s2.id), st.sessions().map { it.id })
        assertTrue(st.sessions()[0].isRecording)
        s1.delete()
        assertEquals(listOf(s2.id), st.sessions().map { it.id })
    }

    @Test fun `writes after close are ignored`() {
        val s = store().newSession()
        val w = ChunkWriter(s, sampleRate = 1000, maxChunkSec = 10) {}
        val a = tone(1000, 3000); w.write(a, a.size)
        assertEquals(1, w.close())
        w.write(a, a.size)
        assertEquals(1, s.partCount())
        assertEquals(1, w.close())
    }

    @Test fun `ring keeps only the latest audio`() {
        val r = PcmRing(10)
        r.write(byteArrayOf(1, 2, 3, 4, 5, 6), 6)
        r.write(byteArrayOf(7, 8, 9, 10, 11, 12), 6)
        assertArrayEquals(byteArrayOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 12), r.snapshot())
        assertEquals(12L, r.total)
    }
}
