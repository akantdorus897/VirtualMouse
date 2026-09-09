package com.majazi.vmouse

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MouseAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_TOGGLE = "com.majazi.vmouse.ACTION_TOGGLE"
        private const val CHANNEL_ID = "vmouse_control"
        private const val NOTIFY_ID = 1

        @Volatile
        var instance: MouseAccessibilityService? = null
            private set
    }

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences
    private var cursorView: CursorView? = null
    private var padView: View? = null
    private var menuView: View? = null
    private var menuParams: WindowManager.LayoutParams? = null
    private lateinit var cursorParams: WindowManager.LayoutParams
    private lateinit var padParams: WindowManager.LayoutParams

    private var cursorX = 0f
    private var cursorY = 0f
    private var screenWidth = 0
    private var screenHeight = 0
    private var touchSlop = 0f
    private var mouseVisible = true

    private val handler = Handler(Looper.getMainLooper())

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var moved = false
    private var totalMove = 0f
    private var longPressPosted = false
    private val longPressRunnable = Runnable {
        if (!dragMode) showMenu()
    }

    private var dragMode = false
    private var dragPath: Path? = null
    private var dragButton: Button? = null

    private val toggleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_TOGGLE) setMouseVisible(!mouseVisible)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("vmouse", Context.MODE_PRIVATE)
        loadConfig()
        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        touchSlop = dp(12f)
        cursorX = screenWidth / 2f
        cursorY = screenHeight / 3f
        setupCursor()
        setupTouchpad()
        registerToggleReceiver()
        showNotification()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(toggleReceiver)
        } catch (_: Exception) {
        }
        removeOverlayViews()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFY_ID)
        super.onDestroy()
    }

    private fun loadConfig() {
        MouseConfig.sensitivityLevel = prefs.getInt("sensitivity", 8)
        MouseConfig.cursorSizeDp = prefs.getInt("size", 34)
        MouseConfig.shape = prefs.getInt("shape", CursorView.SHAPE_ARROW)
        MouseConfig.color = prefs.getInt("color", Color.WHITE)
    }

    // ---------------- Cursor ----------------

    private fun setupCursor() {
        val size = dp(MouseConfig.cursorSizeDp.toFloat()).toInt()
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

    fun applySettings() {
        val view = cursorView ?: return
        view.shape = MouseConfig.shape
        view.color = MouseConfig.color
        val size = dp(MouseConfig.cursorSizeDp.toFloat()).toInt()
        cursorParams.width = size
        cursorParams.height = size
        try {
            windowManager.updateViewLayout(view, cursorParams)
        } catch (_: Exception) {
        }
    }

    private fun moveCursor(dx: Float, dy: Float) {
        cursorX = min(max(cursorX + dx, 0f), (screenWidth - 1).toFloat())
        cursorY = min(max(cursorY + dy, 0f), (screenHeight - 1).toFloat())
        updateCursorPosition()
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

    // ---------------- On/Off (Notification) ----------------

    fun setMouseVisible(visible: Boolean) {
        if (visible == mouseVisible) return
        mouseVisible = visible
        handler.post {
            if (visible) {
                try {
                    if (cursorView?.isAttachedToWindow == false) windowManager.addView(cursorView, cursorParams)
                } catch (_: Exception) {
                }
                try {
                    if (padView?.isAttachedToWindow == false) windowManager.addView(padView, padParams)
                } catch (_: Exception) {
                }
            } else {
                hideMenu()
                try {
                    cursorView?.let { windowManager.removeView(it) }
                } catch (_: Exception) {
                }
                try {
                    padView?.let { windowManager.removeView(it) }
                } catch (_: Exception) {
                }
            }
            showNotification()
        }
    }

    private fun registerToggleReceiver() {
        val filter = IntentFilter(ACTION_TOGGLE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(toggleReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(toggleReceiver, filter)
        }
    }

    // ---------------- Touchpad ----------------

    private fun setupTouchpad() {
        padView = buildPadView()
        padParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            dp(160f).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        padParams.gravity = Gravity.BOTTOM or Gravity.START
        windowManager.addView(padView, padParams)
        padView?.setOnTouchListener { _, event -> handlePadTouch(event) }
    }

    private fun buildPadView(): View {
        val pad = LinearLayout(this)
        pad.orientation = LinearLayout.VERTICAL
        pad.setBackgroundColor(0x33000000)
        pad.setPadding(dp(12f).toInt(), dp(8f).toInt(), dp(12f).toInt(), dp(8f).toInt())

        val hint = TextView(this)
        hint.text = "Touchpad — حرکت = جابه‌جایی موس • Tap = کلیک • لمس طولانی = منو"
        hint.setTextColor(Color.WHITE)
        hint.textSize = 13f
        pad.addView(hint)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL

        val dragBtn = Button(this)
        dragBtn.text = "درگ ✋"
        dragBtn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(8f).toInt()
        }
        dragBtn.setOnClickListener {
            if (dragMode) endDrag() else startDrag()
        }
        dragButton = dragBtn
        row.addView(dragBtn)

        val closeBtn = Button(this)
        closeBtn.text = "بستن ✕"
        closeBtn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        closeBtn.setOnClickListener { disableSelf() }
        row.addView(closeBtn)

        pad.addView(row)
        return pad
    }

    private fun handlePadTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                moved = false
                totalMove = 0f
                longPressPosted = true
                handler.postDelayed(longPressRunnable, 550L)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                totalMove += abs(dx) + abs(dy)
                if (longPressPosted && totalMove > touchSlop) {
                    handler.removeCallbacks(longPressRunnable)
                    longPressPosted = false
                    moved = true
                }
                if (abs(dx) > 0.5f || abs(dy) > 0.5f) {
                    moveCursor(dx * MouseConfig.multiplier, dy * MouseConfig.multiplier)
                    if (dragMode) dragPath?.lineTo(cursorX, cursorY)
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

    // ---------------- Menu (Lamse-ye toolani) ----------------

    fun showMenu() {
        if (menuView != null) return
        val scrim = FrameLayout(this)
        scrim.setBackgroundColor(0x55000000)
        scrim.setOnClickListener { hideMenu() }

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setBackgroundColor(0xF2111111.toInt())
        box.setPadding(dp(10f).toInt(), dp(10f).toInt(), dp(10f).toInt(), dp(10f).toInt())
        val boxParams = FrameLayout.LayoutParams(dp(230f).toInt(), FrameLayout.LayoutParams.WRAP_CONTENT)
        boxParams.gravity = Gravity.CENTER
        scrim.addView(box, boxParams)

        fun addItem(text: String, keepOpen: Boolean = false, action: () -> Unit) {
            val b = Button(this)
            b.text = text
            b.textSize = 14f
            b.setOnClickListener {
                if (keepOpen) {
                    action()
                } else {
                    hideMenu()
                    handler.postDelayed({ action() }, 130L)
                }
            }
            box.addView(b)
        }

        addItem("کلیک چپ") { performLeftClick() }
        addItem("کلیک راست") { performRightClick() }
        addItem("دابل کلیک") { performDoubleClick() }
        addItem("اسکرول بالا ⬆") { scroll(true) }
        addItem("اسکرول پایین ⬇") { scroll(false) }
        addItem("کپی 📋", keepOpen = true) { textAction(AccessibilityNodeInfo.ACTION_COPY) }
        addItem("پیست 📥", keepOpen = true) { textAction(AccessibilityNodeInfo.ACTION_PASTE) }
        addItem("انتخاب همه", keepOpen = true) { selectAll() }
        addItem("بستن منو ✕") { }

        menuView = scrim
        menuParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(scrim, menuParams)
    }

    fun hideMenu() {
        val m = menuView ?: return
        menuView = null
        try {
            windowManager.removeView(m)
        } catch (_: Exception) {
        }
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
        if (length < dp(8f)) {
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

    private fun performDoubleClick() {
        val path1 = Path()
        path1.moveTo(cursorX, cursorY)
        path1.lineTo(cursorX + 0.5f, cursorY + 0.5f)
        val path2 = Path()
        path2.moveTo(cursorX, cursorY)
        path2.lineTo(cursorX + 0.5f, cursorY + 0.5f)
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path1, 0, 50))
                .addStroke(GestureDescription.StrokeDescription(path2, 130, 50))
                .build(),
            null,
            null
        )
    }

    private fun scroll(up: Boolean) {
        val dist = 360f
        val endY = if (up) min((screenHeight - 1).toFloat(), cursorY + dist) else max(1f, cursorY - dist)
        val path = Path()
        path.moveTo(cursorX, cursorY)
        path.lineTo(cursorX, endY)
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 250L))
                .build(),
            null,
            null
        )
    }

    // ---------------- Copy / Paste / Select All ----------------

    private fun textAction(action: Int): Boolean {
        val root = rootInActiveWindow ?: return false
        var node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        if (node == null) node = findEditable(root)
        return node?.performAction(action) ?: false
    }

    private fun selectAll(): Boolean {
        val root = rootInActiveWindow ?: return false
        var node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        if (node == null) node = findEditable(root)
        node ?: return false
        val args = android.os.Bundle()
        args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
        args.putInt(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
            (node.text?.length ?: 10000).coerceAtLeast(1)
        )
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditable(child)
            if (found != null) return found
        }
        return null
    }

    // ---------------- Notification ----------------

    private fun showNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "کنترل موس مجازی", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val togglePi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_TOGGLE).setPackage(packageName), flags)
        val openPi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val builder: Notification.Builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder.setSmallIcon(android.R.drawable.ic_menu_myplaces)
            .setContentTitle(if (mouseVisible) "موس مجازی: روشن ✅" else "موس مجازی: خاموش ⛔")
            .setContentText("با دکمه زیر موس را روشن یا خاموش کنید")
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(0, if (mouseVisible) "خاموش کردن موس" else "روشن کردن موس", togglePi)
        nm.notify(NOTIFY_ID, builder.build())
    }

    // ---------------- Helpers ----------------

    private fun removeOverlayViews() {
        hideMenu()
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

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
}
