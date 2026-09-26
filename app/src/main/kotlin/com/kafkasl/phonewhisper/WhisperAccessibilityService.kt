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
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread
import kotlin.math.abs

class WhisperAccessibilityService : AccessibilityService() {

    companion object {
        var instance: WhisperAccessibilityService? = null
        private const val TAG = "VitalySpeak"
        const val KEY_ONLY_WHILE_TYPING = "overlay_only_while_typing"
        private const val SAMPLE_RATE = 16000
        private const val BTN_DP = 44
        private const val PAD_DP = 10
        private const val MARGIN_DP = 8
        private const val TAP_THRESHOLD_DP = 10
        private const val RING_DP = 56
        private const val FEEDBACK_OFFSET_DP = 64

        private const val COLOR_IDLE = 0xDD1C1C1E.toInt()
        private const val COLOR_RECORDING = 0xDDEF4444.toInt()
        private const val COLOR_BUSY = 0xDD6B6B6B.toInt()
        private const val COLOR_FEEDBACK_BG = 0xEE1C1C1E.toInt()
        private const val COLOR_RING = 0xFFE8EAED.toInt()
    }

    private enum class State { IDLE, RECORDING, TRANSCRIBING }

    private var state = State.IDLE
        set(value) {
            field = value
            // After dictation ends, hide the dot again if the keyboard went away meanwhile.
            if (value == State.IDLE) scheduleVisibilityCheck(400)
        }
    private var overlayView: FrameLayout? = null
    private var button: ImageView? = null
    private var spinner: ProgressBar? = null
    private var feedbackView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var feedbackLayoutParams: WindowManager.LayoutParams? = null
    private var audioRecord: AudioRecord? = null
    private var pcmStream: ByteArrayOutputStream? = null
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
        val buttonSize = (BTN_DP * dp).toInt()
        val ringSize = (RING_DP * dp).toInt()
        val pad = (PAD_DP * dp).toInt()
        val margin = (MARGIN_DP * dp).toInt()

