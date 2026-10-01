package com.svifi.vitalyspeak

import java.io.File
import java.util.Calendar

/**
 * Lifetime dictation statistics, kept in their own small file (stats.tsv: time, seconds,
 * words per line) so deleting history entries doesn't erase them. Pure java.io, unit-tested.
 */
class Stats(private val file: File) {

    data class Entry(val time: Long, val seconds: Long, val words: Int)

    data class Summary(
        val sessions: Int,
        val totalSeconds: Long,
        val totalWords: Long,
        val longestSeconds: Long,
        val averageSeconds: Long,
        val streakDays: Int,
        val wordsPerMinute: Int,
        /** Minutes per day, oldest first, the last [days] days including today. */
        val daily: List<Double>
    )

    @Synchronized
    fun record(time: Long, seconds: Long, words: Int) {
        file.parentFile?.mkdirs()
        file.appendText("$time\t$seconds\t$words\n")
    }

    fun exists() = file.exists()

    fun load(): List<Entry> = if (!file.exists()) emptyList() else file.readLines().mapNotNull { l ->
        val p = l.split('\t')
        if (p.size < 3) null else Entry(p[0].toLongOrNull() ?: return@mapNotNull null,
            p[1].toLongOrNull() ?: 0, p[2].toIntOrNull() ?: 0)
    }

    fun summary(now: Long = System.currentTimeMillis(), days: Int = 14): Summary {
        val e = load()
        val total = e.sumOf { it.seconds }
        val words = e.sumOf { it.words.toLong() }
        fun dayIndex(t: Long): Long {
            val c = Calendar.getInstance().apply { timeInMillis = t }
            return c.get(Calendar.YEAR) * 400L + c.get(Calendar.DAY_OF_YEAR)
        }
        val byDay = e.groupBy { dayIndex(it.time) }
        // Consecutive days with dictation, ending today (or yesterday, so a streak survives until you dictate today).
        var streak = 0
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        if (dayIndex(now) !in byDay) cal.add(Calendar.DAY_OF_YEAR, -1)
        while (dayIndex(cal.timeInMillis) in byDay) { streak++; cal.add(Calendar.DAY_OF_YEAR, -1) }
        val daily = (days - 1 downTo 0).map { back ->
            val c = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.DAY_OF_YEAR, -back) }
            (byDay[dayIndex(c.timeInMillis)]?.sumOf { it.seconds } ?: 0L) / 60.0
        }
        return Summary(
            sessions = e.size,
            totalSeconds = total,
            totalWords = words,
            longestSeconds = e.maxOfOrNull { it.seconds } ?: 0,
            averageSeconds = if (e.isEmpty()) 0 else total / e.size,
            streakDays = streak,
            wordsPerMinute = if (total >= 30) (words * 60 / total).toInt() else 0,
            daily = daily
        )
    }

    companion object {
        fun wordCount(text: String) = text.split(Regex("\\s+")).count { it.any(Char::isLetterOrDigit) }

        fun formatDuration(sec: Long): String = when {
            sec >= 3600 -> "${sec / 3600}h ${sec / 60 % 60}m"
            sec >= 60 -> "${sec / 60}m ${sec % 60}s"
            else -> "${sec}s"
        }
    }
}
