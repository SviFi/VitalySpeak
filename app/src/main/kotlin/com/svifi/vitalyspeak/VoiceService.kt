package com.svifi.vitalyspeak

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * The dictation engine. Runs as an accessibility service so it can
 *  - draw the floating dot over other apps,
 *  - notice when a text field is being edited (to show the dot only then),
 *  - put the finished text into that field,
 *  - use the volume-down / headset button as the "command" key while recording.
 *
 * Flow: tap → record to disk in ~5-minute parts (each part is transcribed while you keep
 * talking) → tap → remaining parts transcribed → AI cleanup → text inserted → audio deleted.
 * A dictation is never lost: until it is fully transcribed it stays on disk and can be
 * retried from History.
 */
class VoiceService : AccessibilityService(), Overlay.Callbacks {

    companion object {
        @Volatile var instance: VoiceService? = null
            private set

        const val SAMPLE_RATE = 16000
        private const val BYTES_PER_SEC = SAMPLE_RATE * 2
        private const val PREVIEW_MODEL = "whisper-large-v3-turbo"
        private const val PREVIEW_EVERY_MS = 2000L
        private const val PREVIEW_SECONDS = 12
        private const val MAX_RECORDING_MS = 4L * 60 * 60 * 1000
        private const val MIN_FREE_BYTES = 50L * 1024 * 1024
    }

    enum class State { IDLE, RECORDING, WORKING }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val store by lazy { DictationStore(File(filesDir, "sessions")) }
    val stats by lazy { Stats(File(filesDir, "stats.tsv")) }
    private lateinit var overlay: Overlay
    private lateinit var inserter: TextInserter

    @Volatile var state = State.IDLE
        private set

    private fun prefs() = Prefs.get(this)
    private fun apiKey() = prefs().getString(Prefs.API_KEY, "").orEmpty().trim()
    private fun vocab() = prefs().getString(Prefs.VOCAB, "").orEmpty()

    override fun attachBaseContext(base: Context) = super.attachBaseContext(UiLocale.wrap(base))

    // ================= lifecycle =================

    override fun onServiceConnected() {
        instance = this
        Health.log(this, "service connected (build ${versionCode()})")
        try { Health.schedule(this) } catch (_: Exception) {}
        // Flags are also set here: after an app update Android may keep the old XML config
        // until the service restarts.
        try {
            serviceInfo = serviceInfo.apply {
                eventTypes = eventTypes or AccessibilityEvent.TYPE_VIEW_FOCUSED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED or AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            }
        } catch (_: Exception) {}
        inserter = TextInserter(this)
        overlay = Overlay(this, this)
        try { overlay.build() } catch (e: Exception) { Health.log(this, "overlay failed: $e") }
        checkFieldSoon(0)
        GroqUsage.onWarning = { audio, pct, model ->
            val msg = getString(if (audio) R.string.usage_warn_audio else R.string.usage_warn_requests, pct, model)
            note(msg); overlay.feedback(msg, 5000)
        }
        worker.execute {
            // Anything still on disk was interrupted (crash, update, reboot): keep it for retry.
            val all = store.sessions()
            all.forEach { it.isRecording = false }
            if (!stats.exists()) all.forEach { s ->
                s.raw()?.takeIf { it.isNotBlank() }?.let { stats.record(s.startedAt, s.durationSec(), Stats.wordCount(it)) }
            }
            if (all.isNotEmpty()) note("${all.size} saved dictation(s) on disk")
        }
    }

