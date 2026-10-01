package com.kafkasl.phonewhisper

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/** Visual customisation: dot size, animation, colours, and reset to defaults. */
class AppearanceActivity : AppCompatActivity() {

    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(32)) }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(attrColor(android.R.attr.colorBackground))
            addView(content)
        })
        build()
    }

    private fun build() {
        content.removeAllViews()
        content.addView(Ui.topBar(this, "Appearance") { finish() })

        content.addView(Ui.section(this, "Dot", R.drawable.ic_sec_dictation))
        content.addView(slider("Dot size", "Size of the floating mic dot",
            Appearance.KEY_DOT_SIZE, Appearance.DEF_DOT_SIZE, 60, 160) { "$it%" })

        content.addView(Ui.section(this, "Recording animation", R.drawable.ic_sec_usage))
        content.addView(slider("Voice reaction", "How strongly the shape reacts to your voice",
            Appearance.KEY_REACTION, Appearance.DEF_REACTION, 0, 100) { v -> if (v < 34) "Calm" else if (v < 67) "Lively" else "Wild" })
        content.addView(slider("Wave speed", "How fast the shape moves",
            Appearance.KEY_WAVE_SPEED, Appearance.DEF_WAVE_SPEED, 100, 500) { "%.1f×".format(it / 100f) })
        content.addView(slider("Wave count", "How many ripples around the shape",
            Appearance.KEY_WAVE_COUNT, Appearance.DEF_WAVE_COUNT, 100, 300) { "%.1f×".format(it / 100f) })

        content.addView(Ui.section(this, "Colours", R.drawable.ic_sec_appearance))
        content.addView(colorRow("Front shape", "Top layer while recording", Appearance.KEY_COLOR_FRONT, Appearance.DEF_COLOR_FRONT))
        content.addView(colorRow("Middle shape", "Second layer", Appearance.KEY_COLOR_MIDDLE, Appearance.DEF_COLOR_MIDDLE))
        content.addView(colorRow("Back shape", "Third layer", Appearance.KEY_COLOR_BACK, Appearance.DEF_COLOR_BACK))
        content.addView(colorRow("Accent ring", "Ring around the idle dot and preview bubble", Appearance.KEY_COLOR_RING, Appearance.DEF_COLOR_RING))

        content.addView(LinearLayout(this).apply {
            setPadding(dp(24), dp(28), dp(24), dp(8))
            addView(MaterialButton(this@AppearanceActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "Reset to defaults"
                setOnClickListener {
                    android.app.AlertDialog.Builder(this@AppearanceActivity)
                        .setMessage("Reset size, animation and colours to the defaults?")
                        .setPositiveButton("Reset") { _, _ ->
                            Appearance.resetDefaults(prefs())
                            apply()
                            build()
                            Toast.makeText(this@AppearanceActivity, "Defaults restored", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("Cancel", null).show()
                }
            })
        })
    }

    private fun apply() = WhisperAccessibilityService.instance?.applyAppearanceSettings()

    private fun slider(title: String, subtitle: String, key: String, def: Int, min: Int, max: Int, label: (Int) -> String): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(12), dp(24), dp(6)) }
        val current = prefs().getInt(key, def).coerceIn(min, max)
        val value = TextView(this).apply { text = label(current); textSize = 14f; setTextColor(attrColor(com.google.android.material.R.attr.colorPrimary)) }
        row.addView(LinearLayout(this).apply {
            addView(TextView(context).apply { text = title; textSize = 17f; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
            addView(value)
        })
        row.addView(TextView(this).apply { text = subtitle; textSize = 13f; setTextColor(attrColor(android.R.attr.textColorSecondary)) })
        row.addView(SeekBar(this).apply {
            this.max = max - min
            progress = current - min
            setPadding(dp(4), dp(12), dp(4), dp(4))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, u: Boolean) { value.text = label(p + min) }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) { prefs().edit().putInt(key, sb.progress + min).apply(); apply() }
            })
        })
        return row
    }

    private fun swatch(color: Int, sizeDp: Int) = View(this).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(color); setStroke(dp(1), 0x33888888)
        }
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun colorRow(title: String, subtitle: String, key: String, def: Int): View {
        val color = prefs().getInt(key, def)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(14), dp(24), dp(14))
            val tv = TypedValue(); context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
            setOnClickListener { pickColor(title, key, def) }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                addView(TextView(context).apply { text = title; textSize = 17f })
                addView(TextView(context).apply { text = subtitle; textSize = 13f; setTextColor(attrColor(android.R.attr.textColorSecondary)) })
            })
            addView(swatch(color, 32))
        }
    }

    private fun pickColor(title: String, key: String, def: Int) {
        val grid = GridLayout(this).apply { columnCount = 6; setPadding(dp(20), dp(12), dp(20), dp(4)) }
        val hex = EditText(this).apply {
            hint = "#RRGGBB"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText("#%06X".format(prefs().getInt(key, def) and 0xFFFFFF))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(grid)
            addView(hex, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(24), dp(8), dp(24), 0) })
        }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Use hex") { _, _ ->
                val c = try { android.graphics.Color.parseColor(hex.text.toString().trim().let { if (it.startsWith("#")) it else "#$it" }) } catch (_: Exception) { null }
                if (c != null) save(key, c) else Toast.makeText(this, "Not a colour", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Default") { _, _ -> prefs().edit().remove(key).apply(); apply(); build() }
            .setNegativeButton("Cancel", null)
            .create()
        for (c in Appearance.PALETTE) {
            grid.addView(swatch(c, 36).apply {
                layoutParams = GridLayout.LayoutParams().apply { width = dp(36); height = dp(36); setMargins(dp(6), dp(6), dp(6), dp(6)) }
                setOnClickListener { save(key, c); dialog.dismiss() }
            })
        }
        dialog.show()
    }

    private fun save(key: String, color: Int) {
        // Keep a little translucency for the layered shapes so they blend softly.
        val alpha = if (key == Appearance.KEY_COLOR_RING) 0xFF else 0xF0
        prefs().edit().putInt(key, (color and 0xFFFFFF) or (alpha shl 24)).apply()
        apply(); build()
    }

    private fun prefs() = getSharedPreferences(Groq.PREFS, MODE_PRIVATE)
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun attrColor(a: Int): Int { val t = obtainStyledAttributes(intArrayOf(a)); val c = t.getColor(0, 0); t.recycle(); return c }
}

