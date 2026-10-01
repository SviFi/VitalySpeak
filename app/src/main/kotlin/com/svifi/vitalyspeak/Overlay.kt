package com.svifi.vitalyspeak

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Everything VitalySpeak draws over other apps:
 *  - the dot (mic / stop + timer, animated shape while recording, spinner while working)
 *  - a feedback bubble and a live-transcript bubble next to the dot
 *  - the recording controls (Command, Cancel, Screen off)
 *  - the screen-off ("pocket") cover
 */
class Overlay(private val ctx: Context, private val callbacks: Callbacks) {

    interface Callbacks {
        fun onDotTap()
        fun onDotLongPress()
        fun onCommandToggle()
        fun onCancel()
        fun elapsedLabel(): String
        fun isIdle(): Boolean
    }

    enum class Mode { IDLE, RECORDING, WORKING }

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val density = ctx.resources.displayMetrics.density
    private fun px(dp: Float) = (dp * density).toInt()
    private fun prefs() = Prefs.get(ctx)

    // dot window
    private var dotRoot: FrameLayout? = null
    private var dotLp: WindowManager.LayoutParams? = null
    private var icon: ImageView? = null
    private var spinner: ProgressBar? = null
    private var timer: TextView? = null
    private var shape: WaveRingView? = null
    private var dotPx = 0
    private var iconPad = 0
    private var mode = Mode.IDLE
    private var shown = true

    // bubbles + controls + pocket
    private var feedback: TextView? = null
    private var feedbackLp: WindowManager.LayoutParams? = null
    private var preview: TextView? = null
    private var previewLp: WindowManager.LayoutParams? = null
    private var controls: LinearLayout? = null
    private var controlsLp: WindowManager.LayoutParams? = null
    private var commandButton: TextView? = null
    private var pocket: FrameLayout? = null
    private var pocketReceiver: BroadcastReceiver? = null

    private val hideFeedback = Runnable { feedback?.visibility = View.GONE }
    private val tick = object : Runnable {
        override fun run() {
            val t = timer ?: return
            if (mode != Mode.RECORDING) { t.visibility = View.GONE; return }
            t.text = callbacks.elapsedLabel()
            t.visibility = View.VISIBLE
            main.postDelayed(this, 1000)
        }
    }

    // ---------- look ----------

    private fun sizePct() = prefs().getInt(Appearance.KEY_DOT_SIZE, Appearance.DEF_DOT_SIZE).coerceIn(60, 160)
    private fun ring() = Appearance.ring(prefs())

