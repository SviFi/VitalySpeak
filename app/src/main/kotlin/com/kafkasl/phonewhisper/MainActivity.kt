package com.kafkasl.phonewhisper

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton
import java.text.DateFormat
import java.util.Date
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var statusSubtitle: TextView
    private lateinit var audioRowSub: TextView
    private lateinit var accRowSub: TextView
    private lateinit var keyRowSub: TextView
    private lateinit var sttRowSub: TextView
    private lateinit var llmRowSub: TextView
    private lateinit var vocabRowSub: TextView
    private lateinit var checkRowSub: TextView
    private lateinit var promptRowSub: TextView
    private lateinit var promptRow: LinearLayout
    private lateinit var promptContainer: LinearLayout
    private lateinit var alertsContainer: LinearLayout

    private val promptRows = mutableMapOf<String, PromptRowViews>()
    private data class PromptRowViews(val radio: MaterialRadioButton, val subtitle: TextView)

    private var report: ModelChecker.Report? = null
    private var update: UpdateChecker.Release? = null
    private var checking = false
    private lateinit var recoveryRow: LinearLayout
    private lateinit var recoverySub: TextView
    /** Last model switch made in this session, for the one-tap undo row. */
    private data class ModelSwitch(val stt: Boolean, val from: String, val to: String)
    private var lastSwitch: ModelSwitch? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = vertical(0, 0)

        root.addView(TextView(this).apply {
            text = "VitalySpeak"
            textSize = 32f
            setPadding(dp(24), dp(64), dp(24), dp(24))
        })

        val statusRow = settingsRow("Status", "Checking...")
        statusSubtitle = statusRow.findViewWithTag("subtitle")
        root.addView(statusRow)

        // Alerts: new models, retired models, app updates. Filled by background checks.
        alertsContainer = vertical(0)
        root.addView(alertsContainer)

        // --- Setup ---
        root.addView(sectionHeader("Setup"))

        val audioRow = settingsRow("Audio permission", "Checking...") {
            if (!hasPerm(Manifest.permission.RECORD_AUDIO)) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            }
        }
        audioRowSub = audioRow.findViewWithTag("subtitle")
        root.addView(audioRow)

        val accRow = settingsRow("Accessibility service", "Checking...") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        accRowSub = accRow.findViewWithTag("subtitle")
        root.addView(accRow)

        val keyRow = settingsRow("Groq API Key", "Tap to set") { promptApiKey() }
        keyRowSub = keyRow.findViewWithTag("subtitle")
        root.addView(keyRow)

        val typingSwitch = MaterialSwitch(this).apply {
            isChecked = prefs().getBoolean(WhisperAccessibilityService.KEY_ONLY_WHILE_TYPING, true)
            isClickable = false
        }
        root.addView(settingsRow(
            "Show mic only while typing",
            "The dot appears when the keyboard is open, like Wispr Flow",
            typingSwitch
        ) {
            val v = !typingSwitch.isChecked
            prefs().edit().putBoolean(WhisperAccessibilityService.KEY_ONLY_WHILE_TYPING, v).apply()
            typingSwitch.isChecked = v
            WhisperAccessibilityService.instance?.refreshOverlayVisibility()
        })

        val previewSwitch = MaterialSwitch(this).apply {
            isChecked = prefs().getBoolean(WhisperAccessibilityService.KEY_LIVE_PREVIEW, true)
            isClickable = false
        }
        root.addView(settingsRow(
            "Live preview while speaking",
            "Shows your words in a bubble by the dot (extra fast-Whisper requests to Groq)",
            previewSwitch
        ) {
            val v = !previewSwitch.isChecked
            prefs().edit().putBoolean(WhisperAccessibilityService.KEY_LIVE_PREVIEW, v).apply()
            previewSwitch.isChecked = v
        })

        root.addView(sliderRow("Voice reaction", "How wildly the dot reacts to your voice",
            WhisperAccessibilityService.KEY_REACTION, 70, 0, 100) { v -> if (v < 34) "Calm" else if (v < 67) "Lively" else "Wild" })
        root.addView(sliderRow("Dot size", "Size of the floating mic dot",
            WhisperAccessibilityService.KEY_DOT_SIZE, 100, 60, 160) { v -> "$v%" })

        // Saved dictations that weren't delivered (network failure, app killed…).
        recoveryRow = settingsRow("Unfinished dictations", "") { showRecovery() }
        recoverySub = recoveryRow.findViewWithTag("subtitle")
        root.addView(recoveryRow)

        root.addView(settingsRow("Last dictation", "Tap to copy the last text again") {
            val t = prefs().getString(WhisperAccessibilityService.KEY_LAST_DICTATION, "") ?: ""
            if (t.isBlank()) toast("Nothing yet") else {
                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("VitalySpeak", t))
                toast("Copied (${t.length} chars)")
            }
        })

        root.addView(settingsRow("Diagnostics", "Last dictation (raw vs cleaned), errors, mic dot decisions") {
            val svc = WhisperAccessibilityService.instance
            val events = svc?.visibilityLog?.joinToString("\n")
                ?.ifBlank { null } ?: "No events yet. Open another app, tap a text field, then come back."
            val last = if (svc != null && svc.lastRaw.isNotBlank())
                "LAST DICTATION\nRaw (${svc.lastRaw.length} chars):\n${svc.lastRaw}\n\n" +
                    (if (svc.lastClean.isNotBlank()) "Cleaned (${svc.lastClean.length} chars):\n${svc.lastClean}\n\n" else "") +
                    "EVENTS\n"
                else ""
            val log = last + events
            android.app.AlertDialog.Builder(this)
                .setTitle("Diagnostics (newest first)")
                .setMessage(log)
                .setPositiveButton("OK", null)
                .show()
        })

        // --- Models ---
        root.addView(sectionHeader("Models"))

        val sttRow = settingsRow("Speech-to-text", sttModel()) { pickModel(stt = true) }
        sttRowSub = sttRow.findViewWithTag("subtitle")
        root.addView(sttRow)

        val llmRow = settingsRow("Cleanup model", llmModel()) { pickModel(stt = false) }
        llmRowSub = llmRow.findViewWithTag("subtitle")
        root.addView(llmRow)

        val vocabRow = settingsRow("Vocabulary hint", "") { promptVocabulary() }
        vocabRowSub = vocabRow.findViewWithTag("subtitle")
        vocabRowSub.maxLines = 2
        vocabRowSub.ellipsize = android.text.TextUtils.TruncateAt.END
        root.addView(vocabRow)

        val checkRow = settingsRow("Check for new models & updates", "") { runChecks(manual = true) }
        checkRowSub = checkRow.findViewWithTag("subtitle")
        root.addView(checkRow)

        // --- Cleanup ---
        root.addView(sectionHeader("Cleanup"))

        val cleanupSwitch = MaterialSwitch(this).apply {
            isChecked = prefs().getBoolean(Groq.KEY_USE_CLEANUP, true)
            isClickable = false
        }
        root.addView(settingsRow(
            "Cleanup transcript",
            "Uses a Groq LLM to fix grammar, punctuation, and remove filler words",
            cleanupSwitch
        ) {
            val v = !cleanupSwitch.isChecked
            prefs().edit().putBoolean(Groq.KEY_USE_CLEANUP, v).apply()
            cleanupSwitch.isChecked = v
            refresh()
        })

        promptContainer = vertical(0)
        for (preset in promptPresets()) promptContainer.addView(buildPromptRow(preset))
        root.addView(promptContainer)

        promptRow = settingsRow("Edit current prompt", currentPrompt()) { promptPostProcessing() }
        promptRowSub = promptRow.findViewWithTag("subtitle")
        promptRowSub.maxLines = 2
        promptRowSub.ellipsize = android.text.TextUtils.TruncateAt.END
        root.addView(promptRow)

        // --- About ---
        root.addView(sectionHeader("About"))
        root.addView(settingsRow("Version", "Build ${installedVersionCode()} · tap to open GitHub") {
            openUrl("https://github.com/${UpdateChecker.REPO}/releases")
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(attrColor(android.R.attr.colorBackground))
            addView(root)
        })

        if (!hasPerm(Manifest.permission.RECORD_AUDIO)) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }

        report = ModelChecker.cached(prefs())
        refresh()
        // Background checks when the app is opened — never on the dictation path.
        runChecks(manual = false)
    }

    override fun onResume() { super.onResume(); refresh() }
    override fun onRequestPermissionsResult(c: Int, p: Array<String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r); refresh()
    }

    // --- Background checks ---

    private fun runChecks(manual: Boolean) {
        if (checking) return
        val key = apiKey()
        val modelsDue = key.isNotBlank() && (manual || ModelChecker.shouldAutoCheck(prefs()))
        checking = true
        if (manual) checkRowSub.text = "Checking…"
        thread {
            val r = if (modelsDue) ModelChecker.check(prefs(), key) else null
            val u = UpdateChecker.newerThan(installedVersionCode())
            runOnUiThread {
                checking = false
                if (r != null) report = r
                update = u
                if (manual) {
                    when {
                        key.isBlank() -> toast("Set your Groq API key first")
                        r?.error != null -> toast("Model check failed: ${r.error}")
                        u == null && (r?.newModels.isNullOrEmpty()) -> toast("All up to date")
                    }
                }
                refresh()
            }
        }
    }

    private fun renderAlerts() {
        alertsContainer.removeAllViews()
        val r = report ?: return
        val checked = r.checkedAt > 0

        update?.let { u ->
            alertsContainer.addView(alertRow("⬆ App update available", "${u.name} — tap to download and install") {
                openUrl(u.apkUrl)
            })
        }
        if (checked && sttModel() !in r.stt) {
            val fix = r.recommendation?.stt?.takeIf { it in r.stt }
            alertsContainer.addView(alertRow("⚠ Speech model unavailable",
                "${sttModel()} isn't available to your Groq key (retired or Enterprise-only) — " +
                    (fix?.let { "tap to switch to $it" } ?: "tap to pick another")) {
                if (fix != null) setModel(stt = true, fix) else pickModel(stt = true)
            })
        }
        if (checked && llmModel() !in r.chat) {
            val fix = r.recommendation?.llm?.takeIf { it in r.chat }
            alertsContainer.addView(alertRow("⚠ Cleanup model unavailable",
                "${llmModel()} isn't available to your Groq key (retired or Enterprise-only) — " +
                    (fix?.let { "tap to switch to $it" } ?: "tap to pick another")) {
                if (fix != null) setModel(stt = false, fix) else pickModel(stt = false)
            })
        }
        lastSwitch?.let { sw ->
            alertsContainer.addView(alertRow(
                "↩ Switched ${if (sw.stt) "speech" else "cleanup"} model",
                "Now ${sw.to} (was ${sw.from}) — tap to undo"
            ) {
                lastSwitch = null
                prefs().edit().putString(if (sw.stt) Groq.KEY_STT_MODEL else Groq.KEY_LLM_MODEL, sw.from).apply()
                toast("Back to ${sw.from}")
                refresh()
            })
        }

        val sttUp = if (checked) ModelChecker.findUpgrade(sttModel(), r.stt, r) else null
        val llmUp = if (checked) ModelChecker.findUpgrade(llmModel(), r.chat, r) else null
        sttUp?.let { alertsContainer.addView(upgradeRow(stt = true, it, r)) }
        llmUp?.let { alertsContainer.addView(upgradeRow(stt = false, it, r)) }

        r.recommendation?.let { rec ->
            val note = rec.note?.let { " · $it" } ?: ""
            rec.stt?.takeIf { it != sttModel() && it in r.stt && it != sttUp?.to }?.let { m ->
                alertsContainer.addView(alertRow("★ Recommended speech model", "$m$note — tap to switch") {
                    setModel(stt = true, m)
                })
            }
            rec.llm?.takeIf { it != llmModel() && it in r.chat && it != llmUp?.to }?.let { m ->
                alertsContainer.addView(alertRow("★ Recommended cleanup model", "$m$note — tap to switch") {
                    setModel(stt = false, m)
                })
            }
        }
        if (r.newModels.isNotEmpty()) {
            alertsContainer.addView(alertRow(
                "✦ ${r.newModels.size} new Groq model${if (r.newModels.size > 1) "s" else ""}",
                r.newModels.joinToString(", ") + " — tap to review"
            ) {
                val sttNew = r.newModels.any(ModelChecker::isStt)
                ModelChecker.acknowledgeNew(prefs())
                pickModel(stt = sttNew)
                report = r.copy(newModels = emptyList())
                refresh()
            })
        }
    }

    // --- Model selection ---

    private fun sttModel() = prefs().getString(Groq.KEY_STT_MODEL, Groq.DEFAULT_STT_MODEL) ?: Groq.DEFAULT_STT_MODEL
    private fun llmModel() = prefs().getString(Groq.KEY_LLM_MODEL, Groq.DEFAULT_LLM_MODEL) ?: Groq.DEFAULT_LLM_MODEL

    private fun setModel(stt: Boolean, id: String) {
        val from = if (stt) sttModel() else llmModel()
        if (from == id) return
        lastSwitch = ModelSwitch(stt, from, id)
        prefs().edit().putString(if (stt) Groq.KEY_STT_MODEL else Groq.KEY_LLM_MODEL, id).apply()
        toast("Switched to $id")
        refresh()
    }

    private fun pickModel(stt: Boolean) {
        val r = report ?: ModelChecker.cached(prefs())
        val current = if (stt) sttModel() else llmModel()
        val available = if (stt) r.stt else r.chat
        val ids = (if (current in available) available else listOf(current) + available).distinct()
        val rec = if (stt) r.recommendation?.stt else r.recommendation?.llm
        val labels = ids.map { id ->
            buildString {
                append(id)
                append(if (r.isStable(id)) "  · stable" else "  · preview")
                released(r, id)?.let { append("  · $it") }
                if (id == rec) append("  ★ recommended")
                if (id in r.newModels) append("  ✦ new")
                if (id !in available && r.checkedAt > 0) append("  ⚠ unavailable")
            }
        }.toMutableList()
        labels += "Other… (type a model ID)"

        android.app.AlertDialog.Builder(this)
            .setTitle(if (stt) "Speech-to-text model" else "Cleanup model")
            .setSingleChoiceItems(labels.toTypedArray(), ids.indexOf(current)) { d, which ->
                d.dismiss()
                if (which == ids.size) promptCustomModel(stt) else setModel(stt, ids[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptCustomModel(stt: Boolean) {
        val input = EditText(this).apply {
            hint = if (stt) "whisper-large-v3-turbo" else "openai/gpt-oss-120b"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Model ID")
            .setView(input.apply { setPadding(dp(24), dp(8), dp(24), dp(8)) })
            .setPositiveButton("Use") { _, _ ->
                val id = input.text.toString().trim()
                if (id.isNotEmpty()) setModel(stt, id)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun released(r: ModelChecker.Report, id: String): String? =
        r.created[id]?.takeIf { it > 0 }?.let {
            java.text.SimpleDateFormat("MMM yyyy", java.util.Locale.ENGLISH).format(Date(it * 1000))
        }

    private fun upgradeRow(stt: Boolean, up: ModelChecker.Upgrade, r: ModelChecker.Report): View {
        val kind = if (stt) "speech" else "cleanup"
        val title = if (up.sameFamily) "⬆ Newer version of your $kind model" else "⬆ Newer stable $kind model"
        val date = released(r, up.to)?.let { " · released $it" } ?: ""
        val more = if (up.others.isNotEmpty()) " · +${up.others.size} more" else ""
        return alertRow(title, "${up.from} → ${up.to}$date$more — tap to switch") { confirmUpgrade(stt, up, r) }
    }

    private fun confirmUpgrade(stt: Boolean, up: ModelChecker.Upgrade, r: ModelChecker.Report) {
        val stableNote = if (r.stable.isNotEmpty()) "Stable (listed under Groq Production models)." else "Stable (no preview/beta in its name)."
        val msg = buildString {
            append("Current: ${up.from}${released(r, up.from)?.let { " — $it" } ?: ""}\n")
            append("New: ${up.to}${released(r, up.to)?.let { " — $it" } ?: ""}\n\n")
            append(stableNote).append("\n")
            append(if (up.sameFamily) "Same model line, newer version."
                   else "Different model line. Newer isn't always better for your use, so try a few dictations.")
            append("\n\nYou can undo with one tap afterwards.")
        }
        val b = android.app.AlertDialog.Builder(this)
            .setTitle("Switch to ${up.to}?")
            .setMessage(msg)
            .setPositiveButton("Switch") { _, _ -> setModel(stt, up.to) }
            .setNeutralButton("Don't suggest") { _, _ ->
                ModelChecker.ignore(prefs(), up.to)
                report = ModelChecker.cached(prefs())
                refresh()
            }
        if (up.others.isNotEmpty()) b.setNegativeButton("Compare all") { _, _ -> pickModel(stt) }
        else b.setNegativeButton("Not now", null)
        b.show()
    }

    // --- Prompt rows ---

    private fun buildPromptRow(preset: PromptPreset): View {
        val radio = MaterialRadioButton(this).apply {
            isClickable = false
            buttonTintList = ColorStateList.valueOf(attrColor(com.google.android.material.R.attr.colorPrimary))
        }
        val row = settingsRow(preset.title, preset.subtitle, radio) { selectPrompt(preset.key) }
        promptRows[preset.key] = PromptRowViews(radio, row.findViewWithTag("subtitle"))
        refreshPromptRow(preset)
        return row
    }

    private fun selectPrompt(key: String) {
        val prompt = promptPresets().firstOrNull { it.key == key }?.prompt ?: return
        prefs().edit().putString("post_processing_prompt", prompt).apply()
        refresh()
    }

    private val builtInPrompts get() = listOf(PostProcessor.BILINGUAL_PROMPT, PostProcessor.DEV_PROMPT, PostProcessor.SIMPLE_PROMPT)

    private fun refreshPromptRow(preset: PromptPreset) {
        val views = promptRows[preset.key] ?: return
        val current = currentPrompt()
        views.radio.isChecked = when (preset.key) {
            "custom" -> current !in builtInPrompts
            else -> current == preset.prompt
        }
        views.subtitle.text = if (preset.key == "custom") customPromptSummary() else preset.subtitle
    }

    // --- State ---

    private fun refresh() {
        val audio = hasPerm(Manifest.permission.RECORD_AUDIO)
        val acc = WhisperAccessibilityService.instance != null
        val useCleanup = prefs().getBoolean(Groq.KEY_USE_CLEANUP, true)
        val key = apiKey()

        audioRowSub.text = if (audio) "Granted" else "Tap to grant permission"
        accRowSub.text = if (acc) "Enabled" else "Tap to enable in settings"
        keyRowSub.text = when {
            key.isBlank() -> "Tap to set — get one free at console.groq.com/keys"
            key.length > 8 -> "${key.take(4)}…${key.takeLast(4)}"
            else -> "…***"
        }

        sttRowSub.text = sttModel() + if (sttModel() == Groq.DEFAULT_STT_MODEL) " · Groq Whisper Large v3" else ""
        llmRowSub.text = llmModel()
        vocabRowSub.text = prefs().getString(Groq.KEY_VOCAB, Groq.DEFAULT_VOCAB)?.ifBlank { "None" } ?: "None"

        val r = report
        if (!checking) {
            checkRowSub.text = if (r == null || r.checkedAt == 0L) "Never checked"
            else "Last checked " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(r.checkedAt))
        }

        promptContainer.visibility = if (useCleanup) View.VISIBLE else View.GONE
        promptRow.visibility = if (useCleanup) View.VISIBLE else View.GONE
        promptRowSub.text = currentPrompt()
        promptPresets().forEach { refreshPromptRow(it) }

        val ready = audio && acc && key.isNotBlank()
        statusSubtitle.text = if (ready) "Ready — open the keyboard in any app, then tap the mic dot" else "Setup required"
        statusSubtitle.setTextColor(
            if (ready) attrColor(com.google.android.material.R.attr.colorPrimary)
            else attrColor(android.R.attr.textColorSecondary)
        )

        renderAlerts()
        refreshRecovery()
    }

    private fun refreshRecovery() {
        val list = WhisperAccessibilityService.instance?.unfinishedSessions().orEmpty()
        recoveryRow.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        recoverySub.text = "${list.size} saved recording(s) not delivered yet — tap to retry"
    }

    private fun showRecovery() {
        val svc = WhisperAccessibilityService.instance ?: return toast("Enable the accessibility service first")
        val list = svc.unfinishedSessions()
        if (list.isEmpty()) { refreshRecovery(); return }
        val labels = list.map { s ->
            val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(Date(s.id.substringBefore('_').toLongOrNull() ?: 0))
            val sec = s.audioSeconds()
            val done = s.partCount() - s.missingParts().size
            "$date · ${sec / 60}:${"%02d".format(sec % 60)} · $done/${s.partCount()} parts transcribed"
        }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Unfinished dictations")
            .setItems(labels) { _, which ->
                val s = list[which]
                android.app.AlertDialog.Builder(this)
                    .setTitle(labels[which])
                    .setMessage("Retry transcribes what's missing, then copies the full text to the clipboard. The audio is deleted only after that succeeds.")
                    .setPositiveButton("Retry") { _, _ -> svc.retrySession(s.id); toast("Retrying in background…") }
                    .setNegativeButton("Delete") { _, _ ->
                        android.app.AlertDialog.Builder(this)
                            .setMessage("Delete this recording permanently?")
                            .setPositiveButton("Delete") { _, _ -> svc.deleteSession(s.id); recoveryRow.postDelayed({ refreshRecovery() }, 300) }
                            .setNegativeButton("Keep", null).show()
                    }
                    .setNeutralButton("Close", null)
                    .show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // --- Dialogs ---

    private fun promptApiKey() {
        val input = EditText(this).apply {
            hint = "gsk_..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(apiKey())
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Groq API Key")
            .setView(input.apply { setPadding(dp(24), dp(8), dp(24), dp(8)) })
            .setPositiveButton("Save") { _, _ ->
                prefs().edit().putString(Groq.KEY_API, input.text.toString().trim()).apply()
                refresh()
                runChecks(manual = false)
            }
            .setNeutralButton("Get key") { _, _ -> openUrl("https://console.groq.com/keys") }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptVocabulary() {
        val input = EditText(this).apply {
            hint = "Names and terms Whisper should spell correctly"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            setText(prefs().getString(Groq.KEY_VOCAB, Groq.DEFAULT_VOCAB))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Vocabulary hint")
            .setMessage("Names and terms you use. The cleanup step fixes their spelling (only when you actually said them).")
            .setView(input.apply { setPadding(dp(24), dp(8), dp(24), dp(8)) })
            .setPositiveButton("Save") { _, _ ->
                prefs().edit().putString(Groq.KEY_VOCAB, input.text.toString().trim()).apply()
                refresh()
            }
            .setNeutralButton("Reset") { _, _ ->
                prefs().edit().putString(Groq.KEY_VOCAB, Groq.DEFAULT_VOCAB).apply()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptPostProcessing() {
        val input = EditText(this).apply {
            hint = "Prompt"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            setText(currentPrompt())
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Edit current prompt")
            .setView(input.apply { setPadding(dp(24), dp(8), dp(24), dp(8)) })
            .setPositiveButton("Save") { _, _ ->
                val text = input.text.toString().trim()
                val finalPrompt = if (text.isBlank()) PostProcessor.DEFAULT_PROMPT else text
                prefs().edit()
                    .putString("custom_post_processing_prompt", finalPrompt)
                    .putString("post_processing_prompt", finalPrompt)
                    .apply()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- UI helpers ---

    /** Title + value label + slider; saves on release and tells the service to apply it. */
    private fun sliderRow(title: String, subtitle: String, key: String, def: Int, min: Int, max: Int,
                          label: (Int) -> String): LinearLayout {
        val row = vertical(0).apply { setPadding(dp(24), dp(16), dp(24), dp(8)) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val titleView = TextView(this).apply {
            text = title
            textSize = 18f
            setTextColor(attrColor(android.R.attr.textColorPrimary))
            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f)
        }
        val current = prefs().getInt(key, def).coerceIn(min, max)
        val valueView = TextView(this).apply {
            text = label(current)
            textSize = 14f
            setTextColor(attrColor(com.google.android.material.R.attr.colorPrimary))
        }
        head.addView(titleView); head.addView(valueView)
        row.addView(head)
        row.addView(TextView(this).apply {
            text = subtitle
            textSize = 14f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(SeekBar(this).apply {
            this.max = max - min
            progress = current - min
            setPadding(dp(4), dp(12), dp(4), dp(4))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { valueView.text = label(p + min) }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {
                    prefs().edit().putInt(key, sb.progress + min).apply()
                    WhisperAccessibilityService.instance?.applyAppearanceSettings()
                }
            })
        })
        return row
    }

    private fun alertRow(title: String, subtitle: String, onClick: () -> Unit): LinearLayout =
        settingsRow(title, subtitle, null, onClick).apply {
            val bg = attrColor(com.google.android.material.R.attr.colorPrimaryContainer)
            setBackgroundColor(bg)
            (getChildAt(0) as LinearLayout).let { tc ->
                (tc.getChildAt(0) as TextView).setTextColor(attrColor(com.google.android.material.R.attr.colorOnPrimaryContainer))
                (tc.getChildAt(1) as TextView).setTextColor(attrColor(com.google.android.material.R.attr.colorOnPrimaryContainer))
            }
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP).apply { bottomMargin = dp(2) }
        }

    private fun settingsRow(title: String, subtitle: String, widget: View? = null, onClick: (() -> Unit)? = null): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(16))
            isClickable = onClick != null
            isFocusable = onClick != null
            if (onClick != null) {
                val outValue = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                setBackgroundResource(outValue.resourceId)
                setOnClickListener { onClick() }
            }
        }
        val textContainer = vertical(0).apply { layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f) }
        textContainer.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setTextColor(attrColor(android.R.attr.textColorPrimary))
        })
        textContainer.addView(TextView(this).apply {
            tag = "subtitle"
            text = subtitle
            textSize = 14f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(textContainer)
        if (widget != null) row.addView(widget)
        return row
    }

    private fun sectionHeader(title: String) = TextView(this).apply {
        text = title
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(attrColor(com.google.android.material.R.attr.colorPrimary))
        setPadding(dp(24), dp(24), dp(24), dp(8))
    }

    private fun vertical(padH: Int, padV: Int = padH) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padH, padV, padH, padV)
    }

    private fun currentPrompt() = prefs().getString("post_processing_prompt", PostProcessor.DEFAULT_PROMPT) ?: PostProcessor.DEFAULT_PROMPT
    private fun customPrompt() = prefs().getString("custom_post_processing_prompt", PostProcessor.DEFAULT_PROMPT) ?: PostProcessor.DEFAULT_PROMPT
    private fun customPromptSummary(): String {
        val prompt = customPrompt()
        return if (prompt in builtInPrompts) "Your edited prompt" else prompt.replace("\n", " ")
    }

    private data class PromptPreset(val key: String, val title: String, val subtitle: String, val prompt: String)

    private fun promptPresets() = listOf(
        PromptPreset("bilingual", "Bilingual cleanup", "RU + EN: fillers, punctuation, no translation", PostProcessor.BILINGUAL_PROMPT),
        PromptPreset("dev", "Dev cleanup", "Coding, CLI commands, project names", PostProcessor.DEV_PROMPT),
        PromptPreset("simple", "Simple cleanup", "Grammar and punctuation only", PostProcessor.SIMPLE_PROMPT),
        PromptPreset("custom", "Custom", customPromptSummary(), customPrompt())
    )

    private fun installedVersionCode(): Long = try {
        packageManager.getPackageInfo(packageName, 0).longVersionCode
    } catch (_: Exception) { 0 }

    private fun openUrl(url: String) = try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Exception) { toast("No browser found") }

    private fun apiKey() = prefs().getString(Groq.KEY_API, "") ?: ""
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun hasPerm(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun attrColor(attr: Int): Int {
        val ta = obtainStyledAttributes(intArrayOf(attr))
        val color = ta.getColor(0, 0)
        ta.recycle()
        return color
    }
    private fun prefs() = getSharedPreferences(Groq.PREFS, MODE_PRIVATE)
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val LP_MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val LP_WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