        val ring = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(COLOR_RING)
            visibility = View.GONE
        }

        val img = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(pad, pad, pad, pad)
            background = circle(COLOR_IDLE)
        }

        val overlay = FrameLayout(this).apply {
            addView(ring, FrameLayout.LayoutParams(ringSize, ringSize, Gravity.CENTER))
            addView(img, FrameLayout.LayoutParams(buttonSize, buttonSize, Gravity.CENTER))
        }

        val params = WindowManager.LayoutParams(
            ringSize, ringSize,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenW - ringSize - margin
            y = screenH / 2 - ringSize / 2
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
                        wm.updateViewLayout(feedbackView, it)
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
                            wm.updateViewLayout(feedbackView, it)
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

        wm.addView(overlay, params)
        wm.addView(feedback, feedbackParams)
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
        button = null
        spinner = null
        layoutParams = null
        feedbackLayoutParams = null
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color)
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 16 * dp
        setColor(color)
    }

    private fun setAppearance(color: Int) {
        handler.post { button?.background = circle(color) }
    }

    private fun setBusy(visible: Boolean) {
        handler.post {
            spinner?.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    private fun positionFeedback(
        feedbackParams: WindowManager.LayoutParams,
        bubbleParams: WindowManager.LayoutParams
    ) {
        val margin = (MARGIN_DP * dp).toInt()
        val offset = (FEEDBACK_OFFSET_DP * dp).toInt()
        feedbackParams.x = maxOf(margin, bubbleParams.x - offset)
        feedbackParams.y = maxOf(margin, bubbleParams.y - margin)
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
        button?.let {
            it.animate().alpha(0.4f).setDuration(500).withEndAction {
                it.animate().alpha(1f).setDuration(500).withEndAction {
                    if (state == State.RECORDING) startPulse()
                }.start()
            }.start()
        }
    }

    private fun stopPulse() {
        button?.animate()?.cancel()
        button?.alpha = 1f
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
        audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
        } catch (_: SecurityException) { toast("Audio permission denied"); return }

        pcmStream = ByteArrayOutputStream()
        audioRecord!!.startRecording()
        // Open the TLS connection to Groq while the user speaks, so the upload
        // after "stop" skips the handshake. Runs off the hot path.
        Groq.warmUp(prefs().getString(Groq.KEY_API, "") ?: "")
        state = State.RECORDING
        setBusy(false)
        setAppearance(COLOR_RECORDING)
        startPulse()

        thread {
            val buf = ByteArray(bufSize)
            while (state == State.RECORDING) {
                val n = audioRecord?.read(buf, 0, buf.size) ?: break
                if (n > 0) pcmStream?.write(buf, 0, n)
            }
        }
    }

    private fun stopAndTranscribe() {
        state = State.TRANSCRIBING
        stopPulse()
        setAppearance(COLOR_BUSY)
        setBusy(true)

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        val pcm = pcmStream?.toByteArray() ?: ByteArray(0)
        pcmStream = null

        if (pcm.isEmpty()) { reset("No audio captured"); return }

        transcribeApi(pcm)
    }

    private fun transcribeApi(pcm: ByteArray) {
        val wav = WavWriter.encode(pcm)
        val p = prefs()
        val apiKey = p.getString(Groq.KEY_API, "") ?: ""
        if (apiKey.isBlank()) { reset("Set Groq API key in VitalySpeak app"); return }

        TranscriberClient.transcribe(
            wav, apiKey,
            model = p.getString(Groq.KEY_STT_MODEL, Groq.DEFAULT_STT_MODEL) ?: Groq.DEFAULT_STT_MODEL,
            vocabulary = p.getString(Groq.KEY_VOCAB, Groq.DEFAULT_VOCAB) ?: ""
        ) { result ->
            if (result.text != null && result.text.isNotBlank()) {
                handleTranscriptionResult(result.text)
            } else {
                handler.post {
                    toast("Error: ${result.error ?: "empty transcript"}")
                    state = State.IDLE
                    setBusy(false)
                    setAppearance(COLOR_IDLE)
                }
            }
        }
    }

    private fun handleTranscriptionResult(text: String?) {
        if (text.isNullOrBlank()) {
            handler.post {
                toast("No speech detected")
                state = State.IDLE
                setBusy(false)
                setAppearance(COLOR_IDLE)
            }
            return
        }

        val usePostProcessing = prefs().getBoolean(Groq.KEY_USE_CLEANUP, true)
        val apiKey = prefs().getString(Groq.KEY_API, "") ?: ""

        if (usePostProcessing) {
            if (apiKey.isBlank()) {
                handler.post {
                    toast("Post-processing needs API key. Using raw text.")
                    injectText(text)
                    state = State.IDLE
                    setBusy(false)
                    setAppearance(COLOR_IDLE)
                }
                return
            }

            val prompt = prefs().getString("post_processing_prompt", PostProcessor.DEFAULT_PROMPT) ?: PostProcessor.DEFAULT_PROMPT
            
            val llm = prefs().getString(Groq.KEY_LLM_MODEL, Groq.DEFAULT_LLM_MODEL) ?: Groq.DEFAULT_LLM_MODEL
            PostProcessor.process(text, prompt, apiKey, llm) { result ->
                handler.post {
                    if (result.text != null && result.text.isNotBlank()) {
                        injectText(result.text)
                    } else {
                        injectText(text, feedback = "Cleanup failed (${result.error ?: "empty reply"}) — raw text used", feedbackDurationMs = 4000)
                    }
                    state = State.IDLE
                    setBusy(false)
                    setAppearance(COLOR_IDLE)
                }
            }
        } else {
            handler.post {
                injectText(text)
                state = State.IDLE
                setBusy(false)
                setAppearance(COLOR_IDLE)
            }
        }
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
