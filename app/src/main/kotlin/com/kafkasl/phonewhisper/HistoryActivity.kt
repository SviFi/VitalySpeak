package com.kafkasl.phonewhisper

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.text.DateFormat
import java.util.Date

/**
 * Every dictation, newest first: cleaned text, raw transcript, and status.
 *  - "Needs transcription": audio is saved but some part isn't transcribed yet → Retry.
 *  - "Raw only": cleanup failed or was off → Retry cleanup.
 * Text is kept until you delete the entry; audio only if "Keep audio" is on or unfinished.
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(Ui.topBar(this, "History") { finish() })
        root.addView(TextView(this).apply {
            text = "All your dictations. Tap one to copy or retry."
            textSize = 14f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(dp(24), 0, dp(24), dp(12))
        })
        empty = TextView(this).apply {
            text = "No dictations yet."
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setTextColor(attrColor(android.R.attr.textColorSecondary))
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(empty)
        root.addView(list)
        setContentView(ScrollView(this).apply {
            setBackgroundColor(attrColor(android.R.attr.colorBackground))
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        WhisperAccessibilityService.instance?.onHistoryChanged = { render() }
        render()
    }

    override fun onPause() {
        super.onPause()
        WhisperAccessibilityService.instance?.onHistoryChanged = null
    }

    private fun svc() = WhisperAccessibilityService.instance

    private fun render() {
        list.removeAllViews()
        val items = svc()?.history().orEmpty()
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        if (svc() == null) empty.text = "Enable the VitalySpeak accessibility service to see history."
        for (s in items) list.addView(row(s))
    }

    private fun statusLabel(s: DictationStore.Session): Pair<String, Int> = when (s.status) {
        DictationStore.Session.Status.DONE -> "Done" to 0xFF2E7D32.toInt()
        DictationStore.Session.Status.RAW_ONLY -> "Raw only" to 0xFFB26A00.toInt()
        DictationStore.Session.Status.NEEDS_TRANSCRIPTION ->
            "Needs transcription · ${s.partCount() - s.missingParts().size}/${s.partCount()}" to 0xFFC62828.toInt()
        DictationStore.Session.Status.RECORDING -> "Recording" to 0xFFC62828.toInt()
    }

    private fun meta(s: DictationStore.Session): String {
        val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(s.startedAt))
        val sec = s.durationSec()
        val dur = if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60) else "%d:%02d".format(sec / 60, sec % 60)
        return "$date · $dur" + if (s.hasAudio()) " · audio saved" else ""
    }

    private fun row(s: DictationStore.Session): View {
        val (label, color) = statusLabel(s)
        val text = s.clean() ?: s.raw() ?: "(not transcribed yet)"
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(14), dp(24), dp(14))
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)
            isClickable = true
            setOnClickListener { detail(s) }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    this.text = meta(s)
                    textSize = 13f
                    setTextColor(attrColor(android.R.attr.textColorSecondary))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(context).apply {
                    this.text = label
                    textSize = 12f
                    setTextColor(0xFFFFFFFF.toInt())
                    setPadding(dp(8), dp(2), dp(8), dp(2))
                    background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(color) }
                })
            })
            addView(TextView(context).apply {
                this.text = text.replace("\n", " ")
                textSize = 16f
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(attrColor(android.R.attr.textColorPrimary))
                setPadding(0, dp(6), 0, 0)
            })
        }
    }

    private fun detail(s: DictationStore.Session) {
        val clean = s.clean()
        val raw = s.raw()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        fun section(title: String, content: String?) {
            body.addView(TextView(this).apply {
                text = title
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(10), 0, dp(4))
            })
            body.addView(TextView(this).apply {
                text = content ?: "—"
                setTextIsSelectable(true)
                textSize = 15f
            })
        }
        section("Cleaned", clean ?: if (raw != null) "Cleanup missing — tap Retry cleanup" else null)
        section("Raw transcript", raw ?: if (s.missingParts().isNotEmpty())
            "${s.missingParts().size} of ${s.partCount()} part(s) not transcribed yet. Audio is saved — tap Retry." else null)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, 0) }
        body.addView(buttons)
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle(meta(s))
            .setView(ScrollView(this).apply { addView(body) })
            .setNegativeButton("Close", null)
            .create()
        fun btn(label: String, action: () -> Unit) = buttons.addView(MaterialButton(this).apply {
            text = label
            setOnClickListener { action(); dialog.dismiss() }
        })
        if (clean != null) btn("Copy cleaned text") { copy(clean) }
        if (raw != null) btn("Copy raw transcript") { copy(raw) }
        if (raw == null) btn("Retry transcription") {
            svc()?.retryTranscription(s.id); toast("Transcribing in background…")
        }
        if (raw != null) btn(if (clean == null) "Retry cleanup" else "Redo cleanup") {
            svc()?.retryCleanup(s.id); toast("Cleaning up in background…")
        }
        btn("Delete") {
            android.app.AlertDialog.Builder(this)
                .setMessage(if (s.hasAudio()) "Delete this dictation and its saved audio?" else "Delete this dictation?")
                .setPositiveButton("Delete") { _, _ -> svc()?.deleteSession(s.id) }
                .setNegativeButton("Keep", null).show()
        }
        dialog.show()
    }

    private fun copy(t: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("VitalySpeak", t))
        toast("Copied (${t.length} chars)")
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun attrColor(attr: Int): Int {
        val ta = obtainStyledAttributes(intArrayOf(attr))
        val c = ta.getColor(0, 0); ta.recycle(); return c
    }
}
