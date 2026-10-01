package com.svifi.vitalyspeak

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
 *   sessions/<timestamp>/raw.txt        full transcript, written once EVERY part is transcribed
 *   sessions/<timestamp>/clean.txt      cleaned text, written only if cleanup fully succeeded
 *   sessions/<timestamp>/duration       seconds of audio (kept after the audio is deleted)
 *
 * Sessions double as the transcription history. Audio (part-*.pcm) is deleted only after
 * raw.txt exists — i.e. every part is verifiably transcribed — and only if the user doesn't
 * keep audio. Anything unfinished (network down, rate limit, app killed, phone restarted)
 * stays on disk and can be retried later: nothing you said is thrown away.
 */
class DictationStore(private val root: File) {

    class Session(val dir: File) {
        val id: String get() = dir.name

        fun partPcm(i: Int) = File(dir, "part-%03d.pcm".format(i))
        fun partTxt(i: Int) = File(dir, "part-%03d.txt".format(i))
        private val recordingMarker get() = File(dir, "recording")
        val rawTxt get() = File(dir, "raw.txt")
        val cleanTxt get() = File(dir, "clean.txt")
        private val durationFile get() = File(dir, "duration")

        enum class Status { RECORDING, NEEDS_TRANSCRIPTION, RAW_ONLY, DONE }

        val status: Status get() = when {
            isRecording -> Status.RECORDING
            !rawTxt.exists() -> Status.NEEDS_TRANSCRIPTION
            !cleanTxt.exists() -> Status.RAW_ONLY
            else -> Status.DONE
        }

        val startedAt: Long get() = id.substringBefore('_').toLongOrNull() ?: dir.lastModified()

        fun raw(): String? = rawTxt.takeIf { it.exists() }?.readText()
        fun clean(): String? = cleanTxt.takeIf { it.exists() }?.readText()
        fun saveRaw(text: String) { atomicWrite(rawTxt, text); saveDuration() }
        fun saveClean(text: String) = atomicWrite(cleanTxt, text)

        fun hasAudio() = partCount() > 0

        // --- inline commands: intervals the user marked, and timed words per part ---
        private val commandsFile get() = File(dir, "commands.tsv")
        fun partUnits(i: Int) = File(dir, "part-%03d.units.tsv".format(i))

        /** Appends a finished command interval (seconds from recording start). */
        fun addCommand(start: Double, end: Double) =
            commandsFile.appendText("%.3f\t%.3f\n".format(java.util.Locale.US, start, end))

        fun commands(): List<Pair<Double, Double>> = if (!commandsFile.exists()) emptyList() else
            commandsFile.readLines().mapNotNull { l ->
                val p = l.split('\t'); val a = p.getOrNull(0)?.toDoubleOrNull(); val b = p.getOrNull(1)?.toDoubleOrNull()
                if (a != null && b != null && b > a) a to b else null
            }

        fun writeUnits(i: Int, units: List<Dictation.Timed>) {
            partUnits(i).writeText(units.joinToString("") {
                "%.3f\t%.3f\t%s\n".format(java.util.Locale.US, it.start, it.end, it.text.replace('\t', ' ').replace('\n', ' '))
            })
        }

        fun units(i: Int): List<Dictation.Timed>? = partUnits(i).takeIf { it.exists() }?.readLines()?.mapNotNull { l ->
            val p = l.split('\t', limit = 3)
            if (p.size < 3) null else Dictation.Timed(p[0].toDoubleOrNull() ?: return@mapNotNull null, p[1].toDoubleOrNull() ?: 0.0, p[2])
        }

        /** Number of parts known (audio or transcript files) — still works after audio is deleted. */
        fun knownParts(): Int = dir.listFiles { f -> f.name.matches(Regex("part-\\d{3}\\.txt")) }?.size ?: 0

        /** Seconds of audio before part [i] (parts are contiguous). Needs the audio files. */
        fun partOffsetSec(i: Int, sampleRate: Int = 16000): Double =
            (0 until i).sumOf { partPcm(it).length() } / (2.0 * sampleRate)

        /** Deletes audio parts (transcripts stay). Only call once raw.txt exists. */
        fun deleteAudio() {
            check(rawTxt.exists()) { "refusing to delete audio before transcript is complete" }
            dir.listFiles { f -> f.name.endsWith(".pcm") }?.forEach { it.delete() }
        }

        fun saveDuration() { if (!durationFile.exists()) durationFile.writeText(audioSeconds().toString()) }
        fun durationSec(): Long = durationFile.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: audioSeconds()

        private fun atomicWrite(f: File, text: String) {
            val tmp = File(dir, f.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) { f.writeText(text); tmp.delete() }
        }

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
    private var closed = false

    val partsClosed get() = index

    @Synchronized
    fun write(buf: ByteArray, n: Int) {
        if (closed) return                     // late buffer after stop: never start a new part
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
        if (closed) return index
        closed = true
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
