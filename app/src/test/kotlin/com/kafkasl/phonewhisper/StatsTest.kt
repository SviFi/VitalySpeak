package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StatsTest {
    private val day = 24L * 3600 * 1000

    @Test fun `summary totals streak and daily minutes`() {
        val st = Stats(File(Files.createTempDirectory("st").toFile(), "stats.tsv"))
        val now = System.currentTimeMillis()
        st.record(now - 2 * day, 120, 200)
        st.record(now - day, 60, 100)
        st.record(now, 180, 300)
        st.record(now - 10 * day, 30, 40)
        val s = st.summary(now, days = 14)
        assertEquals(4, s.sessions)
        assertEquals(390L, s.totalSeconds)
        assertEquals(640L, s.totalWords)
        assertEquals(180L, s.longestSeconds)
        assertEquals(3, s.streakDays)
        assertEquals(14, s.daily.size)
        assertEquals(3.0, s.daily.last(), 0.001)
        assertEquals(98, s.wordsPerMinute)
    }

    @Test fun `word count ignores punctuation`() {
        assertEquals(4, Stats.wordCount("Привет, это — pull request!"))
    }

    @Test fun `empty stats`() {
        val s = Stats(File(Files.createTempDirectory("st").toFile(), "none.tsv")).summary()
        assertEquals(0, s.sessions); assertEquals(0, s.streakDays)
    }
}
