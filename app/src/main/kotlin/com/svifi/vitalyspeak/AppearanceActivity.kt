package com.svifi.vitalyspeak

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.svifi.vitalyspeak.Ui.column
import com.svifi.vitalyspeak.Ui.dp
import com.svifi.vitalyspeak.Ui.primary
import com.svifi.vitalyspeak.Ui.secondaryText

/** Look of the floating dot: size, animation, colours, reset. */
class AppearanceActivity : AppCompatActivity() {

    private lateinit var content: LinearLayout
    private val p by lazy { Prefs.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = column().apply { setPadding(0, 0, 0, dp(32)) }
        Ui.page(this, content)
        build()
    }

    private fun changed() { VoiceService.instance?.onLookChanged() }

    private fun build() {
        content.removeAllViews()
        content.addView(Ui.topBar(this, getString(R.string.sec_appearance)))

        content.addView(Ui.section(this, getString(R.string.look_dot), R.drawable.ic_sec_dictation))
        content.addView(slider(R.string.dot_size, R.string.dot_size_sub, Appearance.KEY_DOT_SIZE, Appearance.DEF_DOT_SIZE, 60, 160) { "$it%" })

        content.addView(Ui.section(this, getString(R.string.look_animation), R.drawable.ic_sec_usage))
        content.addView(slider(R.string.reaction, R.string.reaction_sub, Appearance.KEY_REACTION, Appearance.DEF_REACTION, 0, 100) {
            getString(when { it < 34 -> R.string.reaction_calm; it < 67 -> R.string.reaction_lively; else -> R.string.reaction_wild })
        })
        content.addView(slider(R.string.wave_speed, R.string.wave_speed_sub, Appearance.KEY_WAVE_SPEED, Appearance.DEF_WAVE_SPEED, 100, 500) { "%.1f×".format(it / 100f) })
        content.addView(slider(R.string.wave_count, R.string.wave_count_sub, Appearance.KEY_WAVE_COUNT, Appearance.DEF_WAVE_COUNT, 100, 300) { "%.1f×".format(it / 100f) })

        content.addView(Ui.section(this, getString(R.string.look_colours), R.drawable.ic_sec_appearance))
        content.addView(colour(R.string.colour_front, R.string.colour_front_sub, Appearance.KEY_COLOR_FRONT, Appearance.DEF_COLOR_FRONT))
        content.addView(colour(R.string.colour_middle, R.string.colour_middle_sub, Appearance.KEY_COLOR_MIDDLE, Appearance.DEF_COLOR_MIDDLE))
        content.addView(colour(R.string.colour_back, R.string.colour_back_sub, Appearance.KEY_COLOR_BACK, Appearance.DEF_COLOR_BACK))
        content.addView(colour(R.string.colour_ring, R.string.colour_ring_sub, Appearance.KEY_COLOR_RING, Appearance.DEF_COLOR_RING))

        content.addView(LinearLayout(this).apply {
            setPadding(dp(24), dp(28), dp(24), dp(8))
            addView(MaterialButton(this@AppearanceActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = getString(R.string.reset_defaults)
                setOnClickListener {
                    Ui.dialog(this@AppearanceActivity).setMessage(R.string.reset_confirm)
                        .setPositiveButton(R.string.reset) { _, _ -> Appearance.resetDefaults(p); changed(); build() }
                        .setNegativeButton(android.R.string.cancel, null).show()
                }
            })
        })
    }

    private fun slider(title: Int, sub: Int, key: String, def: Int, min: Int, max: Int, label: (Int) -> String): View {
        val now = p.getInt(key, def).coerceIn(min, max)
        val value = TextView(this).apply { text = label(now); textSize = 14f; setTextColor(primary()) }
        return column().apply {
            setPadding(dp(24), dp(12), dp(24), dp(6))
            addView(LinearLayout(context).apply {
                addView(TextView(context).apply { text = getString(title); textSize = 17f }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
                addView(value)
            })
            addView(TextView(context).apply { text = getString(sub); textSize = 13f; setTextColor(secondaryText()) })
            addView(SeekBar(context).apply {
                this.max = max - min
                progress = now - min
                setPadding(dp(4), dp(12), dp(4), dp(4))
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, v: Int, user: Boolean) { value.text = label(v + min) }
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) { p.edit().putInt(key, s.progress + min).apply(); changed() }
                })
            })
        }
    }

    private fun dot(colour: Int, size: Int) = View(this).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(colour); setStroke(dp(1), 0x33888888) }
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    private fun colour(title: Int, sub: Int, key: String, def: Int): View =
        Ui.row(this, getString(title), getString(sub), dot(p.getInt(key, def), 32)) { pick(getString(title), key, def) }.view

    private fun pick(title: String, key: String, def: Int) {
        val grid = GridLayout(this).apply { columnCount = 6; setPadding(dp(20), dp(12), dp(20), dp(4)) }
        val (hexBox, hex) = Ui.input(this, "#%06X".format(p.getInt(key, def) and 0xFFFFFF), "#RRGGBB")
        val box = column().apply { addView(grid); addView(hexBox) }
        val d = Ui.dialog(this).setTitle(title).setView(box)
            .setPositiveButton(R.string.use) { _, _ ->
                val c = try { Color.parseColor(hex.text.toString().trim().let { if (it.startsWith("#")) it else "#$it" }) } catch (_: Exception) { null }
                if (c != null) save(key, c) else Toast.makeText(this, R.string.not_a_colour, Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(R.string.default_value) { _, _ -> p.edit().remove(key).apply(); changed(); build() }
            .setNegativeButton(android.R.string.cancel, null).create()
        for (c in Appearance.PALETTE) grid.addView(dot(c, 36).apply {
            layoutParams = GridLayout.LayoutParams().apply { width = dp(36); height = dp(36); setMargins(dp(6), dp(6), dp(6), dp(6)) }
            setOnClickListener { save(key, c); d.dismiss() }
        })
        d.show()
    }

    private fun save(key: String, colour: Int) {
        // Layered shapes stay slightly translucent so they blend; the ring is solid.
        val alpha = if (key == Appearance.KEY_COLOR_RING) 0xFF else 0xF0
        p.edit().putInt(key, (colour and 0xFFFFFF) or (alpha shl 24)).apply()
        changed(); build()
    }
}