    private fun versionCode(): Long = try { packageManager.getPackageInfo(packageName, 0).longVersionCode } catch (_: Exception) { 0 }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        Health.log(this, "service unbound (accessibility turned off, app updated or process stopping)")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Health.log(this, "service destroyed")
        instance = null
        if (::overlay.isInitialized) overlay.destroy()
        super.onDestroy()
    }

    // ================= diagnostics =================

    /** Recent events and show/hide decisions, shown in the app's Diagnostics. */
    val log = ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun note(msg: String) = main.post {
        log.addFirst("${clock.format(Date())} $msg")
        while (log.size > 15) log.removeLast()
    }

    @Volatile var lastRaw = ""
        private set
    @Volatile var lastClean = ""
        private set

    // ================= settings changed in the app =================

    fun onLookChanged() = main.post { overlay.applyLook(); if (state == State.IDLE) { overlay.rebuild(); checkFieldSoon(0) } }
    fun onVisibilitySettingChanged() = checkFieldSoon(0)

    // ================= when to show the dot =================

    private var lastEventWasField = false
    private val check = Runnable { updateVisibility() }
    private val recheck = Runnable { updateVisibility() }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.packageName == packageName) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> { lastEventWasField = isField(event); checkFieldSoon(80) }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> { lastEventWasField = false; checkFieldSoon(150) }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> checkFieldSoon(80)
        }
    }

    private fun isField(e: AccessibilityEvent) = try {
        e.source?.isEditable == true || e.className?.toString()?.contains("EditText", true) == true
    } catch (_: Exception) { false }

    /** Debounced; looks again shortly after, because the keyboard animates in for ~300 ms. */
    private fun checkFieldSoon(delay: Long) {
        main.removeCallbacks(check); main.removeCallbacks(recheck)
        main.postDelayed(check, delay)
        main.postDelayed(recheck, delay + 450)
    }

    private fun keyboard(): Rect? = try {
        windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }?.let { w -> Rect().also { w.getBoundsInScreen(it) } }
    } catch (_: Exception) { null }

    private fun fieldFocused(): Boolean = try {
        val roots = buildList {
            rootInActiveWindow?.let { add(it) }
            windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.mapNotNullTo(this) { it.root }
        }
        roots.any { it.packageName != packageName && it.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.isEditable == true }
    } catch (_: Exception) { false }

    private fun updateVisibility() {
        if (!::overlay.isInitialized) return
        // Never hide mid-dictation; looked at again when it returns to idle.
        if (state != State.IDLE || !prefs().getBoolean(Prefs.ONLY_WHILE_TYPING, true)) { overlay.setShown(true); return }
        // Phones differ in what they report, so any one signal counts.
        val kb = keyboard()
        val field = fieldFocused()
        val typing = kb != null || field || lastEventWasField
        kb?.let { overlay.keepAbove(it) }
        val line = "${if (typing) "SHOW" else "hide"} · keyboard=${kb != null} field=$field event=$lastEventWasField app=${rootInActiveWindow?.packageName ?: "?"}"
        if (log.firstOrNull()?.substringAfter(' ') != line) note(line)
        overlay.setShown(typing)
    }

    // ================= overlay callbacks =================

    override fun onDotTap() {
        when (state) {
            State.IDLE -> startRecording()
            State.RECORDING -> stopRecording()
            State.WORKING -> {}
        }
    }

    override fun onDotLongPress() {
        if (state != State.IDLE) return
        try {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        } catch (_: Exception) {}
    }

    override fun onCommandToggle() = toggleCommand()
    override fun onCancel() = cancelRecording()
    override fun isIdle() = state == State.IDLE

    override fun elapsedLabel(): String {
        val s = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    private fun setState(s: State) {
        state = s
        main.post {
            overlay.setMode(when (s) { State.IDLE -> Overlay.Mode.IDLE; State.RECORDING -> Overlay.Mode.RECORDING; State.WORKING -> Overlay.Mode.WORKING })
            if (s == State.IDLE) checkFieldSoon(400)
        }
    }

    private fun toast(msg: String) = main.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    // ================= hardware command key =================

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (state != State.RECORDING || !prefs().getBoolean(Prefs.COMMAND_KEYS, true)) return false
        if (event.keyCode !in setOf(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) return false
        if (event.action == KeyEvent.ACTION_UP) toggleCommand()
        return true
    }

    // ================= recording =================

    private var recorder: AudioRecord? = null
    private var reader: Thread? = null
    @Volatile private var session: DictationStore.Session? = null
    private var parts: ChunkWriter? = null
    @Volatile private var recent: PcmRing? = null
    @Volatile private var startedAt = 0L
    /** Increments per recording, so stale preview replies are ignored. */
    @Volatile private var take = 0
    /** Audio position (s) where the open command began, or null. */
    @Volatile private var commandFrom: Double? = null

    private fun audioPos(): Double = (recent?.total ?: 0L) / BYTES_PER_SEC.toDouble()

    private fun startRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast(getString(R.string.err_mic_permission)); return
        }
        if (apiKey().isEmpty()) { overlay.feedback(getString(R.string.err_no_key), 3500); return }
        if (store.freeBytes() < MIN_FREE_BYTES) { toast(getString(R.string.err_storage_full)); return }
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            // ~2 s of internal buffer: a short stall on the reading side never drops audio.
            AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, BYTES_PER_SEC * 2))
        } catch (_: SecurityException) { toast(getString(R.string.err_mic_permission)); return }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); toast(getString(R.string.err_mic_busy)); return }

        val sess = store.newSession()
        val key = apiKey()
        val ring = PcmRing(BYTES_PER_SEC * PREVIEW_SECONDS)
        val writer = ChunkWriter(sess, SAMPLE_RATE) { idx ->
            // A part is complete: transcribe it while the user keeps talking.
            worker.execute { if (sess.exists()) transcribePart(sess, idx, key) }
        }
        session = sess; parts = writer; recent = ring; recorder = rec
        commandFrom = null
        rec.startRecording()
        Groq.warmUp(key)                      // TLS handshake now, not after "stop"
        take++
        startedAt = System.currentTimeMillis()
        setState(State.RECORDING)
        overlay.showControls()

        reader = Thread {
            val buf = ByteArray(minBuf)
            // Keeps reading after stop() until the recorder is drained, so the last words stay.
            while (true) {
                val n = try { rec.read(buf, 0, buf.size) } catch (_: Exception) { -1 }
                if (n > 0) {
                    try { writer.write(buf, n) } catch (_: Exception) {
                        main.post { if (state == State.RECORDING) { overlay.feedback(getString(R.string.err_storage_write), 4000); stopRecording() } }
                    }
                    ring.write(buf, n)
                    if (state == State.RECORDING) meter(buf, n)
                }
                if (state == State.RECORDING) { guardLimits(); continue }
                if (n <= 0) break
            }
        }.apply { name = "recorder"; start() }
        startPreview(key)
    }

    private var lastDiskCheck = 0L

    /** Stops on its own (and transcribes everything) after 4 hours or when storage runs out. */
    private fun guardLimits() {
        val now = System.currentTimeMillis()
        val tooLong = now - startedAt > MAX_RECORDING_MS
        var lowDisk = false
        if (now - lastDiskCheck > 60_000) { lastDiskCheck = now; lowDisk = store.freeBytes() < MIN_FREE_BYTES / 2 }
        if (tooLong || lowDisk) main.post {
            if (state == State.RECORDING) {
                overlay.feedback(getString(if (tooLong) R.string.stop_max_length else R.string.stop_low_storage), 5000)
                stopRecording()
            }
        }
    }

    /** Voice level → loudness 0..1 and a rough brightness (zero-crossing rate) for the shape. */
    private fun meter(buf: ByteArray, n: Int) {
        var energy = 0.0
        var flips = 0
        var last = 0
        val count = maxOf(1, n / 2)
        var i = 0
        while (i + 1 < n) {
            val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
            energy += s.toDouble() * s
            if ((s < 0) != (last < 0)) flips++
            last = s
            i += 2
        }
        val sensitivity = prefs().getInt(Appearance.KEY_REACTION, Appearance.DEF_REACTION).coerceIn(0, 100) / 100.0
        val loud = ((sqrt(energy / count) - 150) / (3200 - 2400 * sensitivity)).coerceIn(0.0, 1.0)
        val hz = flips * SAMPLE_RATE / (2.0 * count)
        overlay.level(loud.toFloat(), ((hz - 300) / 2700).coerceIn(0.0, 1.0).toFloat())
    }

    private fun releaseRecorder() {
        try { recorder?.stop() } catch (_: Exception) {}
        try { reader?.join(800) } catch (_: InterruptedException) {}
        try { recorder?.release() } catch (_: Exception) {}
        reader = null; recorder = null
    }

    private fun stopRecording() {
        if (state != State.RECORDING) return
        // An open command ends with the recording (+1 s so its last word still counts).
        commandFrom?.let { from -> try { session?.addCommand(from, audioPos() + 1.0) } catch (_: Exception) {} }
        commandFrom = null
        setState(State.WORKING)
        overlay.hidePreview()
        releaseRecorder()
        val sess = session
        val count = try { parts?.close() ?: 0 } catch (_: Exception) { sess?.partCount() ?: 0 }
        session = null; parts = null; recent = null
        if (sess == null || count == 0) {
            sess?.delete()
            toast(getString(R.string.err_no_audio)); setState(State.IDLE); return
        }
        sess.isRecording = false
        worker.execute { finish(sess, insert = true) }
    }

    private fun cancelRecording() {
        if (state != State.RECORDING) return
        take++
        commandFrom = null
        state = State.IDLE                       // stops the reader loop before release
        releaseRecorder()
        val sess = session
        try { parts?.close() } catch (_: Exception) {}
        session = null; parts = null; recent = null
        worker.execute { sess?.delete() }       // queued after any part job, so nothing reappears
        setState(State.IDLE)
        overlay.hidePreview()
        overlay.feedback(getString(R.string.cancelled), 1800)
        note("recording cancelled")
    }

    // ================= commands =================

    fun toggleCommand() = main.post {
        if (state != State.RECORDING) return@post
        val sess = session ?: return@post
        val from = commandFrom
        if (from == null) {
            commandFrom = audioPos()
            note("command from %.1fs".format(commandFrom))
        } else {
            val to = audioPos()
            try { sess.addCommand(from, to) } catch (_: Exception) {}
            commandFrom = null
            note("command %.1f–%.1fs".format(from, to))
        }
        overlay.showCommand(commandFrom != null)
    }

    // ================= live preview =================

    @Volatile private var previewBusy = false
    @Volatile private var previewPauseUntil = 0L

    /**
     * Every ~2 s the last ~12 s of audio go to the fast turbo model and the text is shown
     * next to the dot. Separate from the real transcription, so it never delays the result.
     */
    private fun startPreview(key: String) {
        if (!prefs().getBoolean(Prefs.LIVE_PREVIEW, true)) return
        val myTake = take
        val langs = Languages.selected(this)
        val hint = Languages.whisperHint(langs)
        val lang = langs.singleOrNull()
        overlay.preview(elapsedLabel() + " · " + getString(R.string.listening))
        Thread {
            var sentUpTo = 0L
            while (state == State.RECORDING && take == myTake) {
                try { Thread.sleep(PREVIEW_EVERY_MS) } catch (_: InterruptedException) { break }
                if (state != State.RECORDING || take != myTake) break
                if (previewBusy || System.currentTimeMillis() < previewPauseUntil) continue
                val ring = recent ?: break
                val total = ring.total
                if (total < BYTES_PER_SEC || total - sentUpTo < BYTES_PER_SEC / 2) continue
                sentUpTo = total
                val pcm = ring.snapshot()
                val clipped = total > pcm.size
                previewBusy = true
                GroqUsage.addAudio(PREVIEW_MODEL, pcm.size / BYTES_PER_SEC.toDouble())
                SpeechApi.transcribeAsync(Wav.fromPcm16(pcm), key, PREVIEW_MODEL, hint, lang) { r ->
                    previewBusy = false
                    if (r.httpCode == 429) previewPauseUntil = System.currentTimeMillis() + 10_000
                    val t = r.text?.trim().orEmpty()
                    if (t.isNotEmpty() && state == State.RECORDING && take == myTake) {
                        val mark = if (commandFrom != null) "⌘ " else ""
                        overlay.preview(elapsedLabel() + " · " + mark + (if (clipped) "…" else "") + t)
                    }
                }
            }
        }.apply { name = "preview"; start() }
    }

    // ================= transcription =================

    /**
     * Transcribes one saved part on the worker thread and stores the text (and word timings).
     * Network errors, rate limits and server errors are retried with growing pauses, honouring
     * Retry-After. Returns false if it finally failed; the audio stays for a later retry.
     */
    private fun transcribePart(sess: DictationStore.Session, i: Int, keyArg: String? = null): Boolean {
        if (sess.partTxt(i).exists()) return true
        val key = keyArg?.ifBlank { null } ?: apiKey()
        if (key.isEmpty()) return false
        val model = prefs().getString(Prefs.STT_MODEL, null) ?: Groq.DEFAULT_STT_MODEL
        val pcm = try { sess.partPcm(i).readBytes() } catch (_: Exception) { return false }
        if (Dictation.isSilent(pcm, SAMPLE_RATE)) { saveText(sess, i, ""); return true }

        val keep = Dictation.trimRange(pcm, SAMPLE_RATE)
        val audio = if (keep.first == 0 && keep.last + 1 == pcm.size) pcm else pcm.copyOfRange(keep.first, keep.last + 1)
        val offset = sess.partOffsetSec(i, SAMPLE_RATE) + keep.first / BYTES_PER_SEC.toDouble()
        val langs = Languages.selected(this)
        // Context: the languages' sample phrases first time, then the end of the previous part.
        val prompt = sess.takeIf { i > 0 }?.transcriptOf(i - 1)?.takeLast(200)?.ifBlank { null } ?: Languages.whisperHint(langs)
        val wav = Wav.fromPcm16(audio)
        GroqUsage.addAudio(model, audio.size / BYTES_PER_SEC.toDouble())

        var lang: String? = langs.singleOrNull()
        val waits = longArrayOf(1, 3, 8, 15, 30, 60)
        var attempt = 0
        while (sess.exists()) {
            val r = SpeechApi.transcribe(wav, key, model, prompt, lang)
            if (r.text != null) {
                // Several languages: if Whisper settled on one the user doesn't speak (it happens
                // with accents and short clips), do it again in the primary language.
                val heard = r.language
                if (lang == null && heard != null && langs.size > 1 && langs.none { Languages.englishName(it) == heard }) {
                    note("part ${i + 1}: heard \"$heard\", redoing as ${langs[0]}")
                    lang = langs[0]
                    GroqUsage.addAudio(model, audio.size / BYTES_PER_SEC.toDouble())
                    continue
                }
                r.units?.let { u -> try { sess.writeUnits(i, u.map { Dictation.Timed(it.start + offset, it.end + offset, it.text) }) } catch (_: Exception) {} }
                saveText(sess, i, r.text)
                return true
            }
            val again = r.httpCode == 0 || r.httpCode == 408 || r.httpCode == 429 || r.httpCode >= 500
            note("part ${i + 1}: ${r.error} (try ${attempt + 1})")
            if (!again || attempt >= waits.size) return false
            val pause = minOf(90L, r.retryAfterSec ?: waits[attempt])
            attempt++
            try { Thread.sleep(pause * 1000) } catch (_: InterruptedException) { return false }
        }
        return false
    }

    private fun saveText(sess: DictationStore.Session, i: Int, text: String) {
        try { if (sess.exists()) sess.writeTranscript(i, text) } catch (_: Exception) {}
    }

    /**
     * After recording (worker thread): transcribe what's missing, join the parts, save the raw
     * text, delete the audio (unless kept), then clean up and deliver. If any part still can't
     * be transcribed, everything stays on disk; nothing partial is inserted.
     */
    private fun finish(sess: DictationStore.Session, insert: Boolean) {
        val missing = sess.missingParts()
        if (insert && missing.size > 1) overlay.feedback(getString(R.string.finishing_parts, missing.size, sess.partCount()), 3000)
        for (i in missing) transcribePart(sess, i)
        val joined = sess.joinedTranscript()
        if (joined == null) {
            val left = sess.missingParts().size
            note("kept ${sess.id}: $left part(s) not transcribed")
            main.post {
                if (insert) { overlay.feedback(getString(R.string.err_transcribe_kept, left), 6000); setState(State.IDLE) }
                else toast(getString(R.string.err_retry_failed, left))
            }
            return
        }
        val blocks = commandBlocks(sess)
        val hint = Languages.whisperHint(Languages.selected(this))
        val raw = if (blocks != null) Dictation.toMarked(blocks) else Dictation.stripVocabEcho(joined, listOf(hint, vocab()))
        lastRaw = raw
        note("transcribed ${sess.audioSeconds(SAMPLE_RATE)}s in ${sess.partCount()} part(s), ${raw.length} chars")
        val first = !sess.rawTxt.exists()
        try { sess.saveRaw(raw) } catch (_: Exception) {}
        if (first && raw.isNotBlank()) try { stats.record(sess.startedAt, sess.durationSec(), Stats.wordCount(raw)) } catch (_: Exception) {}
        if (!prefs().getBoolean(Prefs.KEEP_AUDIO, false) && sess.rawTxt.exists()) try { sess.deleteAudio() } catch (_: Exception) {}
        historyChanged()

        val after: (String, Boolean) -> Unit = { text, cleaned ->
            if (cleaned) try { sess.saveClean(text) } catch (_: Exception) {}
            prefs().edit().putString(Prefs.LAST_DICTATION, text).apply()
            historyChanged()
        }
        if (blocks != null) applyCommands(blocks, insert, after) else cleanUp(raw, insert, after)
    }

    /** Content/command blocks if the user marked commands, else null (word timings needed). */
    private fun commandBlocks(sess: DictationStore.Session): List<Dictation.Block>? {
        val marks = sess.commands()
        if (marks.isEmpty()) return null
        val words = ArrayList<Dictation.Timed>()
        for (i in 0 until maxOf(sess.partCount(), sess.knownParts())) {
            val u = sess.units(i)
            if (u != null) words += u
            else sess.transcriptOf(i)?.takeIf { it.isNotBlank() }?.let { words += Dictation.Timed(-10.0, -10.0, it) }
        }
        val blocks = Dictation.splitByCommands(words, marks)
        return blocks.takeIf { b -> b.any { it.isCommand } }
    }

    // ================= cleanup =================

    private fun languageNames() = Languages.selected(this).map { Languages.englishName(it).replaceFirstChar(Char::titlecase) }
    private fun llm() = prefs().getString(Prefs.LLM_MODEL, null) ?: Groq.DEFAULT_LLM_MODEL

    /** Normal dictation: cleaned in sentence-aligned pieces so no reply is ever cut off. */
    private fun cleanUp(raw: String, insert: Boolean, after: (String, Boolean) -> Unit) {
        if (raw.isBlank()) {
            main.post { toast(getString(R.string.no_speech)); after("", false); if (insert) setState(State.IDLE) }
            return
        }
        val key = apiKey()
        if (!prefs().getBoolean(Prefs.CLEANUP, true) || key.isEmpty()) {
            lastClean = ""
            main.post { deliver(raw, insert, null); after(raw, false); if (insert) setState(State.IDLE) }
            return
        }
        val p = prefs()
        val system = CleanupApi.systemPrompt(CleanupApi.style(p.getString(Prefs.STYLE, null)), languageNames(),
            p.getString(Prefs.CUSTOM_PROMPT, "").orEmpty(), vocab())
        val model = llm()
        val pieces = Dictation.splitText(raw)
        val out = arrayOfNulls<String>(pieces.size)
        var kept = 0

        fun step(i: Int, attempt: Int) {
            if (i == pieces.size) {
                val text = out.joinToString(" ").trim()
                lastClean = text
                main.post {
                    deliver(text, insert, if (kept == 0) null else getString(R.string.cleanup_partial, kept))
                    after(text, kept == 0)
                    if (insert) setState(State.IDLE)
                }
                return
            }
            val piece = pieces[i]
            CleanupApi.run(system, "<transcript>\n$piece\n</transcript>", key, model) { r ->
                val t = r.text
                val good = !t.isNullOrBlank() && !Dictation.cleanupLostContent(piece, t)
                when {
                    good -> { out[i] = t; step(i + 1, 0) }
                    r.httpCode == 429 && attempt < 5 -> {
                        val wait = minOf(60L, r.retryAfterSec ?: (5L shl attempt))
                        note("cleanup rate-limited, waiting ${wait}s")
                        if (insert && attempt == 0) overlay.feedback(getString(R.string.groq_busy, wait), 3000)
                        main.postDelayed({ step(i, attempt + 1) }, wait * 1000)
                    }
                    attempt == 0 -> step(i, 1)
                    else -> {
                        note("cleanup failed ($model): ${r.error ?: "reply ${t?.length ?: 0}/${piece.length} chars"}")
                        out[i] = piece; kept++; step(i + 1, 0)
                    }
                }
            }
        }
        step(0, 0)
    }

    /**
     * Dictation with commands: tagged blocks go to the model (in windows for long recordings).
     * A window that fails keeps its raw content and lists its commands as not applied.
     */
    private fun applyCommands(blocks: List<Dictation.Block>, insert: Boolean, after: (String, Boolean) -> Unit) {
        val key = apiKey()
        val model = llm()
        val system = CleanupApi.commandPrompt(languageNames(), vocab())
        val windows = Dictation.windows(blocks)
        val bodies = arrayOfNulls<String>(windows.size)
        val pending = ArrayList<String>()
        var failed = 0
        if (insert) overlay.feedback(getString(R.string.applying_commands), 2500)

        fun giveUp(i: Int, w: List<Dictation.Block>) {
            bodies[i] = w.filter { !it.isCommand }.joinToString("\n\n") { it.text }
            w.filter { it.isCommand }.forEach { pending += "- (${getString(R.string.not_applied)}) ${it.text}" }
            failed++
        }

        fun step(i: Int, attempt: Int) {
            if (i == windows.size) {
                val body = bodies.filterNotNull().filter { it.isNotBlank() }.joinToString("\n\n")
                val text = if (pending.isEmpty()) body else "$body\n\nPending actions\n${pending.joinToString("\n")}"
                lastClean = text
                main.post {
                    deliver(text, insert, if (failed == 0) null else getString(R.string.commands_partial))
                    after(text, failed == 0)
                    if (insert) setState(State.IDLE)
                }
                return
            }
            val w = windows[i]
            if (key.isEmpty()) { giveUp(i, w); step(i + 1, 0); return }
            val contentLen = w.filter { !it.isCommand }.sumOf { it.text.length }
            CleanupApi.run(system, Dictation.toTagged(w), key, model) { r ->
                val t = r.text
                // Commands may shorten text on purpose; only a reply that lost most of it is rejected.
                val good = !t.isNullOrBlank() && !(contentLen > 200 && t.length < contentLen / 4)
                when {
                    good -> { val (b, items) = Dictation.splitPending(t!!); bodies[i] = b; pending += items; step(i + 1, 0) }
                    r.httpCode == 429 && attempt < 5 -> {
                        val wait = minOf(60L, r.retryAfterSec ?: (5L shl attempt))
                        main.postDelayed({ step(i, attempt + 1) }, wait * 1000)
                    }
                    attempt == 0 -> step(i, 1)
                    else -> { note("commands failed: ${r.error ?: "short reply"}"); giveUp(i, w); step(i + 1, 0) }
                }
            }
        }
        step(0, 0)
    }

    // ================= delivery =================

    private fun deliver(text: String, insert: Boolean, warning: String?) {
        if (!insert) { toast(getString(R.string.saved_to_history)); return }
        if (text.isBlank()) return
        val msg = when (inserter.insert(text)) {
            TextInserter.Result.INSERTED, TextInserter.Result.PASTED -> warning
            TextInserter.Result.CLIPBOARD -> getString(R.string.copied_to_clipboard)
        }
        msg?.let { overlay.feedback(it, 4000) }
    }

    // ================= history (used by the app's screens) =================

    /** Set by the app's screens to refresh when an entry changes. */
    @Volatile var onHistoryChanged: (() -> Unit)? = null
    private fun historyChanged() = main.post { onHistoryChanged?.invoke() }

    /** Saved dictations except the one being recorded, newest first. */
    fun history(): List<DictationStore.Session> =
        store.sessions().filter { it.id != session?.id }.sortedByDescending { it.startedAt }

    fun unfinished(): List<DictationStore.Session> =
        history().filter { it.status == DictationStore.Session.Status.NEEDS_TRANSCRIPTION }

    private fun find(id: String) = history().firstOrNull { it.id == id }

    fun retryTranscription(id: String) { val s = find(id) ?: return; worker.execute { finish(s, insert = false) } }

    fun retryCleanup(id: String) {
        val s = find(id) ?: return
        val raw = s.raw() ?: return retryTranscription(id)
        val after: (String, Boolean) -> Unit = { text, ok ->
            if (ok) try { s.saveClean(text) } catch (_: Exception) {} else toast(getString(R.string.cleanup_failed_again))
            historyChanged()
        }
        worker.execute {
            val blocks = commandBlocks(s)
            if (blocks != null) applyCommands(blocks, false, after) else cleanUp(raw, false, after)
        }
    }

    fun delete(id: String) { val s = find(id) ?: return; worker.execute { s.delete(); historyChanged() } }
}
