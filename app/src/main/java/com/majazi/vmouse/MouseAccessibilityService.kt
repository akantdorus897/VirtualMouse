package com.majazi.vmouse

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MouseAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: MouseAccessibilityService? = null
            private set

        @Volatile
        var sensitivity: Float = 1.6f
    }

    private lateinit var windowManager: WindowManager
    private var cursorView: View? = null
    private var padView: View? = null
    private lateinit var cursorParams: WindowManager.LayoutParams
    private lateinit var padParams: WindowManager.LayoutParams

    private var cursorX = 0f
    private var cursorY = 0f
    private var screenWidth = 0
    private var screenHeight = 0
    private var touchSlop = 0f

    private val handler = Handler(Looper.getMainLooper())

    // Vaziat-e touchpad
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var moved = false
    private var longPressPosted = false
    private val longPressRunnable = Runnable {
        if (!dragMode) performRightClick()
    }

    // Vaziat-e drag
    private var dragMode = false
    private var dragPath: Path? = null
    private var dragButton: Button? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        touchSlop = dp(12f, metrics)
        cursorX = screenWidth / 2f
        cursorY = screenHeight / 3f
        setupCursor(metrics)
        setupTouchpad(metrics)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        removeOverlayViews()
        super.onDestroy()
    }

    // ---------------- Cursor ----------------

    private fun setupCursor(metrics: DisplayMetrics) {
        val size = dp(34f, metrics).toInt()
        cursorView = CursorView(this)
        cursorParams = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        cursorParams.gravity = Gravity.TOP or Gravity.START
        windowManager.addView(cursorView, cursorParams)
        updateCursorPosition()
    }

    private fun moveCursor(dx: Float, dy: Float) {
        cursorX = min(max(cursorX + dx, 0f), (screenWidth - 1).toFloat())
        cursorY = min(max(cursorY + dy, 0f), (screenHeight - 1).toFloat())
        updateCursorPosition()
    }

    // ---------------- Touchpad ----------------

    private fun setupTouchpad(metrics: DisplayMetrics) {
        padView = buildPadView(metrics)
        padParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            dp(160f, metrics).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        padParams.gravity = Gravity.BOTTOM or Gravity.START
        windowManager.addView(padView, padParams)
        padView?.setOnTouchListener { _, event -> handlePadTouch(event) }
    }

    private fun buildPadView(metrics: DisplayMetrics): View {
        val pad = LinearLayout(this)
        pad.orientation = LinearLayout.VERTICAL
        pad.setBackgroundColor(0x33000000)
        pad.setPadding(
            dp(12f, metrics).toInt(),
            dp(8f, metrics).toInt(),
            dp(12f, metrics).toInt(),
            dp(8f, metrics).toInt()
        )

        val hint = TextView(this)
        hint.text = "Touchpad — انگشت را حرکت دهید • Tap = کلیک • نگه‌داشتن = کلیک راست"
        hint.setTextColor(Color.WHITE)
        hint.textSize = 13f
        pad.addView(hint)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL

        val dragBtn = Button(this)
        dragBtn.text = "درگ ✋"
        dragBtn.layoutParams = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        ).apply {
            marginEnd = dp(8f, metrics).toInt()
        }
        dragBtn.setOnClickListener {
            if (dragMode) endDrag() else startDrag()
        }
        dragButton = dragBtn
        row.addView(dragBtn)

        val closeBtn = Button(this)
        closeBtn.text = "بستن ✕"
        closeBtn.layoutParams = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        )
        closeBtn.setOnClickListener { disableSelf() }
        row.addView(closeBtn)

        pad.addView(row)
        return pad
    }

    private fun updateCursorPosition() {
        val view = cursorView ?: return
        cursorParams.x = cursorX.toInt()
        cursorParams.y = cursorY.toInt()
        try {
            windowManager.updateViewLayout(view, cursorParams)
        } catch (_: Exception) {
        }
    }

    private fun handlePadTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                moved = false
                longPressPosted = true
                handler.postDelayed(longPressRunnable, 550L)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                if (abs(dx) > 1f || abs(dy) > 1f) {
                    if (!moved && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        moved = true
                        if (longPressPosted) {
                            handler.removeCallbacks(longPressRunnable)
                            longPressPosted = false
                        }
                    }
                    if (moved) {
                        moveCursor(dx * sensitivity, dy * sensitivity)
                        if (dragMode) dragPath?.lineTo(cursorX, cursorY)
                    }
                }
                lastTouchX = event.x
                lastTouchY = event.y
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (longPressPosted) {
                    handler.removeCallbacks(longPressRunnable)
                    longPressPosted = false
                    if (event.actionMasked == MotionEvent.ACTION_UP && !moved) {
                        performLeftClick()
                    }
                }
                return true
            }
        }
        return false
    }

    // ---------------- Drag ----------------

    private fun startDrag() {
        dragMode = true
        dragPath = Path().apply { moveTo(cursorX, cursorY) }
        dragButton?.text = "رها کردن ⬆"
    }

    private fun endDrag() {
        dragMode = false
        dragButton?.text = "درگ ✋"
        val path = dragPath
        dragPath = null
        if (path == null) return
        path.lineTo(cursorX, cursorY)
        val length = PathMeasure(path, false).length
        if (length < dp(8f, resources.displayMetrics)) {
            // Hich harkati anjam nashod: long-press (click-e rast)
            performRightClick()
            return
        }
        val duration = max(250L, (length / 1.5f).toLong())
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                .build(),
            null,
            null
        )
    }

    // ---------------- Gestures ----------------

    private fun strokeAt(x: Float, y: Float, durationMs: Long): GestureDescription {
        val path = Path()
        path.moveTo(x, y)
        path.lineTo(x + 0.5f, y + 0.5f)
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
    }

    private fun performLeftClick() {
        dispatchGesture(strokeAt(cursorX, cursorY, 60L), null, null)
    }

    private fun performRightClick() {
        dispatchGesture(strokeAt(cursorX, cursorY, 600L), null, null)
    }

    // ---------------- Helpers ----------------

    private fun removeOverlayViews() {
        try {
            cursorView?.let { windowManager.removeView(it) }
        } catch (_: Exception) {
        }
        try {
            padView?.let { windowManager.removeView(it) }
        } catch (_: Exception) {
        }
        cursorView = null
        padView = null
    }

    private fun dp(value: Float, metrics: DisplayMetrics): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, metrics)
}

/** Neshan-e cursor (shakl-e tir-e mouse) */
class CursorView(context: Context) : View(context) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    override fun onDraw(canvas: Canvas) {
        val s = width / 34f
        val path = Path()
        path.moveTo(4 * s, 2 * s)
        path.lineTo(4 * s, 30 * s)
        path.lineTo(11 * s, 23 * s)
        path.lineTo(15 * s, 31 * s)
        path.lineTo(19 * s, 29 * s)
        path.lineTo(15 * s, 21 * s)
        path.lineTo(24 * s, 21 * s)
        path.close()
        canvas.drawPath(path, fill)
        canvas.drawPath(path, outline)
    }
}