/** Shared bits for the app's screens: top bar with back arrow + logo, section headers. */
object Ui {
    fun dp(ctx: android.content.Context, n: Int) = (n * ctx.resources.displayMetrics.density).toInt()

    private fun attrColor(ctx: android.content.Context, a: Int): Int {
        val t = ctx.obtainStyledAttributes(intArrayOf(a)); val c = t.getColor(0, 0); t.recycle(); return c
    }

    /** ← back arrow (same as the back gesture), optional logo, title. */
    fun topBar(ctx: android.content.Context, title: String, showLogo: Boolean = false, onBack: () -> Unit): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 8), dp(ctx, 40), dp(ctx, 24), dp(ctx, 12))
            addView(ImageButton(ctx).apply {
                setImageResource(R.drawable.ic_back)
                contentDescription = "Back"
                val tv = TypedValue(); ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
                setBackgroundResource(tv.resourceId)
                setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12))
                setOnClickListener { onBack() }
            })
            if (showLogo) addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_logo)
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 40), dp(ctx, 40)).apply { marginStart = dp(ctx, 4); marginEnd = dp(ctx, 12) }
            })
            addView(TextView(ctx).apply {
                text = title
                textSize = 26f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(if (showLogo) 0 else dp(ctx, 8), 0, 0, 0)
            })
        }

    /** Section header: small tinted line icon + spaced uppercase title. */
    fun section(ctx: android.content.Context, title: String, icon: Int = 0): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(ctx, 24), dp(ctx, 28), dp(ctx, 24), dp(ctx, 6))
        val color = attrColor(ctx, com.google.android.material.R.attr.colorPrimary)
        if (icon != 0) addView(ImageView(ctx).apply {
            setImageResource(icon)
            imageTintList = android.content.res.ColorStateList.valueOf(color)
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 18), dp(ctx, 18)).apply { marginEnd = dp(ctx, 10) }
        })
        addView(TextView(ctx).apply {
            text = title.uppercase()
            textSize = 12f
            letterSpacing = 0.08f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(color)
        })
    }
}
