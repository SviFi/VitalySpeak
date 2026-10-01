package com.kafkasl.phonewhisper

import kotlin.math.abs

/**
 * Pure helpers for reliable long dictation (unit-tested, no Android deps).
 * PCM = 16-bit little-endian mono.
 */
object Dictation {

    // ---------- audio ----------

    private fun sampleAt(pcm: ByteArray, i: Int): Int =
        ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xFF)).toShort().toInt()

    private fun meanAbs(pcm: ByteArray, fromSample: Int, toSample: Int): Double {
        var sum = 0L
        for (i in fromSample until toSample) sum += abs(sampleAt(pcm, i))
        return sum.toDouble() / maxOf(1, toSample - fromSample)
    }

    /**
     * Trims leading/trailing silence (keeps [padMs] of margin). Long trailing silence is the
     * main trigger for Whisper "hallucinating" text such as the vocabulary hint.
     */
    fun trimSilence(pcm: ByteArray, sampleRate: Int = 16000, threshold: Double = 350.0, padMs: Int = 400): ByteArray {
        val r = trimRange(pcm, sampleRate, threshold, padMs)
        return if (r.first == 0 && r.last + 1 == pcm.size) pcm else pcm.copyOfRange(r.first, r.last + 1)
    }

    /** Byte range kept by [trimSilence]; its start tells how much leading audio was cut. */
    fun trimRange(pcm: ByteArray, sampleRate: Int = 16000, threshold: Double = 350.0, padMs: Int = 400): IntRange {
        val n = pcm.size / 2
        val win = sampleRate / 50                    // 20 ms windows
        if (n < win * 5) return 0 until pcm.size
        var first = -1
        var last = -1
        var w = 0
        while (w + win <= n) {
            if (meanAbs(pcm, w, w + win) > threshold) { if (first < 0) first = w; last = w + win }
            w += win
        }
        if (first < 0) return 0 until pcm.size       // all quiet: let Whisper decide
        val pad = sampleRate * padMs / 1000
        val start = maxOf(0, first - pad)
        val end = minOf(n, last + pad)
        return (start * 2) until (end * 2)
    }

    /** True if no 20 ms window rises above [threshold] — nothing worth sending to Whisper. */
    fun isSilent(pcm: ByteArray, sampleRate: Int = 16000, threshold: Double = 350.0): Boolean {
        val n = pcm.size / 2
        val win = sampleRate / 50
        var w = 0
        while (w + win <= n) { if (meanAbs(pcm, w, w + win) > threshold) return false; w += win }
        return true
    }

    /**
     * Splits long audio into chunks of at most [maxChunkSec], cutting at the quietest 100 ms
     * within the last [searchSec] before each boundary so words aren't cut in half.
     * Returns byte ranges [start, end).
     */
    fun splitPcm(pcm: ByteArray, sampleRate: Int = 16000, maxChunkSec: Int = 300, searchSec: Int = 8): List<IntRange> {
        val n = pcm.size / 2
        val max = sampleRate * maxChunkSec
        if (n <= max) return listOf(0 until pcm.size)
        val out = mutableListOf<IntRange>()
        val win = sampleRate / 10
        var start = 0
        while (n - start > max) {
            val hardEnd = start + max
            val searchFrom = maxOf(start + win, hardEnd - sampleRate * searchSec)
            var best = hardEnd
            var bestE = Double.MAX_VALUE
            var s = searchFrom
            while (s + win <= hardEnd) {
                val e = meanAbs(pcm, s, s + win)
                if (e < bestE) { bestE = e; best = s + win / 2 }
                s += win / 2
            }
            out += (start * 2) until (best * 2)
            start = best
        }
        out += (start * 2) until pcm.size
        return out
    }

    // ---------- text ----------

    /** Terms from a comma/period/semicolon separated vocabulary string. */
    fun vocabTerms(vocab: String): List<String> =
        vocab.split(',', ';', '.', '\n').map { it.trim() }.filter { it.length >= 3 }

    /**
     * Removes a vocabulary hint that Whisper echoed back (typically at the end, after silence).
     * Only strips a trailing run of 2+ vocabulary terms, or the literal hint text, so a single
     * real mention of "DeskMe" in speech is kept.
     */
    fun stripVocabEcho(text: String, hints: List<String>): String {
        var t = text.trim()
        for (h in hints) {
            val hint = h.trim()
            if (hint.length > 10) t = t.replace(hint, "", ignoreCase = true).trim()
        }
        val terms = hints.flatMap(::vocabTerms).map { it.lowercase() }.toSet()
        if (terms.isEmpty()) return t
        // Walk back over trailing ", Term" pieces.
        val pieces = t.split(Regex("(?<=[,.;!?])\\s+")).toMutableList()
        var removed = 0
        while (pieces.isNotEmpty()) {
            val p = pieces.last().trim().trimEnd(',', '.', ';', '!', '?').trim().lowercase()
            if (p.isEmpty()) { pieces.removeAt(pieces.size - 1); continue }
            if (p in terms) { pieces.removeAt(pieces.size - 1); removed++ } else break
        }
        return if (removed >= 2) pieces.joinToString(" ").trim().trimEnd(',', ';').trim() else t
    }

    /** Splits text at sentence ends into pieces of at most ~[maxChars] (for chunked cleanup). */
    fun splitText(text: String, maxChars: Int = 3500): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val sentences = text.split(Regex("(?<=[.!?…])\\s+"))
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        for (s in sentences) {
            if (cur.isNotEmpty() && cur.length + s.length + 1 > maxChars) { out += cur.toString(); cur.clear() }
            if (s.length > maxChars) {
                // One enormous "sentence" (no punctuation): hard-wrap at spaces.
                var rest = s
                while (rest.length > maxChars) {
                    val cut = rest.lastIndexOf(' ', maxChars).takeIf { it > 0 } ?: maxChars
                    out += rest.substring(0, cut); rest = rest.substring(cut).trim()
                }
                cur.append(rest)
            } else {
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(s)
            }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    /**
     * Cleanup only removes fillers and fixes punctuation, so a result much shorter than the
     * input means something was lost (truncation, the model summarising) — keep the raw text.
     */
    fun cleanupLostContent(raw: String, cleaned: String): Boolean =
        raw.length >= 80 && cleaned.length < raw.length * 0.6

    // ---------- inline commands ----------

    /** A word or segment with absolute times (seconds from the start of the recording). */
    data class Timed(val start: Double, val end: Double, val text: String)

    data class Block(val isCommand: Boolean, val text: String)

    private fun endsSentence(t: String) = t.trimEnd().let { it.endsWith(".") || it.endsWith("?") || it.endsWith("!") || it.endsWith("…") }

    /**
     * Splits timed words/segments into ordered content and command blocks using the command
     * intervals the user marked while recording.
     *
     * Whisper's word timings drift (often 0.3–1 s) and people start talking as they tap, so a
     * mark rarely lines up with the first/last word exactly. Each command is therefore snapped
     * to natural boundaries: its start moves back to the start of the sentence it's in (if that
     * sentence began within [snapBack] s), its end forward to the end of that sentence (within
     * [snapForward] s). A pause of ≥ [pause] s also counts as a boundary.
     */
    fun splitByCommands(units: List<Timed>, commands: List<Pair<Double, Double>>,
                        lead: Double = 0.25, tail: Double = 0.35,
                        snapBack: Double = 2.5, snapForward: Double = 1.5, pause: Double = 0.6): List<Block> {
        val u = units.sortedBy { it.start }
        val isCmd = BooleanArray(u.size)
        for ((s, e) in commands) {
            val inside = u.indices.filter { val m = (u[it].start + u[it].end) / 2; m >= s - lead && m <= e + tail }
            if (inside.isEmpty()) continue
            var first = inside.first()
            var last = inside.last()
            // Snap start back to a sentence start / pause.
            var j = first
            while (j > 0 && !endsSentence(u[j - 1].text) && u[j].start - u[j - 1].end < pause && u[j - 1].start >= s - snapBack) j--
            val boundaryBefore = j == 0 || endsSentence(u[j - 1].text) || u[j].start - u[j - 1].end >= pause
            if (boundaryBefore) first = j
            // Snap end forward to a sentence end / pause.
            var k = last
            while (k < u.size - 1 && !endsSentence(u[k].text) && u[k + 1].start - u[k].end < pause && u[k + 1].end <= e + snapForward) k++
            val boundaryAfter = k == u.size - 1 || endsSentence(u[k].text) || u[k + 1].start - u[k].end >= pause
            if (boundaryAfter) last = k
            for (x in first..last) isCmd[x] = true
        }
        val out = mutableListOf<Block>()
        val cur = StringBuilder()
        var curCmd: Boolean? = null
        fun flush() {
            val t = cur.toString().replace(Regex("\\s+"), " ").trim()
            if (t.isNotEmpty() && curCmd != null) out += Block(curCmd!!, t)
            cur.clear()
        }
        for (i in u.indices) {
            if (curCmd != null && isCmd[i] != curCmd) flush()
            curCmd = isCmd[i]
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(u[i].text.trim())
        }
        flush()
        return out
    }

    /** Tagged input for the command-aware cleanup model. */
    fun toTagged(blocks: List<Block>): String {
        val sb = StringBuilder()
        var contentId = 0
        for (b in blocks) {
            if (b.isCommand) sb.append("<command after=\"$contentId\">").append(b.text).append("</command>\n")
            else { contentId++; sb.append("<content id=\"$contentId\">").append(b.text).append("</content>\n") }
        }
        return sb.toString().trim()
    }

    /** Human-readable raw transcript with commands marked, for History. */
    fun toMarked(blocks: List<Block>): String =
        blocks.joinToString("\n\n") { if (it.isCommand) "⟦command: ${it.text}⟧" else it.text }

    /**
     * For very long recordings: windows that each end right after a command (plus the trailing
     * content), so every command travels with the content it refers to.
     */
    fun windows(blocks: List<Block>, maxChars: Int = 10_000): List<List<Block>> {
        val out = mutableListOf<List<Block>>()
        var cur = mutableListOf<Block>()
        var size = 0
        for (b in blocks) {
            cur += b; size += b.text.length
            if (b.isCommand && size >= maxChars) { out += cur; cur = mutableListOf(); size = 0 }
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    /** Splits model output into (notes, pending-action lines) so windows can be merged. */
    fun splitPending(text: String): Pair<String, List<String>> {
        val m = Regex("(?im)^\\s*#*\\s*\\**pending actions\\**\\s*:?\\s*$").find(text) ?: return text.trim() to emptyList()
        val notes = text.substring(0, m.range.first).trim()
        val items = text.substring(m.range.last + 1).lines().map { it.trim() }.filter { it.isNotEmpty() }
        return notes to items
    }
}
