package com.kafkasl.phonewhisper

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.graphics.Rect
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread
import kotlin.math.abs

class WhisperAccessibilityService : AccessibilityService() {

    companion object {
        var instance: WhisperAccessibilityService? = null
        private const val TAG = "VitalySpeak"
        const val KEY_ONLY_WHILE_TYPING = "overlay_only_while_typing"
        private const val SAMPLE_RATE = 16000
        private const val BTN_DP = 84
        private const val PAD_DP = 22
        private const val MARGIN_DP = 8
        private const val TAP_THRESHOLD_DP = 10
        private const val RING_DP = 100          // busy spinner
        private const val WINDOW_DP = 140        // overlay window: room for the wave ring
        private const val FEEDBACK_OFFSET_DP = 64

        private const val COLOR_IDLE = 0xDD1C1C1E.toInt()
        private const val COLOR_RECORDING = 0xDDEF4444.toInt()
        private const val COLOR_BUSY = 0xDD6B6B6B.toInt()
        private const val COLOR_FEEDBACK_BG = 0xEE1C1C1E.toInt()
        private const val COLOR_RING = 0xFFE8EAED.toInt()
        private const val COLOR_ACCENT = 0xFFFF7A1A.toInt()   // modern orange ring
        private const val ACCENT_STROKE_DP = 3
        private const val PREVIEW_MODEL = "whisper-large-v3-turbo"
        /** Safety cap: auto-stops (and transcribes, nothing lost) after 4 hours. */
        private const val MAX_RECORDING_MS = 4L * 60 * 60 * 1000
        /** Don't start / keep recording with less free storage than this (≈ 25 min of audio). */
        private const val MIN_FREE_BYTES = 50L * 1024 * 1024
        const val KEY_LAST_DICTATION = "last_dictation"
        const val KEY_KEEP_AUDIO = "keep_audio"
        const val KEY_WAVE_SPEED = "wave_speed"     // percent of base speed, 100..500 (default 200)
        const val KEY_WAVE_COUNT = "wave_count"     // percent of base wave count, 100..300 (default 200)
        private const val PREVIEW_INTERVAL_MS = 2000L
        private const val PREVIEW_WINDOW_SEC = 12
        const val KEY_LIVE_PREVIEW = "live_preview"
        const val KEY_REACTION = "voice_reaction"   // 0..100, default 70
        const val KEY_DOT_SIZE = "dot_size"         // percent 60..160, default 100
    }

    private enum class State { IDLE, RECORDING, TRANSCRIBING }

    private var state = State.IDLE
        set(value) {
            field = value
            // Stop square while recording makes it obvious a second tap is needed.
            if (value != State.RECORDING) exitPocketMode()
            handler.post {
                button?.setImageResource(if (value == State.RECORDING) R.drawable.ic_stop else R.drawable.ic_mic)
                if (value == State.RECORDING) waveView?.start() else waveView?.stop()
            }
            // After dictation ends, hide the dot again if the keyboard went away meanwhile.
            if (value == State.IDLE) scheduleVisibilityCheck(400)
        }
    private var overlayView: FrameLayout? = null
    private var button: ImageView? = null
    private var spinner: ProgressBar? = null
    private var feedbackView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var feedbackLayoutParams: WindowManager.LayoutParams? = null
    private var previewView: TextView? = null
    private var waveView: WaveRingView? = null
    private var cancelView: LinearLayout? = null
    private var pocketView: FrameLayout? = null
    private var cancelParams: WindowManager.LayoutParams? = null
    private var recThread: Thread? = null
    @Volatile private var recordStartMs = 0L
    /** Dot position kept across overlay rebuilds (size change). Center-based. */
    private var savedPos: Pair<Int, Int>? = null

    private fun reaction() = prefs().getInt(KEY_REACTION, 70).coerceIn(0, 100) / 100f
    private fun waveSpeed() = prefs().getInt(KEY_WAVE_SPEED, 200).coerceIn(100, 500) / 100f
    private fun waveCount() = prefs().getInt(KEY_WAVE_COUNT, 200).coerceIn(100, 300) / 100f

