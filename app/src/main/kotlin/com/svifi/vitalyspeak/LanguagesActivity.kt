package com.svifi.vitalyspeak

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.svifi.vitalyspeak.Ui.column
import com.svifi.vitalyspeak.Ui.dp
import com.svifi.vitalyspeak.Ui.primary
import com.svifi.vitalyspeak.Ui.secondaryText
import java.util.Locale

/**
 * The languages you dictate in: one primary language plus any others you mix in.
 * Separate from the interface language.
 */
class LanguagesActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private val ui: Locale get() = resources.configuration.locales[0]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = column().apply { setPadding(0, 0, 0, dp(32)) }
        root.addView(Ui.topBar(this, getString(R.string.speech_languages)))
        root.addView(Ui.note(this, getString(R.string.languages_intro)))
        root.addView(Ui.section(this, getString(R.string.languages_yours), R.drawable.ic_sec_languages))
        list = column()
        root.addView(list)
        root.addView(LinearLayout(this).apply {
            setPadding(dp(24), dp(12), dp(24), dp(8))
            addView(MaterialButton(this@LanguagesActivity).apply {
                text = getString(R.string.languages_add)
                setIconResource(R.drawable.ic_add)
                setOnClickListener { pick() }
            })
        })
        root.addView(Ui.section(this, getString(R.string.languages_why_title)))
        root.addView(Ui.note(this, getString(R.string.languages_why)))
        Ui.page(this, root)
        render()
    }

    private fun current() = Languages.selected(this).toMutableList()

    private fun render() {
        list.removeAllViews()
        val langs = current()
        langs.forEachIndexed { i, code ->
            val end = LinearLayout(this).apply {
                if (i == 0) addView(TextView(context).apply {
                    text = getString(R.string.languages_primary)
                    textSize = 12f
                    setTextColor(0xFFFFFFFF.toInt())
                    setPadding(dp(10), dp(3), dp(10), dp(3))
                    background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(primary()) }
                })
                if (langs.size > 1) addView(ImageButton(context).apply {
                    setImageResource(R.drawable.ic_close)
                    imageTintList = ColorStateList.valueOf(secondaryText())
                    contentDescription = getString(R.string.languages_remove, Languages.label(code, ui))
                    background = null
                    setPadding(dp(12), dp(8), 0, dp(8))
                    setOnClickListener { save(langs - code) }
                })
            }
            val sub = if (i == 0) getString(R.string.languages_primary_sub) else getString(R.string.languages_make_primary)
            list.addView(Ui.row(this, Languages.label(code, ui), sub, end) {
                if (i > 0) save(listOf(code) + (langs - code))
            }.view)
        }
    }

    private fun save(codes: List<String>) {
        Languages.save(this, codes)
        render()
    }

    /** Searchable list of every Whisper language; filters as you type. */
    private fun pick() {
        val chosen = current()
        val all = Languages.SPEECH.filter { it !in chosen }
            .sortedBy { Languages.label(it, ui).lowercase(ui) }
        val shown = all.toMutableList()
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, shown) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply {
                    text = Languages.label(getItem(position)!!, ui)
                    setPadding(dp(24), paddingTop, dp(24), paddingBottom)
                }
        }
        val listView = ListView(this).apply { this.adapter = adapter; isFastScrollEnabled = true }
        val search = Ui.searchField(this, getString(R.string.languages_search)) { q ->
            shown.clear(); shown.addAll(all.filter { Languages.matches(it, q, ui) }); adapter.notifyDataSetChanged()
        }
        val box = column().apply {
            addView(search, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { setMargins(dp(20), dp(8), dp(20), dp(8)) })
            addView(listView, LinearLayout.LayoutParams(Ui.MATCH, (resources.displayMetrics.heightPixels * 0.55f).toInt()))
        }
        val dialog = Ui.dialog(this).setTitle(R.string.languages_add).setView(box)
            .setNegativeButton(android.R.string.cancel, null).create()
        listView.setOnItemClickListener { _, _, pos, _ ->
            save(chosen + shown[pos])
            dialog.dismiss()
        }
        dialog.show()
        search.requestFocus()
    }
}
