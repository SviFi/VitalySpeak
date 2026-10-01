package com.svifi.vitalyspeak

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableString
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.svifi.vitalyspeak.Ui.column
import com.svifi.vitalyspeak.Ui.dp
import com.svifi.vitalyspeak.Ui.primary
import com.svifi.vitalyspeak.Ui.secondaryText
import java.text.DateFormat
import java.util.Date

/** All dictations, newest first, searchable; open one to copy, retry or delete it. */
class HistoryActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private lateinit var empty: TextView
    private lateinit var search: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = column().apply { setPadding(0, 0, 0, dp(24)) }
        root.addView(Ui.topBar(this, getString(R.string.sec_history)))
        search = Ui.searchField(this, getString(R.string.history_search)) { render() }
        root.addView(search, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { setMargins(dp(16), dp(4), dp(16), dp(8)) })
        empty = Ui.note(this, "").apply { setPadding(dp(24), dp(24), dp(24), dp(24)) }
        list = column()
        root.addView(empty); root.addView(list)
        Ui.page(this, root)
    }

    override fun onResume() {
        super.onResume()
        svc()?.onHistoryChanged = { render() }
        render()
    }

    override fun onPause() {
        super.onPause()
        svc()?.onHistoryChanged = null
    }

    private fun svc() = VoiceService.instance
    private fun toast(id: Int, vararg a: Any) = Toast.makeText(this, getString(id, *a), Toast.LENGTH_SHORT).show()

    private fun render() {
        list.removeAllViews()
        val all = svc()?.history().orEmpty()
        val q = search.text.toString().trim()
        // Every word must appear somewhere in the cleaned or raw text, in any order.
        val words = q.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val shown = if (words.isEmpty()) all else all.filter { s ->
            val text = ((s.clean() ?: "") + "\n" + (s.raw() ?: "")).lowercase()
            words.all { it in text }
        }
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        empty.text = when {
            svc() == null -> getString(R.string.history_needs_service)
            all.isEmpty() -> getString(R.string.history_empty)
            else -> getString(R.string.history_no_match, q)
        }
        shown.forEach { list.addView(item(it, words)) }
    }

    private fun badge(s: DictationStore.Session): Pair<String, Int> = when (s.status) {
        DictationStore.Session.Status.DONE -> getString(R.string.state_done) to 0xFF2E7D32.toInt()
        DictationStore.Session.Status.RAW_ONLY -> getString(R.string.state_raw) to 0xFFB26A00.toInt()
        DictationStore.Session.Status.NEEDS_TRANSCRIPTION ->
            getString(R.string.state_pending, s.partCount() - s.missingParts().size, s.partCount()) to 0xFFC62828.toInt()
        DictationStore.Session.Status.RECORDING -> getString(R.string.state_recording) to 0xFFC62828.toInt()
    }

    private fun meta(s: DictationStore.Session): String {
        val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(s.startedAt))
        val sec = s.durationSec()
        val len = if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60) else "%d:%02d".format(sec / 60, sec % 60)
        return "$date · $len" + if (s.hasAudio()) " · " + getString(R.string.audio_saved) else ""
    }

    private fun item(s: DictationStore.Session, words: List<String>): View {
        val (label, colour) = badge(s)
        val full = (s.clean() ?: s.raw() ?: getString(R.string.not_transcribed)).replace('\n', ' ')
        val text: CharSequence = if (words.isEmpty()) full else mark(around(full, words.first()), words)
        val head = LinearLayout(this).apply {
            addView(TextView(context).apply { this.text = meta(s); textSize = 13f; setTextColor(secondaryText()) }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            addView(TextView(context).apply {
                this.text = label; textSize = 12f; setTextColor(0xFFFFFFFF.toInt())
                setPadding(dp(8), dp(2), dp(8), dp(2))
                background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(colour) }
            })
        }
        val body = TextView(this).apply {
            this.text = text; textSize = 16f; maxLines = 3; ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(6), 0, 0)
        }
        val r = Ui.row(this, "", "") { open(s) }
        r.title.visibility = View.GONE
        (r.title.parent as LinearLayout).apply { addView(head, 0); addView(body) }
        return r.view
    }

    private fun open(s: DictationStore.Session) {
        val clean = s.clean()
        val raw = s.raw()
        val box = column().apply { setPadding(dp(24), dp(8), dp(24), dp(8)) }
        fun part(title: Int, value: String?) {
            box.addView(TextView(this).apply { text = getString(title); setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(10), 0, dp(4)) })
            box.addView(TextView(this).apply { text = value ?: "—"; setTextIsSelectable(true); textSize = 15f })
        }
        part(R.string.cleaned, clean ?: if (raw != null) getString(R.string.cleaned_missing) else null)
        part(R.string.raw, raw ?: if (s.missingParts().isNotEmpty()) getString(R.string.raw_missing, s.missingParts().size, s.partCount()) else null)
        val buttons = column().apply { setPadding(0, dp(12), 0, 0) }
        box.addView(buttons)
        val d = Ui.dialog(this).setTitle(meta(s)).setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton(R.string.close, null).create()
        fun button(label: Int, action: () -> Unit) = buttons.addView(MaterialButton(this).apply {
            text = getString(label); setOnClickListener { action(); d.dismiss() }
        })
        clean?.let { c -> button(R.string.copy_cleaned) { copy(c) } }
        raw?.let { r -> button(R.string.copy_raw) { copy(r) } }
        if (raw == null) button(R.string.retry_transcription) { svc()?.retryTranscription(s.id); toast(R.string.working_background) }
        else button(if (clean == null) R.string.retry_cleanup else R.string.redo_cleanup) { svc()?.retryCleanup(s.id); toast(R.string.working_background) }
        button(R.string.delete) {
            Ui.dialog(this).setMessage(if (s.hasAudio()) R.string.delete_confirm_audio else R.string.delete_confirm)
                .setPositiveButton(R.string.delete) { _, _ -> svc()?.delete(s.id) }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
        d.show()
    }

    private fun around(t: String, w: String): String {
        val i = t.lowercase().indexOf(w)
        if (i < 0) return t
        val from = maxOf(0, i - 60)
        return (if (from > 0) "…" else "") + t.substring(from)
    }

    private fun mark(t: String, words: List<String>): CharSequence {
        val out = SpannableString(t)
        val lower = t.lowercase()
        for (w in words) {
            var i = lower.indexOf(w)
            while (i >= 0) {
                out.setSpan(ForegroundColorSpan(primary()), i, i + w.length, 0)
                out.setSpan(StyleSpan(Typeface.BOLD), i, i + w.length, 0)
                i = lower.indexOf(w, i + w.length)
            }
        }
        return out
    }

    private fun copy(t: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("VitalySpeak", t))
        toast(R.string.copied, t.length)
    }
}