    /** Settings changed: reaction applies live; size needs the overlay rebuilt. */
    fun applyAppearanceSettings() {
        handler.post {
            waveView?.reaction = reaction()
            waveView?.speed = waveSpeed()
            waveView?.waveMult = waveCount()
            if (state != State.IDLE) return@post
            layoutParams?.let { lp -> savedPos = lp.x + lp.width / 2 to lp.y + lp.height / 2 }
            removeOverlay()
            showOverlay()
            // showOverlay placed it by top-left; recentre on the old centre.
            layoutParams?.let { lp ->
                savedPos?.let { (cx, cy) ->
                    lp.x = (cx - lp.width / 2).coerceIn(0, maxOf(0, screenW - lp.width))
                    lp.y = (cy - lp.height / 2).coerceIn(0, maxOf(0, screenH - lp.height))
                    overlayView?.let { v -> try { (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(v, lp) } catch (_: Exception) {} }
                }
            }
            savedPos = null
            overlayShown = true
            scheduleVisibilityCheck(0)
        }
    }
    private var previewLayoutParams: WindowManager.LayoutParams? = null
    @Volatile private var recordingSession = 0
    @Volatile private var previewInFlight = false
    @Volatile private var previewBackoffUntil = 0L
    private var audioRecord: AudioRecord? = null
    // Crash-safe recording: audio goes to disk in ~5-min parts, transcribed in the background.
    private val store by lazy { DictationStore(java.io.File(filesDir, "sessions")) }
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var session: DictationStore.Session? = null
    private var writer: ChunkWriter? = null
    @Volatile private var ring: PcmRing? = null
    private val handler = Handler(Looper.getMainLooper())
    private val hideFeedback = Runnable {
        feedbackView?.animate()?.alpha(0f)?.setDuration(180)?.withEndAction {
            feedbackView?.visibility = View.GONE
        }?.start()
    }

    private val dp get() = resources.displayMetrics.density
    private val screenW get() = resources.displayMetrics.widthPixels
    private val screenH get() = resources.displayMetrics.heightPixels

    override fun onServiceConnected() {
        instance = this
        // Apply the event types/flags in code too, so they work even if Android kept the old
        // service config after an app update (config XML is only re-read on service restart).
        try {
            serviceInfo = serviceInfo.apply {
                eventTypes = eventTypes or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOWS_CHANGED
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
        } catch (e: Exception) { Log.w(TAG, "Could not update serviceInfo", e) }
        showOverlay()
        scheduleVisibilityCheck(0)
        GroqUsage.init(prefs())
        GroqUsage.onWarning = { msg -> logEvent(msg); showFeedback(msg, 5000) }
        // Anything on disk now is from before a crash/restart: keep it, offer retry in the app.
        worker.execute {
            store.sessions().forEach { it.isRecording = false }
            val n = store.sessions().size
            if (n > 0) logEvent("$n unfinished dictation(s) saved — retry from the app")
        }
    }

    // --- Show the dot only while typing (keyboard up / text field focused) ---

    private var overlayShown = true
    private val visibilityCheck = Runnable { updateOverlayVisibility() }
    private val visibilityRecheck = Runnable { updateOverlayVisibility() }

    /** Set from events: the last focused/clicked/selected view was an editable text field. */
    private var lastEventEditable = false

    /** Recent show/hide decisions, shown in the app under "Diagnostics". */
    val visibilityLog = ArrayDeque<String>()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.packageName == packageName) return  // ignore our own overlay
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                lastEventEditable = isEditableSource(event)
                scheduleVisibilityCheck(80)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // New screen/app: forget the old field until something new is focused.
                lastEventEditable = false
                scheduleVisibilityCheck(150)
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> scheduleVisibilityCheck(80)
        }
    }

    private fun isEditableSource(event: AccessibilityEvent): Boolean = try {
        event.source?.isEditable == true ||
            event.className?.toString()?.contains("EditText", ignoreCase = true) == true
    } catch (_: Exception) { false }

    /**
     * Debounced so switching between fields doesn't flicker; a second look a bit later
     * catches the keyboard, which finishes animating in ~300 ms after the field is focused.
     */
    private fun scheduleVisibilityCheck(delayMs: Long) {
        handler.removeCallbacks(visibilityCheck)
        handler.removeCallbacks(visibilityRecheck)
        handler.postDelayed(visibilityCheck, delayMs)
        handler.postDelayed(visibilityRecheck, delayMs + 450)
    }

    /** Called from settings when "Only while typing" is toggled. */
    fun refreshOverlayVisibility() = scheduleVisibilityCheck(0)

    private fun onlyWhileTyping() = prefs().getBoolean(KEY_ONLY_WHILE_TYPING, true)

    /** Screen bounds of the on-screen keyboard, or null if no keyboard is reported. */
    private fun keyboardBounds(): Rect? = try {
        windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            ?.let { w -> Rect().also { w.getBoundsInScreen(it) } }
    } catch (_: Exception) { null }

    /** Is an editable field input-focused in the active window or any app window? */
    private fun editableFocused(): Boolean = try {
        val roots = buildList {
            rootInActiveWindow?.let { add(it) }
            windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .mapNotNull { it.root }.forEach { add(it) }
        }
        roots.any { it.packageName != packageName &&
            it.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.isEditable == true }
    } catch (_: Exception) { false }

    private fun updateOverlayVisibility() {
        if (overlayView == null) return
        // Never hide mid-dictation; re-evaluated when the state returns to IDLE.
        if (state != State.IDLE) { setOverlayShown(true); return }
        if (!onlyWhileTyping()) { setOverlayShown(true); return }

        // Any one signal is enough: keyboard window, focused editable node, or the last
        // focus/click/cursor event coming from a text field. Phones differ in which they report.
        val kb = keyboardBounds()
        val focused = editableFocused()
        val typing = kb != null || focused || lastEventEditable
        if (kb != null) keepAboveKeyboard(kb)

        val line = "${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())} " +
            "${if (typing) "SHOW" else "hide"} · keyboard=${kb != null} field=$focused event=$lastEventEditable " +
            "app=${rootInActiveWindow?.packageName ?: "?"}"
        if (visibilityLog.firstOrNull()?.substringAfter(' ') != line.substringAfter(' ')) {
            visibilityLog.addFirst(line)
            while (visibilityLog.size > 12) visibilityLog.removeLast()
        }
        setOverlayShown(typing)
    }

    /** If the keyboard covers the dot, lift it to just above the keyboard. */
    private fun keepAboveKeyboard(kb: Rect) {
        val params = layoutParams ?: return
        val view = overlayView ?: return
        val margin = (MARGIN_DP * dp).toInt()
        val size = params.height
        if (params.y + size > kb.top - margin) {
            params.y = maxOf(margin, kb.top - size - margin)
            if (view.isAttachedToWindow) {
                try { (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(view, params) } catch (_: Exception) {}
            }
            feedbackLayoutParams?.let { fp ->
                positionFeedback(fp, params)
                feedbackView?.let { fv -> try { (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(fv, fp) } catch (_: Exception) {} }
            }
        }
    }

    /**
     * Shows/hides the dot by attaching/detaching its window. No fade: on some phones
     * (seen on Samsung) a fade-in on a previously hidden overlay window never runs, leaving
     * the dot "shown" but fully transparent. Detached = can't be seen or tapped, guaranteed.
     */
    private fun setOverlayShown(show: Boolean) {
        val view = overlayView ?: return
        val params = layoutParams ?: return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val attached = view.isAttachedToWindow
        overlayShown = show
        view.animate().cancel()
        view.alpha = 1f
        view.visibility = View.VISIBLE
        try {
            if (show && !attached) {
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                wm.addView(view, params)
            } else if (!show && attached) {
                wm.removeViewImmediate(view)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Overlay ${if (show) "attach" else "detach"} failed", e)
        }
    }
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        removeOverlay()
        super.onDestroy()
    }

    // --- Overlay ---

    private fun showOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val sizeScale = prefs().getInt(KEY_DOT_SIZE, 100).coerceIn(60, 160) / 100f
        val buttonSize = (BTN_DP * sizeScale * dp).toInt()
        val spinnerSize = (RING_DP * sizeScale * dp).toInt()
        // Window leaves room around the dot for the shape-shifting blob.
        val ringSize = (buttonSize * 2.0f).toInt()
        val pad = (PAD_DP * sizeScale * dp).toInt()
        val margin = (MARGIN_DP * dp).toInt()

        val ring = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(COLOR_RING)
            visibility = View.GONE
        }

        val wave = WaveRingView(this, buttonSize / 2f, reaction()).apply {
            visibility = View.INVISIBLE
            speed = waveSpeed(); waveMult = waveCount()
        }
        waveView = wave

        val img = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(pad, pad, pad, pad)
            background = circle(COLOR_IDLE)
        }

        val overlay = FrameLayout(this).apply {
            addView(wave, FrameLayout.LayoutParams(ringSize, ringSize, Gravity.CENTER))
            addView(ring, FrameLayout.LayoutParams(spinnerSize, spinnerSize, Gravity.CENTER))
            addView(img, FrameLayout.LayoutParams(buttonSize, buttonSize, Gravity.CENTER))
        }

        val params = WindowManager.LayoutParams(
            ringSize, ringSize,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedPos?.first?.coerceIn(0, maxOf(0, screenW - ringSize)) ?: (screenW - ringSize - margin)
            y = savedPos?.second?.coerceIn(0, maxOf(0, screenH - ringSize)) ?: (screenH / 2 - ringSize / 2)
        }

        var startX = 0; var startY = 0
        var touchX = 0f; var touchY = 0f

        overlay.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = ev.rawX; touchY = ev.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (ev.rawX - touchX).toInt()
                    params.y = startY + (ev.rawY - touchY).toInt()
                    wm.updateViewLayout(v, params)
                    feedbackLayoutParams?.let {
                        positionFeedback(it, params)
                        wm.updateViewLayout(feedbackView, it); updatePreviewPosition()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = abs(ev.rawX - touchX) + abs(ev.rawY - touchY)
                    if (moved < TAP_THRESHOLD_DP * dp) {
                        onTap()
                    } else {
                        params.x = if (params.x + ringSize / 2 > screenW / 2)
                            screenW - ringSize - margin else margin
                        wm.updateViewLayout(v, params)
                        feedbackLayoutParams?.let {
                            positionFeedback(it, params)
                            wm.updateViewLayout(feedbackView, it); updatePreviewPosition()
                        }
                    }
                    true
                }
                else -> false
            }
        }

        val feedback = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            background = pill(COLOR_FEEDBACK_BG)
            maxWidth = (currentScreenW() * 0.7).toInt()
            alpha = 0f
            visibility = View.GONE
        }

        val feedbackParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        positionFeedback(feedbackParams, params)

        // Live transcript bubble shown while recording (not touchable, never steals focus).
        val preview = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.START
            maxWidth = (currentScreenW() * 0.7).toInt()
            setPadding((14 * dp).toInt(), (10 * dp).toInt(), (14 * dp).toInt(), (10 * dp).toInt())
            background = pill(COLOR_FEEDBACK_BG).apply { setStroke((1.5f * dp).toInt(), COLOR_ACCENT) }
            visibility = View.GONE
        }
        val previewParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        positionFeedback(previewParams, params)

        wm.addView(overlay, params)
        wm.addView(feedback, feedbackParams)
        wm.addView(preview, previewParams)
        previewView = preview
        previewLayoutParams = previewParams
        overlayView = overlay
        button = img
        spinner = ring
        feedbackView = feedback
        layoutParams = params
        feedbackLayoutParams = feedbackParams
    }

    private fun removeOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView?.let {
            if (it.isAttachedToWindow) try { wm.removeView(it) } catch (_: Exception) {}
            overlayView = null
        }
        feedbackView?.let {
            wm.removeView(it)
            feedbackView = null
        }
        previewView?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
            previewView = null
        }
        previewLayoutParams = null
        cancelView?.let { if (it.isAttachedToWindow) try { wm.removeView(it) } catch (_: Exception) {} }
        cancelView = null
        cancelParams = null
        button = null
        spinner = null
        layoutParams = null
        feedbackLayoutParams = null
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke((ACCENT_STROKE_DP * dp).toInt(), COLOR_ACCENT)
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 16 * dp
        setColor(color)
    }

    private fun setAppearance(color: Int) {
        handler.post {
            // While recording the WaveRingView blob *is* the dot (red fill + orange border).
            button?.background = if (color == COLOR_RECORDING) null else circle(color)
        }
    }

    private fun setBusy(visible: Boolean) {
        handler.post {
            spinner?.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    private fun currentScreenW(): Int = try {
        (getSystemService(WINDOW_SERVICE) as WindowManager).currentWindowMetrics.bounds.width()
    } catch (_: Exception) { screenW }

    private fun currentScreenH(): Int = try {
        (getSystemService(WINDOW_SERVICE) as WindowManager).currentWindowMetrics.bounds.height()
    } catch (_: Exception) { screenH }

    /** Places a bubble window just above the dot, right edges aligned; below it if near the top. */
    private fun positionFeedback(
        bubble: WindowManager.LayoutParams,
        dot: WindowManager.LayoutParams
    ) {
        val margin = (MARGIN_DP * dp).toInt()
        val size = dot.height
        val w = currentScreenW()
        val h = currentScreenH()
        val dotCenterX = dot.x + size / 2
        val alignRight = dotCenterX > w / 2
        val horizontal = if (alignRight) Gravity.END else Gravity.START
        bubble.x = if (alignRight) maxOf(margin, w - (dot.x + size)) else maxOf(margin, dot.x)
        if (dot.y > (140 * dp).toInt()) {
            bubble.gravity = Gravity.BOTTOM or horizontal
            bubble.y = maxOf(margin, h - dot.y + margin / 2)
        } else {
            bubble.gravity = Gravity.TOP or horizontal
            bubble.y = dot.y + size + margin / 2
        }
    }

    private fun updatePreviewPosition() {
        val dotLp = layoutParams
        val cv = cancelView
        val cp = cancelParams
        if (dotLp != null && cv != null && cp != null && cv.isAttachedToWindow) {
            positionCancel(cp, dotLp)
            try { (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(cv, cp) } catch (_: Exception) {}
        }
        val pv = previewView ?: return
        val pp = previewLayoutParams ?: return
        val dot = layoutParams ?: return
        positionFeedback(pp, dot)
        try { (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(pv, pp) } catch (_: Exception) {}
    }

    private fun showPreview(text: String) {
        handler.post {
            val pv = previewView ?: return@post
            updatePreviewPosition()
            pv.text = text
            pv.visibility = View.VISIBLE
        }
    }

    private fun elapsed(): String {
        val sec = ((System.currentTimeMillis() - recordStartMs) / 1000).toInt()
        return "%d:%02d".format(sec / 60, sec % 60)
    }

    private fun hidePreview() {
        handler.post { previewView?.visibility = View.GONE; previewView?.text = "" }
    }

    /**
     * While recording, every ~2 s send the last ~12 s of audio to Groq's fast Whisper turbo
     * and show the text in the bubble. Runs in parallel; the final transcript is a separate,
     * full-quality request, so this adds no delay after you stop.
     */
    private fun startLivePreview(apiKey: String) {
        if (!prefs().getBoolean(KEY_LIVE_PREVIEW, true) || apiKey.isBlank()) return
        val session = recordingSession
        showPreview("0:00 · Listening…")
        thread {
            var lastTotal = 0L
            while (state == State.RECORDING && recordingSession == session) {
                try { Thread.sleep(PREVIEW_INTERVAL_MS) } catch (_: InterruptedException) { break }
                if (state != State.RECORDING || recordingSession != session) break
                if (previewInFlight || System.currentTimeMillis() < previewBackoffUntil) continue
                val rb = ring ?: break
                val bytesPerSec = SAMPLE_RATE * 2
                val total = rb.total
                if (total < bytesPerSec || total - lastTotal < bytesPerSec / 2) continue
                lastTotal = total
                val slice = rb.snapshot()
                val start = if (total > slice.size) 1 else 0     // ring is full → earlier audio exists
                previewInFlight = true
                GroqUsage.addAudio(PREVIEW_MODEL, slice.size / 2.0 / SAMPLE_RATE)
                TranscriberClient.transcribe(WavWriter.encode(slice), apiKey, PREVIEW_MODEL, Groq.WHISPER_HINT) { r ->
                    previewInFlight = false
                    if (r.text == null && r.error?.contains("rate", ignoreCase = true) == true) {
                        previewBackoffUntil = System.currentTimeMillis() + 10_000
                    }
                    val t = r.text?.trim().orEmpty()
                    if (t.isNotEmpty() && state == State.RECORDING && recordingSession == session) {
                        showPreview(elapsed() + " · " + (if (start > 0) "…$t" else t))
                    }
                }
            }
        }
    }

    // --- Cancel (discard without sending anything) ---

    /**
     * Two small pills beside the dot while recording: "✕ Cancel" and "☾ Screen off".
     * Separate from the big stop button and placed to the side, so they're discoverable
     * but hard to hit by accident.
     */
    private fun showCancel() {
        handler.post {
            val dot = layoutParams ?: return@post
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            fun pillButton(label: String, onClick: () -> Unit) = TextView(this).apply {
                text = label
                textSize = 13f
                setTextColor(0xFFFFFFFF.toInt())
                setPadding((14 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
                background = pill(0xE63A3A3C.toInt())
                setOnClickListener { onClick() }
            }
            val view = cancelView ?: LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(pillButton("✕  Cancel") { cancelRecording() })
                addView(pillButton("☾  Screen off") { enterPocketMode() },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        .apply { topMargin = (8 * dp).toInt() })
            }.also { cancelView = it }
            val lp = cancelParams ?: WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).also { cancelParams = it }
            positionCancel(lp, dot)
            try {
                if (view.isAttachedToWindow) wm.updateViewLayout(view, lp) else wm.addView(view, lp)
            } catch (e: Exception) { Log.w(TAG, "cancel pill", e) }
        }
    }

    // --- Screen-off ("pocket") mode ---

    /**
     * Blacks out the whole screen at minimum brightness while recording continues.
     * Why not really turn the screen off: Android may silence the microphone for apps that
     * aren't visible once the screen is off. A black, zero-brightness overlay keeps the
     * display technically on (so the mic keeps working and the screen won't auto-lock) while
     * drawing almost no power on OLED screens. It also swallows touches, so pocket taps do
     * nothing. Double-tap anywhere to come back exactly where you were.
     */
    private fun enterPocketMode() {
        handler.post {
            if (state != State.RECORDING || pocketView != null) return@post
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val label = TextView(this).apply {
                textSize = 13f
                setTextColor(0xFF3A3A3C.toInt())
                text = "● Recording — double-tap to wake"
            }
            val root = FrameLayout(this).apply {
                setBackgroundColor(0xFF000000.toInt())
                addView(label, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            }
            val detector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true
                override fun onDoubleTap(e: MotionEvent): Boolean { exitPocketMode(); return true }
            })
            root.setOnTouchListener { _, ev -> detector.onTouchEvent(ev); true }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE
            ).apply {
                screenBrightness = 0.0f            // lowest backlight
                gravity = Gravity.TOP or Gravity.START
            }
            try { wm.addView(root, lp) } catch (e: Exception) { Log.w(TAG, "pocket", e); return@post }
            pocketView = root
            logEvent("screen-off mode on")
            // Move the dim label every 20 s (no burn-in) and show elapsed time.
            val mover = object : Runnable {
                override fun run() {
                    if (pocketView !== root) return
                    label.text = "● Recording ${elapsed()} — double-tap to wake"
                    val w = maxOf(1, root.width - label.width)
                    val h = maxOf(1, root.height - label.height)
                    label.x = (Math.random() * w).toFloat()
                    label.y = (Math.random() * h).toFloat()
                    handler.postDelayed(this, 20_000)
                }
            }
            handler.postDelayed(mover, 300)
        }
    }

    private fun exitPocketMode() {
        handler.post {
            val v = pocketView ?: return@post
            pocketView = null
            try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeViewImmediate(v) } catch (_: Exception) {}
            logEvent("screen-off mode off")
        }
    }

    private fun positionCancel(lp: WindowManager.LayoutParams, dot: WindowManager.LayoutParams) {
        val gap = (4 * dp).toInt()
        val w = currentScreenW()
        val onRight = dot.x + dot.width / 2 > w / 2
        // Beside the dot, vertically centred; on the side facing the middle of the screen.
        lp.gravity = Gravity.CENTER_VERTICAL or (if (onRight) Gravity.END else Gravity.START)
        lp.x = if (onRight) w - dot.x + gap else dot.x + dot.width + gap
        lp.y = dot.y + dot.height / 2 - currentScreenH() / 2
    }

    private fun hideCancel() {
        handler.post {
            cancelView?.let { v ->
                if (v.isAttachedToWindow) try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeViewImmediate(v) } catch (_: Exception) {}
            }
        }
    }

    private fun cancelRecording() {
        if (state != State.RECORDING) return
        recordingSession++                 // drop any in-flight live preview
        state = State.IDLE
        stopPulse()
        hidePreview()
        hideCancel()
        stopRecorder()
        val sess = session
        try { writer?.close() } catch (_: Exception) {}
        session = null; writer = null; ring = null
        worker.execute { sess?.delete() }      // after any queued part job, so nothing is re-created
        setBusy(false)
        setAppearance(COLOR_IDLE)
        logEvent("recording cancelled")
        showFeedback("Cancelled — nothing was sent", 1800)
    }

    // --- Diagnostics ---

    fun logEvent(msg: String) {
        handler.post {
            val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            visibilityLog.addFirst("$ts $msg")
            while (visibilityLog.size > 12) visibilityLog.removeLast()
        }
    }

    private fun showFeedback(text: String, durationMs: Long = 2000) {
        handler.post {
            val view = feedbackView ?: return@post
            val bubbleParams = layoutParams ?: return@post
            val feedbackParams = feedbackLayoutParams ?: return@post
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager

            view.text = text
            positionFeedback(feedbackParams, bubbleParams)
            wm.updateViewLayout(view, feedbackParams)

            handler.removeCallbacks(hideFeedback)
            view.animate().cancel()
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(120).start()
            handler.postDelayed(hideFeedback, durationMs)
        }
    }

    private fun startPulse() {
        // Visual "mic is hearing you": the dot grows with your voice level (see level()).
        button?.animate()?.cancel()
        button?.alpha = 1f
    }

    /** Called from the recording thread with each audio buffer. */
    private fun level(buf: ByteArray, n: Int) {
        var sum = 0.0
        var crossings = 0
        var prev = 0
        var i = 0
        val samples = maxOf(1, n / 2)
        while (i + 1 < n) {
            val v = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
            sum += v.toDouble() * v
            if ((v >= 0) != (prev >= 0)) crossings++
            prev = v
            i += 2
        }
        val rms = kotlin.math.sqrt(sum / samples)
        // Higher "Voice reaction" = quieter speech already drives the blob to full size.
        val k = reaction()
        val loud = minOf(1.0, maxOf(0.0, (rms - 150.0) / (3200.0 - 2400.0 * k))).toFloat()
        // Zero-crossing rate as a cheap "pitch/brightness" estimate, 0..1 over ~300–3000 Hz.
        val zcrHz = crossings * SAMPLE_RATE / (2.0 * samples)
        val pitch = minOf(1.0, maxOf(0.0, (zcrHz - 300.0) / 2700.0)).toFloat()
        waveView?.update(loud, pitch)
    }

    private fun stopPulse() {
        button?.animate()?.cancel()
        button?.alpha = 1f
        button?.scaleX = 1f
        button?.scaleY = 1f
    }

    // --- State machine ---

    private fun onTap() {
        when (state) {
            State.IDLE -> startRecording()
            State.RECORDING -> stopAndTranscribe()
            State.TRANSCRIBING -> {}
        }
    }

    private fun startRecording() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            toast("Grant audio permission in VitalySpeak app"); return
        }

        val bufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        // Internal buffer holds ~2 s so a momentary stall (GC, UI work) never drops audio.
        val internalBuf = maxOf(bufSize, SAMPLE_RATE * 2 * 2)
        audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, internalBuf
            )
        } catch (_: SecurityException) { toast("Audio permission denied"); return }

        if (store.freeBytes() < MIN_FREE_BYTES) {
            audioRecord?.release(); audioRecord = null
            toast("Phone storage almost full — can't record safely"); return
        }
        val sess = store.newSession()
        session = sess
        ring = PcmRing(SAMPLE_RATE * 2 * PREVIEW_WINDOW_SEC)
        val apiKeyNow = prefs().getString(Groq.KEY_API, "") ?: ""
        writer = ChunkWriter(sess, SAMPLE_RATE) { idx ->
            // A 5-min part is complete: transcribe it now, while you keep talking.
            worker.execute { if (sess.exists()) transcribePart(sess, idx, apiKeyNow) }
        }
        audioRecord!!.startRecording()
        // Open the TLS connection to Groq while the user speaks, so the upload
        // after "stop" skips the handshake. Runs off the hot path.
        Groq.warmUp(prefs().getString(Groq.KEY_API, "") ?: "")
        recordingSession++
        state = State.RECORDING
        setBusy(false)
        setAppearance(COLOR_RECORDING)
        startPulse()

        recordStartMs = System.currentTimeMillis()
        val rec = audioRecord!!
        val out = writer!!
        val preview = ring!!
        recThread = thread {
            val buf = ByteArray(bufSize)
            // Keep reading until the recorder is stopped and drained, so the last words aren't lost.
            while (true) {
                val n = try { rec.read(buf, 0, buf.size) } catch (_: Exception) { -1 }
                if (n > 0) {
                    try { out.write(buf, n) } catch (e: Exception) {
                        Log.e(TAG, "write failed", e)
                        handler.post { if (state == State.RECORDING) { showFeedback("Storage error — transcribing what was saved", 4000); stopAndTranscribe() } }
                    }
                    preview.write(buf, n)
                    if (state == State.RECORDING) level(buf, n)
                }
                if (state == State.RECORDING) {
                    val now = System.currentTimeMillis()
                    val tooLong = now - recordStartMs > MAX_RECORDING_MS
                    // Check free space about once a minute.
                    val lowDisk = (now / 1000) % 60 == 0L && store.freeBytes() < MIN_FREE_BYTES / 2
                    if (tooLong || lowDisk) {
                        handler.post {
                            if (state == State.RECORDING) {
                                showFeedback(if (tooLong) "Reached 4 hours — transcribing everything" else "Storage almost full — transcribing everything", 5000)
                                stopAndTranscribe()
                            }
                        }
                    }
                    continue
                }
                if (n <= 0) break
            }
        }
        showCancel()
        startLivePreview(prefs().getString(Groq.KEY_API, "") ?: "")
    }

    /** Stops the recorder and waits for the reader thread to flush the last audio. */
    private fun stopRecorder() {
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { recThread?.join(800) } catch (_: InterruptedException) {}
        recThread = null
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
    }

    private fun stopAndTranscribe() {
        state = State.TRANSCRIBING
        stopPulse()
        hidePreview()
        hideCancel()
        setAppearance(COLOR_BUSY)
        setBusy(true)

        stopRecorder()

        val sess = session ?: run { reset("No audio captured"); return }
        val parts = try { writer?.close() ?: 0 } catch (e: Exception) { Log.e(TAG, "close", e); sess.partCount() }
        sess.isRecording = false
        session = null; writer = null; ring = null
        if (parts == 0) { sess.delete(); reset("No audio captured"); return }
        worker.execute { finalizeSession(sess, inject = true) }
    }

    /** Last dictation, shown in the app's Diagnostics to debug accuracy issues. */
    @Volatile var lastRaw: String = ""
    @Volatile var lastClean: String = ""

    private fun finishIdle() {
        state = State.IDLE
        setBusy(false)
        setAppearance(COLOR_IDLE)
    }

    /**
     * Transcribes one saved part (blocking, on the worker thread) and stores its text on disk.
     * Retries with backoff for network errors, rate limits and server errors; honours
     * Retry-After. Returns false only if it finally failed (audio stays saved for later).
     */
    private fun transcribePart(sess: DictationStore.Session, i: Int, apiKeyArg: String? = null): Boolean {
        if (sess.partTxt(i).exists()) return true
        val p = prefs()
        val apiKey = apiKeyArg?.ifBlank { null } ?: p.getString(Groq.KEY_API, "") ?: ""
        if (apiKey.isBlank()) return false
        val model = p.getString(Groq.KEY_STT_MODEL, Groq.DEFAULT_STT_MODEL) ?: Groq.DEFAULT_STT_MODEL
        val pcm = try { sess.partPcm(i).readBytes() } catch (e: Exception) { Log.e(TAG, "read part", e); return false }
        if (Dictation.isSilent(pcm, SAMPLE_RATE)) { safeWrite(sess, i, ""); return true }
        val audio = Dictation.trimSilence(pcm, SAMPLE_RATE)
        val context = if (i == 0) Groq.WHISPER_HINT else sess.transcriptOf(i - 1)?.takeLast(200)?.ifBlank { null } ?: Groq.WHISPER_HINT
        val wav = WavWriter.encode(audio)
        GroqUsage.addAudio(model, audio.size / 2.0 / SAMPLE_RATE)
        val delays = longArrayOf(1, 3, 8, 15, 30, 60)
        for (attempt in 0..delays.size) {
            if (!sess.exists()) return false
            val r = TranscriberClient.transcribeBlocking(wav, apiKey, model, context)
            if (r.text != null) {
                safeWrite(sess, i, r.text)
                return true
            }
            val retryable = r.httpCode == 0 || r.httpCode == 408 || r.httpCode == 429 || r.httpCode >= 500
            logEvent("part ${i + 1}: ${r.error} (attempt ${attempt + 1})")
            if (!retryable || attempt == delays.size) return false
            val wait = minOf(90L, r.retryAfterSec ?: delays[attempt])
            try { Thread.sleep(wait * 1000) } catch (_: InterruptedException) { return false }
        }
        return false
    }

    private fun safeWrite(sess: DictationStore.Session, i: Int, text: String) {
        try { if (sess.exists()) sess.writeTranscript(i, text) } catch (e: Exception) { Log.e(TAG, "save transcript", e) }
    }

    /**
     * Runs on the worker after recording stops: makes sure every part has a transcript,
     * joins them in order, cleans up, delivers, and only then deletes the saved audio.
     * If anything is missing, the session stays on disk for a retry — never a partial paste.
     */
    private fun finalizeSession(sess: DictationStore.Session, inject: Boolean) {
        val missing = sess.missingParts()
        if (inject && missing.size > 1) showFeedback("Finishing ${missing.size} of ${sess.partCount()} parts…", 3000)
        for (i in sess.missingParts()) transcribePart(sess, i)
        val joined = sess.joinedTranscript()
        if (joined == null) {
            val left = sess.missingParts().size
            logEvent("KEPT dictation ${sess.id}: $left of ${sess.partCount()} part(s) not transcribed yet")
            handler.post {
                if (inject) {
                    showFeedback("Couldn't reach Groq for $left part(s). Your recording is saved — open VitalySpeak to retry.", 6000)
                    finishIdle()
                } else toast("Still couldn't transcribe $left part(s) — kept for another retry")
            }
            return
        }
        val vocab = prefs().getString(Groq.KEY_VOCAB, Groq.DEFAULT_VOCAB) ?: ""
        val raw = Dictation.stripVocabEcho(joined, listOf(Groq.WHISPER_HINT, vocab))
        lastRaw = raw
        logEvent("transcribed ${sess.audioSeconds(SAMPLE_RATE)}s in ${sess.partCount()} part(s), ${raw.length} chars")
        try { sess.saveRaw(raw) } catch (e: Exception) { Log.e(TAG, "save raw", e) }
        // Every part is verifiably transcribed and saved: the audio may go (unless kept).
        if (!prefs().getBoolean(KEY_KEEP_AUDIO, false) && sess.rawTxt.exists()) {
            try { sess.deleteAudio() } catch (e: Exception) { Log.e(TAG, "delete audio", e) }
        }
        historyChanged()
        handleTranscriptionResult(raw, inject) { finalText, cleanOk ->
            if (cleanOk) try { sess.saveClean(finalText) } catch (_: Exception) {}
            prefs().edit().putString(KEY_LAST_DICTATION, finalText).apply()
            historyChanged()
        }
    }

    // --- History API for the app ---

    /** Set by the app's screens to refresh when an entry changes. */
    @Volatile var onHistoryChanged: (() -> Unit)? = null
    private fun historyChanged() { handler.post { onHistoryChanged?.invoke() } }

    /** All dictations except the one being recorded right now, newest first. */
    fun history(): List<DictationStore.Session> =
        store.sessions().filter { it.id != session?.id }.sortedByDescending { it.startedAt }

    fun unfinishedSessions(): List<DictationStore.Session> =
        history().filter { it.status == DictationStore.Session.Status.NEEDS_TRANSCRIPTION }

    private fun find(id: String) = history().firstOrNull { it.id == id }

    /** Transcribes whatever parts are still missing, then cleans up. Result lands in History. */
    fun retryTranscription(id: String) {
        val s = find(id) ?: return
        worker.execute { finalizeSession(s, inject = false) }
    }

    /** Re-runs cleanup on the saved raw transcript. */
    fun retryCleanup(id: String) {
        val s = find(id) ?: return
        val raw = s.raw() ?: return retryTranscription(id)
        handleTranscriptionResult(raw, inject = false) { text, ok ->
            if (ok) try { s.saveClean(text) } catch (_: Exception) {}
            else handler.post { toast("Cleanup failed again — raw text is still saved") }
            historyChanged()
        }
    }

    fun deleteSession(id: String) {
        val s = find(id) ?: return
        worker.execute { s.delete(); historyChanged() }
    }

    private fun deliver(text: String, inject: Boolean, feedback: String? = null) {
        if (inject) {
            if (feedback != null) injectText(text, feedback = feedback, feedbackDurationMs = 4000) else injectText(text)
        } else {
            toast("Saved to History (${text.length} chars)")
        }
    }

    private fun handleTranscriptionResult(text: String?, inject: Boolean = true, onDelivered: (String, Boolean) -> Unit = { _, _ -> }) {
        if (text.isNullOrBlank()) {
            handler.post { toast("No speech detected"); onDelivered("", false); if (inject) finishIdle() }
            return
        }

        val p = prefs()
        val usePostProcessing = p.getBoolean(Groq.KEY_USE_CLEANUP, true)
        val apiKey = p.getString(Groq.KEY_API, "") ?: ""
        if (!usePostProcessing || apiKey.isBlank()) {
            lastClean = ""
            handler.post { deliver(text, inject); onDelivered(text, false); if (inject) finishIdle() }
            return
        }

        val prompt = p.getString("post_processing_prompt", PostProcessor.DEFAULT_PROMPT) ?: PostProcessor.DEFAULT_PROMPT
        val llm = p.getString(Groq.KEY_LLM_MODEL, Groq.DEFAULT_LLM_MODEL) ?: Groq.DEFAULT_LLM_MODEL
        val vocab = p.getString(Groq.KEY_VOCAB, Groq.DEFAULT_VOCAB) ?: ""

        // Long texts are cleaned in sentence-aligned pieces so no reply can be cut off.
        val pieces = Dictation.splitText(text)
        val out = arrayOfNulls<String>(pieces.size)
        var fellBack = 0

        fun done() {
            val cleaned = out.joinToString(" ").trim()
            lastClean = cleaned
            handler.post {
                deliver(cleaned, inject, if (fellBack == 0) null else "Cleanup skipped for $fellBack part(s) — raw text kept there")
                onDelivered(cleaned, fellBack == 0)
                if (inject) finishIdle()
            }
        }

        fun next(i: Int, attempt: Int) {
            if (i == pieces.size) { done(); return }
            val piece = pieces[i]
            PostProcessor.process(piece, prompt, apiKey, llm, vocab) { res ->
                val cleaned = res.text
                val ok = !cleaned.isNullOrBlank() && !Dictation.cleanupLostContent(piece, cleaned)
                when {
                    ok -> { out[i] = cleaned; next(i + 1, 0) }
                    // Rate limited (e.g. tokens-per-minute on long texts): wait as Groq asks.
                    res.httpCode == 429 && attempt < 5 -> {
                        val wait = minOf(60L, res.retryAfterSec ?: (5L shl attempt))
                        logEvent("cleanup rate-limited, waiting ${wait}s (part ${i + 1}/${pieces.size})")
                        if (inject && attempt == 0) showFeedback("Groq busy — finishing cleanup in ${wait}s…", 3000)
                        handler.postDelayed({ next(i, attempt + 1) }, wait * 1000)
                    }
                    attempt == 0 -> {
                        logEvent("cleanup retry: ${res.error ?: if (cleaned.isNullOrBlank()) "empty reply" else "reply lost content"}")
                        next(i, 1)
                    }
                    else -> {
                        val why = res.error ?: if (cleaned.isNullOrBlank()) "empty reply" else "reply was ${cleaned.length}/${piece.length} chars"
                        logEvent("CLEANUP FAILED ($llm): $why — kept raw text")
                        out[i] = piece; fellBack++; next(i + 1, 0)
                    }
                }
            }
        }
        next(0, 0)
    }

    private fun reset(msg: String) {
        toast(msg)
        state = State.IDLE
        setBusy(false)
        setAppearance(COLOR_IDLE)
    }

    // --- Text injection ---

    private fun injectText(
        text: String,
        feedback: String? = "Copied to clipboard",
        feedbackDurationMs: Long = 2000
    ) {
        val clip = ClipData.newPlainText("phonewhisper", text)
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
        feedback?.let { showFeedback(it, feedbackDurationMs) }

        val candidates = findInjectionCandidates()
        Log.i(TAG, "Injecting text into ${candidates.size} candidate node(s)")

        var injected = false
        try {
            for (candidate in candidates) {
                if (tryInjectIntoNode(candidate, text)) {
                    injected = true
                    break
                }
            }
        } finally {
            candidates.forEach { it.recycle() }
        }

        Log.i(TAG, if (injected) "Text injection action reported success" else "No injection action succeeded; clipboard fallback only")
    }

    private fun findInjectionCandidates(): List<AccessibilityNodeInfo> {
        val candidates = mutableListOf<AccessibilityNodeInfo>()

        rootInActiveWindow?.let { root ->
            Log.i(TAG, "Active root: package=${root.packageName} class=${root.className}")
            collectInjectionCandidates(root, candidates)
            root.recycle()
        }

        windows
            ?.filter { it.isActive || it.isFocused }
            ?.forEach { window ->
                val root = window.root ?: return@forEach
                Log.i(
                    TAG,
                    "Window root: type=${window.type} active=${window.isActive} focused=${window.isFocused} package=${root.packageName} class=${root.className}"
                )
                collectInjectionCandidates(root, candidates)
                root.recycle()
            }

        return candidates.sortedByDescending(::candidateScore)
    }

    private fun collectInjectionCandidates(
        root: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { out += it }
        root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.let { out += it }
        collectPotentialTargets(root, out)
    }

    private fun collectPotentialTargets(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        if (isPotentialInjectionTarget(node)) {
            out += AccessibilityNodeInfo.obtain(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectPotentialTargets(child, out)
            } finally {
                child.recycle()
            }
        }
    }

    private fun isPotentialInjectionTarget(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString().orEmpty()
        return node.isFocused ||
            node.isEditable ||
            className.contains("EditText") ||
            className.contains("TerminalView") ||
            findCustomPasteAction(node) != null
    }

    private fun candidateScore(node: AccessibilityNodeInfo): Int {
        val className = node.className?.toString().orEmpty()
        var score = 0
        if (findCustomPasteAction(node) != null) score += 100
        if (className.contains("TerminalView")) score += 80
        if (node.isEditable) score += 60
        if (node.isFocused) score += 40
        if (className.contains("EditText")) score += 20
        return score
    }

    private fun tryInjectIntoNode(node: AccessibilityNodeInfo, text: String): Boolean {
        logNode("Trying node", node)

        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

        findCustomPasteAction(node)?.let { action ->
            val ok = node.performAction(action.id)
            Log.i(TAG, "Custom action '${action.label}' (${action.id}) => $ok")
            if (ok) return true
        }

        val pasteOk = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        Log.i(TAG, "ACTION_PASTE => $pasteOk")
        if (pasteOk) return true

        if (node.isEditable || node.className?.toString()?.contains("EditText") == true) {
            val current = node.text?.toString().orEmpty()
            val start = if (node.textSelectionStart >= 0) node.textSelectionStart else current.length
            val end = if (node.textSelectionEnd >= 0) node.textSelectionEnd else start
            val replacementStart = minOf(start, end)
            val replacementEnd = maxOf(start, end)
            val updated = current.replaceRange(replacementStart, replacementEnd, text)
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    updated
                )
            }
            val setTextOk = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.i(TAG, "ACTION_SET_TEXT => $setTextOk")
            if (setTextOk) return true
        }

        return false
    }

    private fun findCustomPasteAction(node: AccessibilityNodeInfo): AccessibilityNodeInfo.AccessibilityAction? =
        node.actionList.firstOrNull { action ->
            action.label?.toString()?.contains("paste", ignoreCase = true) == true
        }

    private fun logNode(prefix: String, node: AccessibilityNodeInfo) {
        val actions = node.actionList.joinToString { action ->
            action.label?.toString() ?: action.id.toString()
        }
        Log.i(
            TAG,
            "$prefix package=${node.packageName} class=${node.className} focused=${node.isFocused} editable=${node.isEditable} text=${node.text} desc=${node.contentDescription} actions=[$actions]"
        )
    }

    private fun prefs() = getSharedPreferences("phonewhisper", MODE_PRIVATE)
    private fun toast(msg: String) { handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } }
}
