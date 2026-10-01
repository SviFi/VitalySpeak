package com.svifi.vitalyspeak

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.radiobutton.MaterialRadioButton
import com.svifi.vitalyspeak.Ui.column
import com.svifi.vitalyspeak.Ui.dp
import com.svifi.vitalyspeak.Ui.primary
import com.svifi.vitalyspeak.Ui.secondaryText
import com.svifi.vitalyspeak.Ui.themeColor
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Home screen: setup status, settings, stats and links. */
class MainActivity : AppCompatActivity() {

    private val p by lazy { Prefs.get(this) }
    private fun s(id: Int, vararg args: Any) = getString(id, *args)

    private lateinit var status: Ui.Row
    private lateinit var statusBadge: TextView
    private lateinit var alerts: LinearLayout
    private lateinit var statsBox: LinearLayout
    private lateinit var history: Ui.Row
    private lateinit var mic: Ui.Row; private lateinit var micBadge: TextView
    private lateinit var access: Ui.Row; private lateinit var accessBadge: TextView
    private lateinit var key: Ui.Row; private lateinit var keyBadge: TextView
    private lateinit var battery: Ui.Row; private lateinit var batteryBadge: TextView
    private var alertsRow: Ui.Row? = null; private var alertsBadge: TextView? = null
    private lateinit var speechLangs: Ui.Row
    private lateinit var appLang: Ui.Row
    private lateinit var vocab: Ui.Row
    private lateinit var styles: LinearLayout
    private val styleRadios = mutableMapOf<CleanupApi.Style, MaterialRadioButton>()
    private lateinit var customRow: Ui.Row
    private lateinit var stt: Ui.Row
    private lateinit var llm: Ui.Row
    private lateinit var checkRow: Ui.Row
    private lateinit var usage: LinearLayout

    private var report: ModelChecker.Report? = null
    private var update: UpdateChecker.Release? = null
    private var checking = false
    private data class Switch(val stt: Boolean, val from: String, val to: String)
    private var lastSwitch: Switch? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = column().apply { setPadding(0, 0, 0, dp(32)) }
        root.addView(Ui.topBar(this, s(R.string.app_name), logo = true))

        statusBadge = Ui.badge(this)
        status = Ui.row(this, s(R.string.status), "", statusBadge)
        root.addView(status.view)
        alerts = column(); root.addView(alerts)
        statsBox = column(); root.addView(statsBox)

        // History
        root.addView(Ui.section(this, s(R.string.sec_history), R.drawable.ic_sec_history))
        history = Ui.row(this, s(R.string.history_row)) { startActivity(Intent(this, HistoryActivity::class.java)) }
        root.addView(history.view)

