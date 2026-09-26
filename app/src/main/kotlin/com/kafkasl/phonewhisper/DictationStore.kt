package com.kafkasl.phonewhisper

import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * Crash-safe storage for dictations of any length (pure java.io, unit-tested).
 *
 * Audio is written straight to disk in parts of ~5 minutes while you speak:
 *
 *   sessions/<timestamp>/part-000.pcm   raw 16 kHz mono PCM
 *   sessions/<timestamp>/part-000.txt   its transcript, written once Whisper returns
 *   sessions/<timestamp>/recording      present while still recording
 *   sessions/<timestamp>/final.txt      the final text, once complete
 *
 * A session is deleted only after every part has a transcript and the final text was
 * delivered. Anything else (network down, app killed, phone restarted) leaves it on disk,
 * so it can be retried later — nothing you said is thrown away.
 */
class DictationStore(private val root: File) {

    class Session(val dir: File) {
        val id: String get() = dir.name

        fun partPcm(i: Int) = File(dir, "part-%03d.pcm".format(i))
        fun partTxt(i: Int) = File(dir, "part-%03d.txt".format(i))
        private val recordingMarker get() = File(dir, "recording")
        val finalTxt get() = File(dir, "final.txt")

        fun partCount(): Int = dir.listFiles { f -> f.name.startsWith("part-") && f.name.endsWith(".pcm") }?.size ?: 0

        /** Parts whose audio exists but whose transcript is still missing. */
        fun missingParts(): List<Int> = (0 until partCount()).filter { !partTxt(it).exists() }

        fun transcriptOf(i: Int): String? = partTxt(i).takeIf { it.exists() }?.readText()

        /** Atomic write: a transcript file either exists complete or not at all. */
        fun writeTranscript(i: Int, text: String) {
            val tmp = File(dir, "part-%03d.txt.tmp".format(i))
            tmp.writeText(text)
            if (!tmp.renameTo(partTxt(i))) { partTxt(i).writeText(text); tmp.delete() }
        }

        /** Every part transcribed (in order), or null if any is missing. */
        fun joinedTranscript(): String? {
            val n = partCount()
            if (n == 0) return null
            val texts = (0 until n).map { transcriptOf(it) ?: return null }
            return texts.joinToString(" ") { it.trim() }.replace(Regex("\\s+"), " ").trim()
        }

        fun audioSeconds(sampleRate: Int = 16000): Long =
            (0 until partCount()).sumOf { partPcm(it).length() } / (2L * sampleRate)

        var isRecording: Boolean
            get() = recordingMarker.exists()
            set(v) { if (v) recordingMarker.writeText("1") else recordingMarker.delete() }

        fun delete() { dir.deleteRecursively() }
        fun exists() = dir.exists()
    }

    fun newSession(now: Long = System.currentTimeMillis()): Session {
        root.mkdirs()
        var dir = File(root, now.toString())
        var k = 1
        while (dir.exists()) dir = File(root, "${now}_${k++}")
        dir.mkdirs()
        return Session(dir).also { it.isRecording = true }
    }

    /** Sessions left on disk (unfinished or undelivered), oldest first. */
    fun sessions(): List<Session> =
        root.listFiles { f -> f.isDirectory }?.sortedBy { it.name }?.map { Session(it) } ?: emptyList()

    fun freeBytes(): Long = root.also { it.mkdirs() }.usableSpace
}

/**
 * Streams PCM into ~[maxChunkSec]-second part files. A part is closed at the first quiet
 * 100 ms after reaching the target length (so words aren't cut), or [graceSec] later at the
 * latest. [onPartClosed] fires with the part index as soon as a part is complete, so it can
 * be transcribed while you keep talking.
 */
class ChunkWriter(
    private val session: DictationStore.Session,
    private val sampleRate: Int = 16000,
    private val maxChunkSec: Int = 300,
    private val graceSec: Int = 10,
    private val quietThreshold: Double = 400.0,
    private val onPartClosed: (Int) -> Unit
) {
    private var index = 0
    private var out: FileOutputStream? = null
    private var partSamples = 0L
    private val win = sampleRate / 10
    private var winSum = 0L
    private var winCount = 0
    private var carry: Byte? = null           // odd byte from a previous write

    val partsClosed get() = index

    @Synchronized
    fun write(buf: ByteArray, n: Int) {
        if (out == null) out = FileOutputStream(session.partPcm(index))
        var i = 0
        var data = buf
        var len = n
        carry?.let { c ->                      // re-align to 16-bit samples
            data = ByteArray(n + 1); data[0] = c; System.arraycopy(buf, 0, data, 1, n); len = n + 1; carry = null
        }
        if (len % 2 != 0) { carry = data[len - 1]; len-- }
        var start = 0
        while (i + 1 < len) {
            val v = ((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xFF)).toShort().toInt()
            winSum += abs(v); winCount++; partSamples++
            i += 2
            if (winCount == win) {
                val quiet = winSum.toDouble() / win < quietThreshold
                winSum = 0; winCount = 0
                val longEnough = partSamples >= sampleRate.toLong() * maxChunkSec
                val tooLong = partSamples >= sampleRate.toLong() * (maxChunkSec + graceSec)
                if ((longEnough && quiet) || tooLong) {
                    out!!.write(data, start, i - start)
                    start = i
                    closePart()
                    out = FileOutputStream(session.partPcm(index))
                }
            }
        }
        if (len > start) out!!.write(data, start, len - start)
    }

    private fun closePart() {
        out?.flush(); out?.fd?.sync(); out?.close(); out = null
        val closed = index
        index++
        partSamples = 0
        onPartClosed(closed)
    }

    /** Closes the last part. Returns total number of parts. */
    @Synchronized
    fun close(): Int {
        if (out != null) {
            if (session.partPcm(index).length() == 0L && index > 0) {
                out?.close(); out = null; session.partPcm(index).delete()
            } else closePart()
        }
        return index
    }
}

/** Last few seconds of audio in memory, for the live preview (no growing buffers). */
class PcmRing(private val capacity: Int) {
    private val buf = ByteArray(capacity)
    private var pos = 0
    private var filled = 0
    var total = 0L; private set

    @Synchronized
    fun write(data: ByteArray, n: Int) {
        for (k in 0 until n) { buf[pos] = data[k]; pos = (pos + 1) % capacity }
        filled = minOf(capacity, filled + n)
        total += n
    }

    /** Snapshot of the buffered audio, oldest first, even length. */
    @Synchronized
    fun snapshot(): ByteArray {
        val len = filled - filled % 2
        val out = ByteArray(len)
        val start = (pos - filled + capacity) % capacity
        for (k in 0 until len) out[k] = buf[(start + k) % capacity]
        return out
    }
}