    private fun round(fill: Int, stroke: Int? = null) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        stroke?.let { setStroke(px(3f), it) }
    }

    private fun bubble(fill: Int, stroke: Int? = null) = GradientDrawable().apply {
        cornerRadius = px(16f).toFloat()
        setColor(fill)
        stroke?.let { setStroke(px(1.5f), it) }
    }

    private fun screen(): Rect = wm.currentWindowMetrics.bounds

    /** Idle: window ≈ pinch zone (bigger than the dot when small). Busy: room for the shape. */
    private fun windowSize(m: Mode): Int = if (m == Mode.IDLE)
        (dotPx * (1.45f - 0.30f * (sizePct() - 60) / 100f)).toInt() else dotPx * 2

    private fun overlayParams(w: Int, h: Int, touchable: Boolean) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        // Whole-screen coordinates (no shifting by status bar, cutout or screen edge), so every
        // overlay window and getLocationOnScreen() share one coordinate system.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    // ---------- build / tear down ----------

    /** Creates the dot (centred on [center] if given) and the bubbles. */
    fun build(center: Pair<Int, Int>? = null) {
        val scale = sizePct() / 100f
        dotPx = px(84f * scale)
        iconPad = px(22f * scale)

        val shapeView = WaveRingView(ctx, dotPx / 2f, prefs().getInt(Appearance.KEY_REACTION, Appearance.DEF_REACTION) / 100f).apply {
            visibility = View.INVISIBLE
        }
        shape = shapeView
        applyLook()

        val spin = ProgressBar(ctx).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(0xFFE8EAED.toInt())
            visibility = View.GONE
        }
        val ic = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_mic)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(iconPad, iconPad, iconPad, iconPad)
            background = round(COLOR_IDLE, ring())
            contentDescription = ctx.getString(R.string.dot_description)
        }
        val tm = TextView(ctx).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_PX, dotPx * 0.15f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            fontFeatureSettings = "tnum"
            translationY = dotPx * 0.27f
            visibility = View.GONE
        }
        val root = FrameLayout(ctx).apply {
            val match = FrameLayout.LayoutParams.MATCH_PARENT
            val wrap = FrameLayout.LayoutParams.WRAP_CONTENT
            addView(shapeView, FrameLayout.LayoutParams(match, match, Gravity.CENTER))
            addView(spin, FrameLayout.LayoutParams((dotPx * 1.19f).toInt(), (dotPx * 1.19f).toInt(), Gravity.CENTER))
            addView(ic, FrameLayout.LayoutParams(dotPx, dotPx, Gravity.CENTER))
            addView(tm, FrameLayout.LayoutParams(wrap, wrap, Gravity.CENTER))
        }
        val size = windowSize(mode)
        val sc = screen()
        val lp = overlayParams(size, size, true).apply {
            val cx = center?.first ?: (sc.width() - px(8f) - size / 2)
            val cy = center?.second ?: (sc.height() / 2)
            x = (cx - size / 2).coerceIn(0, maxOf(0, sc.width() - size))
            y = (cy - size / 2).coerceIn(0, maxOf(0, sc.height() - size))
        }
        root.setOnTouchListener(DotTouch(root, lp))
        dotRoot = root; dotLp = lp; icon = ic; spinner = spin; timer = tm
        if (shown) attach(root, lp)

        feedback = TextView(ctx).apply {
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(px(12f), px(8f), px(12f), px(8f))
            background = bubble(COLOR_BUBBLE)
            maxWidth = (sc.width() * 0.7f).toInt()
            visibility = View.GONE
        }
        feedbackLp = overlayParams(WRAP, WRAP, false)
        preview = TextView(ctx).apply {
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.START
            setPadding(px(14f), px(10f), px(14f), px(10f))
            background = bubble(COLOR_BUBBLE, ring())
            maxWidth = (sc.width() * 0.7f).toInt()
            visibility = View.GONE
        }
        previewLp = overlayParams(WRAP, WRAP, false)
        placeNextToDot(feedbackLp!!); placeNextToDot(previewLp!!)
        attach(feedback!!, feedbackLp!!)
        attach(preview!!, previewLp!!)
        setMode(mode)
    }

    fun destroy() {
        main.removeCallbacksAndMessages(null)
        exitPocket()
        listOf(dotRoot, feedback, preview, controls).forEach { v -> v?.let { detach(it) } }
        dotRoot = null; feedback = null; preview = null; controls = null; commandButton = null
        icon = null; spinner = null; timer = null; shape = null
    }

    /** Rebuild at the current size/colours, keeping the dot where it is. */
    fun rebuild() {
        val lp = dotLp
        val center = lp?.let { it.x + it.width / 2 to it.y + it.height / 2 }
        destroy()
        build(center)
    }

    fun applyLook() {
        val p = prefs()
        shape?.apply {
            reaction = p.getInt(Appearance.KEY_REACTION, Appearance.DEF_REACTION).coerceIn(0, 100) / 100f
            speed = p.getInt(Appearance.KEY_WAVE_SPEED, Appearance.DEF_WAVE_SPEED).coerceIn(100, 500) / 100f
            waveMult = p.getInt(Appearance.KEY_WAVE_COUNT, Appearance.DEF_WAVE_COUNT).coerceIn(100, 300) / 100f
            setColors(Appearance.front(p), Appearance.middle(p), Appearance.back(p))
        }
    }

    private fun attach(v: View, lp: WindowManager.LayoutParams) {
        try { if (!v.isAttachedToWindow) wm.addView(v, lp) } catch (_: Exception) {}
    }
    private fun detach(v: View) {
        try { if (v.isAttachedToWindow) wm.removeViewImmediate(v) } catch (_: Exception) {}
    }
    private fun relayout(v: View?, lp: WindowManager.LayoutParams?) {
        if (v == null || lp == null) return
        try { if (v.isAttachedToWindow) wm.updateViewLayout(v, lp) } catch (_: Exception) {}
    }

    // ---------- visibility, mode, position ----------

    /**
     * Shown/hidden by attaching/detaching the window (a fade on a re-shown overlay window can
     * stay stuck at 0 on some phones; a detached window is guaranteed invisible and untouchable).
     */
    fun setShown(show: Boolean) {
        shown = show
        val root = dotRoot ?: return
        val lp = dotLp ?: return
        if (show) attach(root, lp) else detach(root)
    }

    fun setMode(m: Mode) {
        mode = m
        val ic = icon ?: return
        resizeTo(windowSize(m))
        ic.setImageResource(if (m == Mode.RECORDING) R.drawable.ic_stop else R.drawable.ic_mic)
        // Recording: the animated shape is the dot; stop icon sits higher, timer below it.
        ic.background = when (m) {
            Mode.RECORDING -> null
            Mode.WORKING -> round(COLOR_WORKING, ring())
            Mode.IDLE -> round(COLOR_IDLE, ring())
        }
        if (m == Mode.RECORDING) ic.setPadding(iconPad, (iconPad * 0.62f).toInt(), iconPad, (iconPad * 1.38f).toInt())
        else ic.setPadding(iconPad, iconPad, iconPad, iconPad)
        spinner?.visibility = if (m == Mode.WORKING) View.VISIBLE else View.GONE
        main.removeCallbacks(tick)
        if (m == Mode.RECORDING) { shape?.start(); main.post(tick) } else { shape?.stop(); timer?.visibility = View.GONE }
        if (m != Mode.RECORDING) { hideControls(); exitPocket() }
    }

    private fun resizeTo(size: Int) {
        val lp = dotLp ?: return
        if (size <= 0 || lp.width == size) return
        val cx = lp.x + lp.width / 2
        val cy = lp.y + lp.height / 2
        lp.width = size; lp.height = size
        lp.x = cx - size / 2; lp.y = cy - size / 2
        relayout(dotRoot, lp)
    }

    /** Lift the dot above the keyboard if the keyboard would cover it. */
    fun keepAbove(keyboard: Rect) {
        val lp = dotLp ?: return
        val margin = px(8f)
        if (lp.y + lp.height > keyboard.top - margin) {
            lp.y = maxOf(margin, keyboard.top - lp.height - margin)
            relayout(dotRoot, lp)
            moveSatellites()
        }
    }

    /** Bubble windows sit just above the dot, aligned to its outer side (below it near the top). */
    private fun placeNextToDot(b: WindowManager.LayoutParams) {
        val dot = dotLp ?: return
        val sc = screen()
        val margin = px(8f)
        val onRight = dot.x + dot.width / 2 > sc.width() / 2
        val side = if (onRight) Gravity.END else Gravity.START
        b.x = if (onRight) maxOf(margin, sc.width() - (dot.x + dot.width)) else maxOf(margin, dot.x)
        if (dot.y > px(140f)) {
            b.gravity = Gravity.BOTTOM or side
            b.y = maxOf(margin, sc.height() - dot.y + margin / 2)
        } else {
            b.gravity = Gravity.TOP or side
            b.y = dot.y + dot.height + margin / 2
        }
    }

    /**
     * Puts the Command / Cancel / Screen-off column next to the dot without ever covering it:
     * measured from where the dot really is on screen, on the side with more room (the left when
     * the dot is on the right half), clear of the dot's enlarged tap zone. If neither side has
     * room for the column (narrow screens, long translations), it goes above or below the dot.
     */
    private fun placeControls(c: WindowManager.LayoutParams) {
        val box = controls ?: return
        val sc = screen()
        val size = icon?.width?.takeIf { it > 0 } ?: dotPx
        val at = IntArray(2)
        val ic = icon
        if (ic != null && ic.isAttachedToWindow && ic.width > 0) ic.getLocationOnScreen(at)
        else dotLp?.let { at[0] = it.x + (it.width - size) / 2; at[1] = it.y + (it.height - size) / 2 } ?: return
        box.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val w = box.measuredWidth
        val h = box.measuredHeight
        val margin = px(8f)
        val gap = (size * 0.2f).toInt() + px(6f)       // outside the 1.35× tap radius while busy
        val left = at[0]; val top = at[1]; val right = left + size; val bottom = top + size
        val roomLeft = left - gap - margin
        val roomRight = sc.width() - right - gap - margin
        val leftFirst = left + size / 2 > sc.width() / 2
        c.gravity = Gravity.TOP or Gravity.START
        val x = when {
            leftFirst && w <= roomLeft -> left - gap - w
            !leftFirst && w <= roomRight -> right + gap
            w <= roomLeft -> left - gap - w
            w <= roomRight -> right + gap
            else -> null
        }
        (box as? LinearLayout)?.gravity = when {
            x == null -> Gravity.CENTER_HORIZONTAL
            x < left -> Gravity.END                      // column left of the dot: hug the dot
            else -> Gravity.START
        }
        if (x != null) {
            c.x = x
            c.y = (top + size / 2 - h / 2).coerceIn(margin, maxOf(margin, sc.height() - h - margin))
        } else {
            c.x = (left + size / 2 - w / 2).coerceIn(margin, maxOf(margin, sc.width() - w - margin))
            c.y = if (top - gap - h >= margin) top - gap - h else minOf(bottom + gap, sc.height() - h - margin)
        }
    }

    private fun moveSatellites() {
        feedbackLp?.let { placeNextToDot(it); relayout(feedback, it) }
        previewLp?.let { placeNextToDot(it); relayout(preview, it) }
        controlsLp?.let { placeControls(it); relayout(controls, it) }
    }

    // ---------- bubbles ----------

    fun feedback(text: String, ms: Long = 2000) = main.post {
        val v = feedback ?: return@post
        feedbackLp?.let { placeNextToDot(it); relayout(v, it) }
        v.text = text
        v.visibility = View.VISIBLE
        main.removeCallbacks(hideFeedback)
        main.postDelayed(hideFeedback, ms)
    }

    fun preview(text: String) = main.post {
        val v = preview ?: return@post
        previewLp?.let { placeNextToDot(it); relayout(v, it) }
        v.text = text
        v.visibility = View.VISIBLE
    }

    fun hidePreview() = main.post { preview?.visibility = View.GONE }

    fun level(loudness: Float, pitch: Float) = shape?.update(loudness, pitch)

    // ---------- recording controls ----------

    fun showControls() = main.post {
        if (controls == null) {
            fun button(label: String, onClick: () -> Unit) = TextView(ctx).apply {
                text = label
                textSize = 13f
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(px(14f), px(8f), px(14f), px(8f))
                background = bubble(COLOR_CONTROL)
                setOnClickListener { onClick() }
            }
            val gap = LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = px(8f) }
            controls = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(button(ctx.getString(R.string.cmd_start)) { callbacks.onCommandToggle() }.also { commandButton = it })
                addView(button(ctx.getString(R.string.btn_cancel)) { callbacks.onCancel() }, gap)
                addView(button(ctx.getString(R.string.btn_screen_off)) { enterPocket() },
                    LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = px(8f) })
            }
            controlsLp = overlayParams(WRAP, WRAP, true)
        }
        showCommand(false)
        controlsLp?.let { placeControls(it) }
        attach(controls!!, controlsLp!!)
        relayout(controls, controlsLp)
        // Again once the dot's window has its recording size and the column its final text.
        main.postDelayed({ controlsLp?.let { placeControls(it); relayout(controls, it) } }, 120)
    }

    fun hideControls() = main.post { controls?.let { detach(it) } }

    /** Command mode: amber button and shape while the user speaks an instruction. */
    fun showCommand(on: Boolean) = main.post {
        commandButton?.apply {
            text = ctx.getString(if (on) R.string.cmd_end else R.string.cmd_start)
            background = bubble(if (on) COLOR_COMMAND else COLOR_CONTROL)
            setTextColor(if (on) 0xFF1A1A1A.toInt() else 0xFFFFFFFF.toInt())
        }
        val p = prefs()
        shape?.setColors(if (on) COLOR_COMMAND else Appearance.front(p), Appearance.middle(p), Appearance.back(p))
        if (on) preview(callbacks.elapsedLabel() + " · " + ctx.getString(R.string.cmd_hint))
        controlsLp?.let { placeControls(it); relayout(controls, it) }    // the label's width changed
    }

    // ---------- screen-off ("pocket") mode ----------

    /**
     * A black cover at minimum brightness while recording continues. The display stays
     * technically on (Android may mute the microphone of background apps once the screen is
     * off), touches are swallowed, and a double-tap or the power button brings everything back.
     */
    fun enterPocket() = main.post {
        if (mode != Mode.RECORDING || pocket != null) return@post
        val sc = screen()
        val title = TextView(ctx).apply {
            text = ctx.getString(R.string.pocket_title)
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 0.9f)
            setAutoSizeTextTypeUniformWithConfiguration(24, 400, 2, TypedValue.COMPLEX_UNIT_SP)
            layoutParams = LinearLayout.LayoutParams((sc.width() * 0.88f).toInt(), (sc.height() * 0.42f).toInt())
        }
        val sub = TextView(ctx).apply {
            textSize = 20f
            setTextColor(0xCCFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, px(16f), 0, 0)
        }
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(title); addView(sub)
        }
        val cover = FrameLayout(ctx).apply {
            setBackgroundColor(0xFF000000.toInt())
            addView(column, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        }
        val taps = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onDoubleTap(e: MotionEvent): Boolean { exitPocket(); return true }
        })
        cover.setOnTouchListener { _, ev -> taps.onTouchEvent(ev); true }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        ).apply { gravity = Gravity.TOP or Gravity.START; screenBrightness = -1f }
        try { wm.addView(cover, lp) } catch (_: Exception) { return@post }
        pocket = cover
        // Power button = screen off: leave pocket mode so the screen looks normal when it wakes.
        pocketReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = exitPocket()
        }.also { try { ctx.registerReceiver(it, IntentFilter(Intent.ACTION_SCREEN_OFF)) } catch (_: Exception) {} }
        // 5-second countdown with full brightness, then dim to readable grey.
        var left = 5
        main.post(object : Runnable {
            override fun run() {
                if (pocket !== cover) return
                if (left > 0) {
                    sub.text = ctx.getString(R.string.pocket_countdown, left); left--
                    main.postDelayed(this, 1000)
                } else {
                    sub.text = ctx.getString(R.string.pocket_continues)
                    title.setTextColor(0xFF5C5C5C.toInt())
                    sub.setTextColor(0xFF444444.toInt())
                    lp.screenBrightness = 0f
                    relayout(cover, lp)
                }
            }
        })
        // Every 20 s: small drift (no burn-in) and the elapsed time.
        main.postDelayed(object : Runnable {
            override fun run() {
                if (pocket !== cover) return
                sub.text = ctx.getString(R.string.pocket_recording, callbacks.elapsedLabel())
                column.translationX = ((Math.random() - 0.5) * cover.width * 0.06).toFloat()
                column.translationY = ((Math.random() - 0.5) * cover.height * 0.08).toFloat()
                main.postDelayed(this, 20_000)
            }
        }, 20_000)
    }

    fun exitPocket() {
        val run = Runnable {
            pocketReceiver?.let { try { ctx.unregisterReceiver(it) } catch (_: Exception) {} }
            pocketReceiver = null
            pocket?.let { detach(it) }
            pocket = null
        }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else main.post(run)
    }

    // ---------- touch: tap, drag, long-press, pinch ----------

    private inner class DotTouch(private val root: FrameLayout, private val lp: WindowManager.LayoutParams) : View.OnTouchListener {
        private var downX = 0f; private var downY = 0f
        private var startX = 0; private var startY = 0
        private var onDot = false
        private var tracking = false
        private var longPressed = false
        private var pinching = false
        private var pinchScale = 1f
        private val slop = px(10f)

        private val longPress = Runnable {
            if (!callbacks.isIdle()) return@Runnable
            longPressed = true
            root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            callbacks.onDotLongPress()
        }

        private val pinch = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                if (!callbacks.isIdle()) return false
                pinching = true; pinchScale = 1f
                main.removeCallbacks(longPress)
                resizeTo((dotPx * 160f / sizePct() * 1.2f).toInt())   // room to grow, no clipping
                return true
            }
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val pct = sizePct()
                pinchScale = (pinchScale * d.scaleFactor).coerceIn(60f / pct, 160f / pct)
                icon?.scaleX = pinchScale; icon?.scaleY = pinchScale
                return true
            }
            override fun onScaleEnd(d: ScaleGestureDetector) {
                val newPct = (sizePct() * pinchScale).toInt().coerceIn(60, 160)
                prefs().edit().putInt(Appearance.KEY_DOT_SIZE, newPct).apply()
                feedback(ctx.getString(R.string.dot_size_toast, newPct), 1200)
                main.post { rebuild() }
            }
        })

        override fun onTouch(v: View, ev: MotionEvent): Boolean {
            if (callbacks.isIdle()) pinch.onTouchEvent(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val dist = hypot(ev.x - v.width / 2f, ev.y - v.height / 2f)
                    val tapRadius = dotPx / 2f * (if (callbacks.isIdle()) 1.05f else 1.35f)
                    if (dist > maxOf(tapRadius, v.width / 2f)) { tracking = false; return false }
                    onDot = dist <= tapRadius
                    tracking = true; pinching = false; longPressed = false
                    downX = ev.rawX; downY = ev.rawY; startX = lp.x; startY = lp.y
                    if (onDot) main.postDelayed(longPress, 600)
                }
                MotionEvent.ACTION_POINTER_DOWN -> main.removeCallbacks(longPress)
                MotionEvent.ACTION_MOVE -> {
                    if (!tracking) return false
                    if (pinching || ev.pointerCount > 1 || !onDot) return true
                    if (abs(ev.rawX - downX) + abs(ev.rawY - downY) > slop) main.removeCallbacks(longPress)
                    lp.x = startX + (ev.rawX - downX).toInt()
                    lp.y = startY + (ev.rawY - downY).toInt()
                    relayout(root, lp)
                    moveSatellites()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                    if (!tracking) return false
                    tracking = false
                    val moved = abs(ev.rawX - downX) + abs(ev.rawY - downY)
                    when {
                        longPressed || pinching || !onDot || ev.actionMasked == MotionEvent.ACTION_CANCEL -> {}
                        moved < slop -> callbacks.onDotTap()
                        else -> {   // dropped after a drag: snap to the nearest side
                            val sc = screen()
                            lp.x = if (lp.x + lp.width / 2 > sc.width() / 2) sc.width() - lp.width - px(8f) else px(8f)
                            lp.y = lp.y.coerceIn(0, maxOf(0, sc.height() - lp.height))
                            relayout(root, lp)
                            moveSatellites()
                        }
                    }
                    pinching = false
                }
            }
            return true
        }
    }

    companion object {
        private const val WRAP = WindowManager.LayoutParams.WRAP_CONTENT
        private const val COLOR_IDLE = 0xDD1C1C1E.toInt()
        private const val COLOR_WORKING = 0xDD6B6B6B.toInt()
        private const val COLOR_BUBBLE = 0xEE1C1C1E.toInt()
        private const val COLOR_CONTROL = 0xE63A3A3C.toInt()
        private const val COLOR_COMMAND = 0xF2E8A317.toInt()
    }
}