        // Setup
        root.addView(Ui.section(this, s(R.string.sec_setup), R.drawable.ic_sec_setup))
        micBadge = Ui.badge(this)
        mic = Ui.row(this, s(R.string.setup_mic), "", micBadge) {
            if (!granted(Manifest.permission.RECORD_AUDIO)) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        accessBadge = Ui.badge(this)
        access = Ui.row(this, s(R.string.setup_access), "", accessBadge) { openAccessibility() }
        keyBadge = Ui.badge(this)
        key = Ui.row(this, s(R.string.setup_key), "", keyBadge) { editKey() }
        batteryBadge = Ui.badge(this)
        battery = Ui.row(this, s(R.string.setup_battery), "", batteryBadge) {
            if (!Health.batteryUnrestricted(this)) Health.openBatterySettings(this)
            else Ui.dialog(this).setTitle(R.string.setup_battery).setMessage(R.string.battery_ok_help).setPositiveButton(android.R.string.ok, null).show()
        }
        listOf(mic, access, key, battery).forEach { root.addView(it.view) }
        if (Build.VERSION.SDK_INT >= 33) {
            val b = Ui.badge(this)
            alertsBadge = b
            alertsRow = Ui.row(this, s(R.string.setup_alerts), "", b) {
                if (!Health.notificationsAllowed(this)) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            }.also { root.addView(it.view) }
        }

        // Languages
        root.addView(Ui.section(this, s(R.string.sec_languages), R.drawable.ic_sec_languages))
        speechLangs = Ui.row(this, s(R.string.speech_languages)) { startActivity(Intent(this, LanguagesActivity::class.java)) }
        appLang = Ui.row(this, s(R.string.app_language)) { pickAppLanguage() }
        root.addView(speechLangs.view); root.addView(appLang.view)

        // Dictation
        root.addView(Ui.section(this, s(R.string.sec_dictation), R.drawable.ic_sec_dictation))
        root.addView(Ui.switchRow(this, s(R.string.only_while_typing), s(R.string.only_while_typing_sub), Prefs.ONLY_WHILE_TYPING, true) {
            VoiceService.instance?.onVisibilitySettingChanged()
        }.view)
        root.addView(Ui.switchRow(this, s(R.string.live_preview), s(R.string.live_preview_sub), Prefs.LIVE_PREVIEW, true).view)
        vocab = Ui.row(this, s(R.string.vocabulary)) { editVocabulary() }
        vocab.subtitle.maxLines = 2
        root.addView(vocab.view)
        root.addView(Ui.switchRow(this, s(R.string.command_keys), s(R.string.command_keys_sub), Prefs.COMMAND_KEYS, true).view)
        root.addView(Ui.switchRow(this, s(R.string.keep_audio), s(R.string.keep_audio_sub), Prefs.KEEP_AUDIO, false).view)

        // Cleanup
        root.addView(Ui.section(this, s(R.string.sec_cleanup), R.drawable.ic_sec_cleanup))
        root.addView(Ui.switchRow(this, s(R.string.cleanup), s(R.string.cleanup_sub), Prefs.CLEANUP, true) { refresh() }.view)
        styles = column()
        for ((style, title, sub) in listOf(
            Triple(CleanupApi.Style.STANDARD, R.string.style_standard, R.string.style_standard_sub),
            Triple(CleanupApi.Style.FORMAL, R.string.style_formal, R.string.style_formal_sub),
            Triple(CleanupApi.Style.NOTES, R.string.style_notes, R.string.style_notes_sub),
            Triple(CleanupApi.Style.CUSTOM, R.string.style_custom, R.string.style_custom_sub))) {
            val radio = MaterialRadioButton(this).apply { isClickable = false; isFocusable = false }
            styleRadios[style] = radio
            styles.addView(Ui.row(this, s(title), s(sub), radio) {
                p.edit().putString(Prefs.STYLE, style.name.lowercase()).apply()
                if (style == CleanupApi.Style.CUSTOM && p.getString(Prefs.CUSTOM_PROMPT, "").isNullOrBlank()) editCustom()
                refresh()
            }.view)
        }
        customRow = Ui.row(this, s(R.string.custom_instructions)) { editCustom() }
        customRow.subtitle.maxLines = 2
        styles.addView(customRow.view)
        root.addView(styles)

        // Models
        root.addView(Ui.section(this, s(R.string.sec_models), R.drawable.ic_sec_models))
        stt = Ui.row(this, s(R.string.model_stt)) { pickModel(true) }
        llm = Ui.row(this, s(R.string.model_llm)) { pickModel(false) }
        checkRow = Ui.row(this, s(R.string.check_updates)) { runChecks(manual = true) }
        listOf(stt, llm, checkRow).forEach { root.addView(it.view) }

        // Appearance
        root.addView(Ui.section(this, s(R.string.sec_appearance), R.drawable.ic_sec_appearance))
        root.addView(Ui.row(this, s(R.string.look), s(R.string.look_sub)) {
            startActivity(Intent(this, AppearanceActivity::class.java))
        }.view)

        // Usage
        root.addView(Ui.section(this, s(R.string.sec_usage), R.drawable.ic_sec_usage))
        usage = column(); root.addView(usage)

        // About
        root.addView(Ui.section(this, s(R.string.sec_about), R.drawable.ic_sec_advanced))
        root.addView(Ui.row(this, s(R.string.diagnostics), s(R.string.diagnostics_sub)) { showDiagnostics() }.view)
        root.addView(Ui.row(this, s(R.string.privacy_policy)) { open(BuildConfig.PRIVACY_URL) }.view)
        root.addView(Ui.row(this, s(R.string.terms)) { open(BuildConfig.TERMS_URL) }.view)
        root.addView(Ui.row(this, s(R.string.licenses)) {
            Ui.dialog(this).setTitle(R.string.licenses).setMessage(R.string.licenses_text).setPositiveButton(android.R.string.ok, null).show()
        }.view)
        root.addView(Ui.row(this, s(R.string.version), s(R.string.version_sub, BuildConfig.VERSION_NAME)) {
            if (BuildConfig.SELF_UPDATE) open("https://github.com/${UpdateChecker.REPO}/releases")
        }.view)

        Ui.page(this, root)

        // First run: explain the accessibility use before anything else.
        if (!p.getBoolean(Prefs.DISCLOSURE_ACCEPTED, false)) startActivity(Intent(this, DisclosureActivity::class.java))
        else if (!granted(Manifest.permission.RECORD_AUDIO)) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)

