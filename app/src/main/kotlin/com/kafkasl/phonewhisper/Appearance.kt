package com.kafkasl.phonewhisper

import android.content.SharedPreferences

/** All visual settings in one place, with their defaults, so "Reset to defaults" is exact. */
object Appearance {
    const val KEY_REACTION = "voice_reaction"   // 0..100
    const val KEY_DOT_SIZE = "dot_size"         // percent 60..160
    const val KEY_WAVE_SPEED = "wave_speed"     // percent 100..500
    const val KEY_WAVE_COUNT = "wave_count"     // percent 100..300
    const val KEY_COLOR_FRONT = "color_front"   // recording blob, top layer
    const val KEY_COLOR_MIDDLE = "color_middle" // middle layer
    const val KEY_COLOR_BACK = "color_back"     // back layer
    const val KEY_COLOR_RING = "color_ring"     // ring around the idle dot + preview outline

    const val DEF_REACTION = 70
    const val DEF_DOT_SIZE = 100
    const val DEF_WAVE_SPEED = 200
    const val DEF_WAVE_COUNT = 200
    const val DEF_COLOR_FRONT = 0xF2EF4444.toInt()   // red
    const val DEF_COLOR_MIDDLE = 0xE6F4F4F6.toInt()  // near-white
    const val DEF_COLOR_BACK = 0xF0141416.toInt()    // near-black
    const val DEF_COLOR_RING = 0xFFFF7A1A.toInt()    // orange

    val ALL_KEYS = listOf(KEY_REACTION, KEY_DOT_SIZE, KEY_WAVE_SPEED, KEY_WAVE_COUNT,
        KEY_COLOR_FRONT, KEY_COLOR_MIDDLE, KEY_COLOR_BACK, KEY_COLOR_RING)

    fun front(p: SharedPreferences) = p.getInt(KEY_COLOR_FRONT, DEF_COLOR_FRONT)
    fun middle(p: SharedPreferences) = p.getInt(KEY_COLOR_MIDDLE, DEF_COLOR_MIDDLE)
    fun back(p: SharedPreferences) = p.getInt(KEY_COLOR_BACK, DEF_COLOR_BACK)
    fun ring(p: SharedPreferences) = p.getInt(KEY_COLOR_RING, DEF_COLOR_RING)

    fun resetDefaults(p: SharedPreferences) {
        val e = p.edit()
        ALL_KEYS.forEach { e.remove(it) }
        e.apply()
    }

    /** Swatches offered in the color picker (plus free hex entry). */
    val PALETTE = intArrayOf(
        0xFFEF4444.toInt(), 0xFFFF7A1A.toInt(), 0xFFFFC928.toInt(), 0xFF22C55E.toInt(),
        0xFF14B8A6.toInt(), 0xFF3B82F6.toInt(), 0xFF8B5CF6.toInt(), 0xFFEC4899.toInt(),
        0xFFF4F4F6.toInt(), 0xFF9CA3AF.toInt(), 0xFF3A3A3C.toInt(), 0xFF141416.toInt()
    )
}
