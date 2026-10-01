package com.svifi.vitalyspeak

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Small view toolkit shared by the app's screens. Screens are built in code (no XML
 * layouts) so the same helpers give every screen the same spacing, type and colours.
 */
object Ui {
    const val GREEN = 0xFF2E9E4F.toInt()
    const val RED = 0xFFD93025.toInt()
    const val AMBER = 0xFFE8A317.toInt()
    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    fun Context.dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    fun Context.themeColor(attr: Int): Int {
        val a = obtainStyledAttributes(intArrayOf(attr))
        return try { a.getColor(0, 0) } finally { a.recycle() }
    }

    fun Context.primary() = themeColor(com.google.android.material.R.attr.colorPrimary)
    fun Context.secondaryText() = themeColor(android.R.attr.textColorSecondary)

    fun Context.column(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun View.ripple() {
        val tv = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        setBackgroundResource(tv.resourceId)
    }

    /**
     * Puts [content] in a scrolling page and keeps it clear of the status and navigation
     * bars (Android 15+ always draws apps edge to edge).
     */
    fun page(activity: Activity, content: View): ScrollView {
        val scroll = ScrollView(activity).apply {
            setBackgroundColor(activity.themeColor(android.R.attr.colorBackground))
            isFillViewport = true
            clipToPadding = false
            addView(content)
        }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        activity.setContentView(scroll)
        return scroll
    }

    /** ← (same as the back gesture), optional logo, title. */
    fun topBar(activity: Activity, title: String, logo: Boolean = false): View = LinearLayout(activity).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(activity.dp(8), activity.dp(12), activity.dp(24), activity.dp(8))
        addView(ImageButton(activity).apply {
            setImageResource(R.drawable.ic_back)
            contentDescription = activity.getString(R.string.back)
            val tv = TypedValue()
            activity.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
            setBackgroundResource(tv.resourceId)
            setPadding(activity.dp(12), activity.dp(12), activity.dp(12), activity.dp(12))
            setOnClickListener { activity.finish() }
        })
        if (logo) addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_logo)
            layoutParams = LinearLayout.LayoutParams(activity.dp(38), activity.dp(38)).apply { marginEnd = activity.dp(12) }
        })
        addView(TextView(activity).apply {
            text = title
            textSize = 26f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            if (!logo) setPadding(activity.dp(8), 0, 0, 0)
        })
    }

    /** Section heading: tinted line icon and spaced capitals. */
    fun section(ctx: Context, title: String, icon: Int = 0): View = LinearLayout(ctx).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.dp(24), ctx.dp(28), ctx.dp(24), ctx.dp(6))
        val c = ctx.primary()
        if (icon != 0) addView(ImageView(ctx).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(c)
            layoutParams = LinearLayout.LayoutParams(ctx.dp(18), ctx.dp(18)).apply { marginEnd = ctx.dp(10) }
        })
        addView(TextView(ctx).apply {
            text = title.uppercase()
            textSize = 12f
            letterSpacing = 0.08f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(c)
        })
    }

    /** A settings row; keep [subtitle] to update it later. */
    class Row(val view: LinearLayout, val title: TextView, val subtitle: TextView)

    fun row(ctx: Context, title: String, subtitle: String = "", end: View? = null, onClick: (() -> Unit)? = null): Row {
        val t = TextView(ctx).apply { text = title; textSize = 17f; setTextColor(ctx.themeColor(android.R.attr.textColorPrimary)) }
        val s = TextView(ctx).apply {
            text = subtitle; textSize = 14f; setTextColor(ctx.secondaryText()); setPadding(0, ctx.dp(2), 0, 0)
            visibility = if (subtitle.isEmpty()) View.GONE else View.VISIBLE
        }
        val v = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(24), ctx.dp(14), ctx.dp(24), ctx.dp(14))
            addView(ctx.column().apply { addView(t); addView(s) }, LinearLayout.LayoutParams(0, WRAP, 1f))
            end?.let { addView(it) }
            if (onClick != null) { ripple(); setOnClickListener { onClick() } }
        }
        return Row(v, t, s)
    }

    fun setSubtitle(r: Row, text: CharSequence) {
        r.subtitle.text = text
        r.subtitle.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Row with a switch bound to a boolean setting. */
    fun switchRow(ctx: Context, title: String, subtitle: String, key: String, default: Boolean, changed: (Boolean) -> Unit = {}): Row {
        val p = Prefs.get(ctx)
        val sw = MaterialSwitch(ctx).apply { isChecked = p.getBoolean(key, default); isClickable = false; isFocusable = false }
        return row(ctx, title, subtitle, sw) {
            sw.isChecked = !sw.isChecked
            p.edit().putBoolean(key, sw.isChecked).apply()
            changed(sw.isChecked)
        }
    }

    /** Highlighted row for things that need attention (updates, retired models…). */
    fun alertRow(ctx: Context, title: String, subtitle: String, onClick: () -> Unit): View {
        val r = row(ctx, title, subtitle, onClick = onClick)
        val fg = ctx.themeColor(com.google.android.material.R.attr.colorOnPrimaryContainer)
        r.title.setTextColor(fg); r.subtitle.setTextColor(fg)
        return FrameLayout(ctx).apply {
            setBackgroundColor(ctx.themeColor(com.google.android.material.R.attr.colorPrimaryContainer))
            addView(r.view)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = ctx.dp(2) }
        }
    }

    /** Round ✓ / ! / ✕ status badge. */
    fun badge(ctx: Context) = TextView(ctx).apply {
        gravity = Gravity.CENTER
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(0xFFFFFFFF.toInt())
        layoutParams = LinearLayout.LayoutParams(ctx.dp(26), ctx.dp(26)).apply { marginStart = ctx.dp(12) }
    }

    enum class Level { OK, WARN, BAD }

    fun setBadge(b: TextView, level: Level) {
        b.text = when (level) { Level.OK -> "✓"; Level.WARN -> "!"; Level.BAD -> "✕" }
        b.contentDescription = b.context.getString(when (level) { Level.OK -> R.string.status_ok; Level.WARN -> R.string.status_recommended; Level.BAD -> R.string.status_missing })
        b.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(when (level) { Level.OK -> GREEN; Level.WARN -> AMBER; Level.BAD -> RED })
        }
    }

    fun dialog(ctx: Context) = MaterialAlertDialogBuilder(ctx)

    /** Text field padded for use inside a dialog. */
    fun input(ctx: Context, value: String, hint: String, multiLine: Boolean = false): Pair<View, EditText> {
        val e = EditText(ctx).apply {
            setText(value)
            this.hint = hint
            inputType = if (multiLine) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            if (multiLine) { minLines = 3; gravity = Gravity.TOP or Gravity.START }
        }
        val box = FrameLayout(ctx).apply { setPadding(ctx.dp(24), ctx.dp(8), ctx.dp(24), 0); addView(e) }
        return box to e
    }

    /** Rounded search field. */
    fun searchField(ctx: Context, hint: String, changed: (String) -> Unit): EditText = EditText(ctx).apply {
        this.hint = hint
        setSingleLine()
        textSize = 16f
        setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(16), ctx.dp(12))
        background = GradientDrawable().apply {
            cornerRadius = ctx.dp(24).toFloat()
            setColor(ctx.themeColor(com.google.android.material.R.attr.colorSurfaceContainerHigh))
        }
        addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = changed(s?.toString().orEmpty())
        })
    }

    /** Explanatory paragraph under a heading. */
    fun note(ctx: Context, text: CharSequence) = TextView(ctx).apply {
        this.text = text
        textSize = 14f
        setTextColor(ctx.secondaryText())
        setLineSpacing(0f, 1.15f)
        setPadding(ctx.dp(24), ctx.dp(4), ctx.dp(24), ctx.dp(8))
    }
}