        Health.schedule(this)
        Health.check(this, "app opened")
        if (Build.VERSION.SDK_INT >= 33 && !Health.notificationsAllowed(this) && !p.getBoolean(Prefs.ASKED_NOTIFICATIONS, false)
            && p.getBoolean(Prefs.DISCLOSURE_ACCEPTED, false)) {
            p.edit().putBoolean(Prefs.ASKED_NOTIFICATIONS, true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        report = ModelChecker.cached(p)
        runChecks(manual = false)
    }

    override fun onResume() { super.onResume(); refresh() }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results); refresh()
    }

    private fun granted(perm: String) = checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
    private fun apiKey() = p.getString(Prefs.API_KEY, "").orEmpty().trim()
    private fun sttModel() = p.getString(Prefs.STT_MODEL, null) ?: Groq.DEFAULT_STT_MODEL
    private fun llmModel() = p.getString(Prefs.LLM_MODEL, null) ?: Groq.DEFAULT_LLM_MODEL
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun open(url: String) = try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { toast(s(R.string.no_browser)) }

    // ---------------- state ----------------

    private fun refresh() {
        val micOk = granted(Manifest.permission.RECORD_AUDIO)
        val running = VoiceService.instance != null
        val switchedOn = Health.accessibilityEnabled(this)
        val k = apiKey()

        Ui.setBadge(micBadge, if (micOk) Ui.Level.OK else Ui.Level.BAD)
        Ui.setSubtitle(mic, s(if (micOk) R.string.mic_ok else R.string.mic_missing))
        Ui.setBadge(accessBadge, if (running) Ui.Level.OK else Ui.Level.BAD)
        Ui.setSubtitle(access, s(when { running -> R.string.access_ok; switchedOn -> R.string.access_stuck; else -> R.string.access_off }))
        Ui.setBadge(keyBadge, if (k.isNotEmpty()) Ui.Level.OK else Ui.Level.BAD)
        Ui.setSubtitle(key, if (k.isEmpty()) s(R.string.key_missing) else if (k.length > 8) "${k.take(4)}…${k.takeLast(4)}" else "…")
        val batt = Health.batteryUnrestricted(this)
        Ui.setBadge(batteryBadge, if (batt) Ui.Level.OK else Ui.Level.WARN)
        Ui.setSubtitle(battery, s(if (batt) R.string.battery_ok else R.string.battery_missing))
        alertsRow?.let { r ->
            val ok = Health.notificationsAllowed(this)
            Ui.setBadge(alertsBadge!!, if (ok) Ui.Level.OK else Ui.Level.WARN)
            Ui.setSubtitle(r, s(if (ok) R.string.alerts_ok else R.string.alerts_missing))
        }

        val ready = micOk && running && k.isNotEmpty()
        val missing = listOfNotNull(
            s(R.string.setup_mic).takeIf { !micOk }, s(R.string.setup_access).takeIf { !running }, s(R.string.setup_key).takeIf { k.isEmpty() })
        Ui.setSubtitle(status, if (ready) s(R.string.status_ready) else s(R.string.status_incomplete, missing.joinToString(", ")))
        status.subtitle.setTextColor(if (ready) Ui.GREEN else Ui.RED)
        Ui.setBadge(statusBadge, if (ready) Ui.Level.OK else Ui.Level.BAD)

        val langs = Languages.selected(this)
        Ui.setSubtitle(speechLangs, langs.joinToString(", ") { Languages.label(it, uiLocale()) })
        Ui.setSubtitle(appLang, UiLocale.current(this).let { if (it.isEmpty()) s(R.string.app_language_system) else uiName(it) })
        Ui.setSubtitle(vocab, vocabText().ifBlank { s(R.string.vocabulary_none) })

        val cleanupOn = p.getBoolean(Prefs.CLEANUP, true)
        styles.visibility = if (cleanupOn) View.VISIBLE else View.GONE
        val style = CleanupApi.style(p.getString(Prefs.STYLE, null))
        styleRadios.forEach { (st, r) -> r.isChecked = st == style }
        customRow.view.visibility = if (style == CleanupApi.Style.CUSTOM) View.VISIBLE else View.GONE
        Ui.setSubtitle(customRow, p.getString(Prefs.CUSTOM_PROMPT, "").orEmpty().replace('\n', ' ').ifBlank { s(R.string.custom_instructions_empty) })

        Ui.setSubtitle(stt, sttModel())
        Ui.setSubtitle(llm, llmModel())
        if (!checking) Ui.setSubtitle(checkRow, report?.checkedAt?.takeIf { it > 0 }?.let {
            s(R.string.last_checked, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)))
        } ?: s(R.string.never_checked))

        renderHistoryRow(running)
        renderAlerts()
        renderStats()
        renderUsage()
    }

    private fun vocabText() = p.getString(Prefs.VOCAB, "").orEmpty()
    private fun uiLocale(): Locale = resources.configuration.locales[0]

    private fun renderHistoryRow(running: Boolean) {
        val list = VoiceService.instance?.history().orEmpty()
        val pending = list.count { it.status == DictationStore.Session.Status.NEEDS_TRANSCRIPTION }
        Ui.setSubtitle(history, when {
            !running -> s(R.string.history_needs_service)
            pending > 0 -> resources.getQuantityString(R.plurals.history_pending, pending, pending, list.size)
            list.isEmpty() -> s(R.string.history_empty)
            else -> resources.getQuantityString(R.plurals.history_count, list.size, list.size)
        })
        history.subtitle.setTextColor(if (pending > 0) Ui.RED else secondaryText())
    }

    // ---------------- stats card ----------------

    private fun renderStats() {
        statsBox.removeAllViews()
        val sum = (VoiceService.instance?.stats ?: Stats(java.io.File(filesDir, "stats.tsv"))).summary()
        if (sum.sessions == 0) return
        val card = column().apply {
            setPadding(dp(20), dp(18), dp(20), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(themeColor(com.google.android.material.R.attr.colorSurfaceContainer))
            }
            layoutParams = LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { setMargins(dp(16), dp(8), dp(16), dp(4)) }
        }
        fun small(text: String) = TextView(this).apply { this.text = text; textSize = 12f; setTextColor(secondaryText()) }
        card.addView(small(s(R.string.stats_title).uppercase()).apply { letterSpacing = 0.1f; textSize = 11f })
        val grid = GridLayout(this).apply { columnCount = 3; setPadding(0, dp(8), 0, dp(8)) }
        fun cell(value: String, label: String) = grid.addView(column().apply {
            layoutParams = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply { width = 0 }
            setPadding(0, dp(6), dp(4), dp(6))
            addView(TextView(this@MainActivity).apply { text = value; textSize = 22f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
            addView(small(label))
        })
        cell(Stats.formatDuration(sum.totalSeconds), s(R.string.stats_spoken))
        cell(String.format(Locale.getDefault(), "%,d", sum.totalWords), s(R.string.stats_words))
        cell("${sum.sessions}", s(R.string.stats_dictations))
        cell(Stats.formatDuration(sum.averageSeconds), s(R.string.stats_average))
        cell(Stats.formatDuration(sum.longestSeconds), s(R.string.stats_longest))
        cell(if (sum.streakDays > 0) "${sum.streakDays} 🔥" else "–", s(R.string.stats_streak))
        card.addView(grid)
        if (sum.wordsPerMinute > 0) card.addView(small(s(R.string.stats_wpm, sum.wordsPerMinute, maxOf(1, sum.wordsPerMinute / 40))).apply { setPadding(0, 0, 0, dp(10)) })
        card.addView(small(s(R.string.stats_chart)).apply { textSize = 11f; setPadding(0, 0, 0, dp(6)) })
        card.addView(BarChartView(this, primary(), themeColor(com.google.android.material.R.attr.colorOutlineVariant), secondaryText()).apply { values = sum.daily })
        statsBox.addView(card)
    }

    // ---------------- background checks + alerts ----------------

    private fun runChecks(manual: Boolean) {
        if (checking) return
        val k = apiKey()
        val modelsDue = k.isNotEmpty() && (manual || ModelChecker.shouldAutoCheck(p))
        checking = true
        if (manual) Ui.setSubtitle(checkRow, s(R.string.checking))
        Thread {
            val r = if (modelsDue) ModelChecker.check(p, k) else null
            val u = if (BuildConfig.SELF_UPDATE) UpdateChecker.newerThan(BuildConfig.VERSION_CODE.toLong()) else null
            runOnUiThread {
                checking = false
                if (r != null) report = r
                update = u
                if (manual) when {
                    k.isEmpty() -> toast(s(R.string.key_first))
                    r?.error != null -> toast(s(R.string.check_failed, r.error))
                    u == null && r?.newModels.isNullOrEmpty() -> toast(s(R.string.all_up_to_date))
                }
                refresh()
            }
        }.start()
    }

    private fun released(r: ModelChecker.Report, id: String): String? = r.created[id]?.takeIf { it > 0 }?.let {
        java.text.SimpleDateFormat("MMM yyyy", uiLocale()).format(Date(it * 1000))
    }

    private fun renderAlerts() {
        alerts.removeAllViews()
        fun add(title: String, sub: String, action: () -> Unit) = alerts.addView(Ui.alertRow(this, title, sub, action))
        update?.let { u -> add(s(R.string.alert_update), s(R.string.alert_update_sub, u.name)) { open(u.apkUrl) } }
        val r = report ?: return
        val checked = r.checkedAt > 0
        for (isStt in listOf(true, false)) {
            val current = if (isStt) sttModel() else llmModel()
            val available = if (isStt) r.stt else r.chat
            if (checked && current !in available) {
                val fix = (if (isStt) r.recommendation?.stt else r.recommendation?.llm)?.takeIf { it in available }
                add(s(if (isStt) R.string.alert_stt_gone else R.string.alert_llm_gone),
                    if (fix != null) s(R.string.alert_gone_fix, current, fix) else s(R.string.alert_gone_pick, current)) {
                    if (fix != null) setModel(isStt, fix) else pickModel(isStt)
                }
            }
        }
        lastSwitch?.let { sw ->
            add(s(R.string.alert_switched), s(R.string.alert_switched_sub, sw.to, sw.from)) {
                lastSwitch = null
                p.edit().putString(if (sw.stt) Prefs.STT_MODEL else Prefs.LLM_MODEL, sw.from).apply()
                refresh()
            }
        }
        val sttUp = if (checked) ModelChecker.findUpgrade(sttModel(), r.stt, r) else null
        val llmUp = if (checked) ModelChecker.findUpgrade(llmModel(), r.chat, r) else null
        sttUp?.let { up -> add(s(R.string.alert_upgrade), "${up.from} → ${up.to}") { confirmUpgrade(true, up, r) } }
        llmUp?.let { up -> add(s(R.string.alert_upgrade), "${up.from} → ${up.to}") { confirmUpgrade(false, up, r) } }
        r.recommendation?.let { rec ->
            rec.stt?.takeIf { it != sttModel() && it in r.stt && it != sttUp?.to }?.let { m -> add(s(R.string.alert_recommended), m) { setModel(true, m) } }
            rec.llm?.takeIf { it != llmModel() && it in r.chat && it != llmUp?.to }?.let { m -> add(s(R.string.alert_recommended), m) { setModel(false, m) } }
        }
        if (r.newModels.isNotEmpty()) {
            add(resources.getQuantityString(R.plurals.alert_new_models, r.newModels.size, r.newModels.size), r.newModels.joinToString(", ")) {
                ModelChecker.acknowledgeNew(p)
                report = r.copy(newModels = emptyList())
                pickModel(r.newModels.any(ModelChecker::isStt))
                refresh()
            }
        }
    }

    private fun confirmUpgrade(isStt: Boolean, up: ModelChecker.Upgrade, r: ModelChecker.Report) {
        val msg = s(R.string.upgrade_text, up.from, released(r, up.from) ?: "–", up.to, released(r, up.to) ?: "–") + "\n\n" +
            s(if (up.sameFamily) R.string.upgrade_same_line else R.string.upgrade_other_line)
        Ui.dialog(this).setTitle(s(R.string.upgrade_title, up.to)).setMessage(msg)
            .setPositiveButton(R.string.switch_model) { _, _ -> setModel(isStt, up.to) }
            .setNeutralButton(R.string.dont_suggest) { _, _ -> ModelChecker.ignore(p, up.to); report = ModelChecker.cached(p); refresh() }
            .setNegativeButton(R.string.not_now, null).show()
    }

    // ---------------- models ----------------

    private fun setModel(isStt: Boolean, id: String) {
        val from = if (isStt) sttModel() else llmModel()
        if (from == id) return
        lastSwitch = Switch(isStt, from, id)
        p.edit().putString(if (isStt) Prefs.STT_MODEL else Prefs.LLM_MODEL, id).apply()
        refresh()
    }

    private fun pickModel(isStt: Boolean) {
        val r = report ?: ModelChecker.cached(p)
        val current = if (isStt) sttModel() else llmModel()
        val available = if (isStt) r.stt else r.chat
        val ids = (listOf(current) + available).distinct()
        val rec = if (isStt) r.recommendation?.stt else r.recommendation?.llm
        val labels = ids.map { id ->
            buildString {
                append(id)
                append("  · ").append(s(if (r.isStable(id)) R.string.model_stable else R.string.model_preview))
                released(r, id)?.let { append("  · ").append(it) }
                if (id == rec) append("  ★")
                if (id in r.newModels) append("  ✦")
                if (id !in available && r.checkedAt > 0) append("  ⚠")
            }
        } + s(R.string.model_other)
        Ui.dialog(this).setTitle(if (isStt) R.string.model_stt else R.string.model_llm)
            .setSingleChoiceItems(labels.toTypedArray(), ids.indexOf(current)) { d, which ->
                d.dismiss()
                if (which == ids.size) {
                    val (box, input) = Ui.input(this, "", if (isStt) "whisper-large-v3-turbo" else "openai/gpt-oss-120b")
                    Ui.dialog(this).setTitle(R.string.model_id).setView(box)
                        .setPositiveButton(R.string.use) { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let { setModel(isStt, it) } }
                        .setNegativeButton(android.R.string.cancel, null).show()
                } else setModel(isStt, ids[which])
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    // ---------------- usage ----------------

    private fun renderUsage() {
        usage.removeAllViews()
        val limits = GroqUsage.all()
        val audio = GroqUsage.audioToday()
        for (m in limits) {
            m.requestsUsedFraction?.let { f ->
                usage.addView(bar(m.model, s(R.string.usage_requests, m.limitRequests!! - m.remainingRequests!!, m.limitRequests), f))
            }
            if (m.limitTokens != null && m.remainingTokens != null && m.limitTokens > 0) {
                val used = m.limitTokens - m.remainingTokens
                usage.addView(bar(m.model, s(R.string.usage_tokens, used, m.limitTokens), used.toDouble() / m.limitTokens))
            }
        }
        for (model in audio.keys()) {
            val sec = audio.optDouble(model, 0.0)
            usage.addView(bar(model, s(R.string.usage_audio, (sec / 60).toInt(), GroqUsage.FREE_AUDIO_SEC_PER_DAY / 3600), sec / GroqUsage.FREE_AUDIO_SEC_PER_DAY))
        }
        usage.addView(Ui.row(this, s(R.string.usage_console), s(if (limits.isEmpty() && audio.length() == 0) R.string.usage_none else R.string.usage_console_sub)) {
            open("https://console.groq.com/settings/limits")
        }.view)
    }

    private fun bar(title: String, sub: String, fraction: Double): View {
        val f = fraction.coerceIn(0.0, 1.0)
        val warn = f >= GroqUsage.WARN_AT
        return column().apply {
            setPadding(dp(24), dp(10), dp(24), dp(10))
            addView(TextView(context).apply { text = "$title  ${(f * 100).toInt()}%"; textSize = 13f; if (warn) setTextColor(Ui.RED) })
            addView(LinearProgressIndicator(context).apply {
                max = 1000; progress = (f * 1000).toInt(); trackCornerRadius = dp(3)
                setIndicatorColor(if (warn) Ui.RED else primary())
                layoutParams = LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(6); bottomMargin = dp(4) }
            })
            addView(TextView(context).apply { text = sub; textSize = 11f; setTextColor(secondaryText()) })
        }
    }

    // ---------------- dialogs ----------------

    private fun openAccessibility() {
        if (!p.getBoolean(Prefs.DISCLOSURE_ACCEPTED, false)) { startActivity(Intent(this, DisclosureActivity::class.java)); return }
        val running = VoiceService.instance != null
        Ui.dialog(this).setTitle(R.string.setup_access)
            .setMessage(if (running) R.string.access_help_running else R.string.access_help)
            .setPositiveButton(R.string.open_settings) { _, _ -> Health.openAccessibility(this) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun editKey() {
        val (box, input) = Ui.input(this, apiKey().ifEmpty { copiedKey().orEmpty() }, "gsk_…")
        Ui.dialog(this).setTitle(R.string.setup_key).setMessage(R.string.key_help).setView(box)
            .setPositiveButton(R.string.save) { _, _ -> saveKey(input.text.toString()) }
            .setNeutralButton(R.string.get_key) { _, _ -> showKeySteps() }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    /** Three plain steps, then Groq's key page; the copied key is picked up on return. */
    private fun showKeySteps() {
        Ui.dialog(this).setTitle(R.string.key_steps_title).setMessage(R.string.key_steps)
            .setPositiveButton(R.string.key_steps_open) { _, _ -> watchClipboard = true; open(Groq.KEYS_URL) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private var watchClipboard = false
    private var offeredKey: String? = null

    /** A Groq key on the clipboard (they start with "gsk_"), if there is one. */
    private fun copiedKey(): String? = try {
        val clip = (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        Regex("gsk_[A-Za-z0-9]{20,}").find(text)?.value
    } catch (_: Exception) { null }

    /** Back from Groq's page with a key copied: offer to use it (clipboard is readable once focused). */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || (!watchClipboard && apiKey().isNotEmpty())) return
        val k = copiedKey() ?: return
        if (k == apiKey() || k == offeredKey) return
        offeredKey = k
        watchClipboard = false
        Ui.dialog(this).setTitle(R.string.key_found_title)
            .setMessage(s(R.string.key_found_text, "${k.take(8)}…${k.takeLast(4)}"))
            .setPositiveButton(R.string.use) { _, _ -> saveKey(k) }
            .setNegativeButton(R.string.not_now, null).show()
    }

    /** Saves the key, then checks it with Groq and says whether it works. */
    private fun saveKey(raw: String) {
        val k = raw.trim()
        p.edit().putString(Prefs.API_KEY, k).apply()
        refresh()
        if (k.isEmpty()) return
        Thread {
            val problem = Groq.checkKey(k)
            runOnUiThread {
                if (problem == null) { toast(s(R.string.key_ok)); runChecks(manual = false) }
                else Ui.dialog(this).setTitle(R.string.setup_key).setMessage(s(R.string.key_bad, problem))
                    .setPositiveButton(android.R.string.ok, null).show()
            }
        }.start()
    }

    private fun editVocabulary() {
        val (box, input) = Ui.input(this, vocabText(), s(R.string.vocabulary_hint), multiLine = true)
        Ui.dialog(this).setTitle(R.string.vocabulary).setMessage(R.string.vocabulary_help).setView(box)
            .setPositiveButton(R.string.save) { _, _ -> p.edit().putString(Prefs.VOCAB, input.text.toString().trim()).apply(); refresh() }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun editCustom() {
        val (box, input) = Ui.input(this, p.getString(Prefs.CUSTOM_PROMPT, "").orEmpty(), s(R.string.custom_hint), multiLine = true)
        Ui.dialog(this).setTitle(R.string.custom_instructions).setMessage(R.string.custom_help).setView(box)
            .setPositiveButton(R.string.save) { _, _ -> p.edit().putString(Prefs.CUSTOM_PROMPT, input.text.toString().trim()).apply(); refresh() }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    /** Interface language name in its own language ("Deutsch", "日本語"). */
    private fun uiName(tag: String): String {
        val l = Locale.forLanguageTag(tag)
        return l.getDisplayName(l).replaceFirstChar { it.titlecase(l) }
    }

    private fun pickAppLanguage() {
        val tags = listOf("") + Languages.UI
        val labels = tags.map { if (it.isEmpty()) s(R.string.app_language_system) else uiName(it) }
        Ui.dialog(this).setTitle(R.string.app_language)
            .setSingleChoiceItems(labels.toTypedArray(), tags.indexOf(UiLocale.current(this)).coerceAtLeast(0)) { d, which ->
                d.dismiss()
                UiLocale.set(this, tags[which])       // AppCompat recreates the screens
                VoiceService.instance?.let { toast(s(R.string.app_language_service)) }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun showDiagnostics() {
        val svc = VoiceService.instance
        val text = buildString {
            if (svc != null && svc.lastRaw.isNotBlank()) {
                append("LAST DICTATION\nRaw (${svc.lastRaw.length}):\n${svc.lastRaw}\n\n")
                if (svc.lastClean.isNotBlank()) append("Cleaned (${svc.lastClean.length}):\n${svc.lastClean}\n\n")
            }
            append("EVENTS\n").append(svc?.log?.joinToString("\n")?.ifBlank { null } ?: "—").append("\n\n")
            append("SERVICE LOG\n").append(Health.readLog(this@MainActivity).ifBlank { "—" }).append("\n\n")
            append("EXIT REASONS\n").append(Health.exitReasons(this@MainActivity))
        }
        Ui.dialog(this).setTitle(R.string.diagnostics).setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.copy) { _, _ ->
                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("VitalySpeak diagnostics", text))
            }.show()
    }
}
