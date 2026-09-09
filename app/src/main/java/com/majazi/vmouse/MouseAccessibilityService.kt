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
    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var handleView: View? = null
    private var handleParams: WindowManager.LayoutParams? = null
    private var menuView: View? = null
    private var menuParams: WindowManager.LayoutParams? = null
    private lateinit var cursorParams: WindowManager.LayoutParams
    private var panelCollapsed = false
    private var panelOnRight = true
    private var panelY = 0
    private var headerLastY = 0f

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
    private var dragButton: TextView? = null

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
        setupPanel()
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
                if (panelCollapsed) showHandle() else showPanel()
            } else {
                hideMenu()
                try {
                    cursorView?.let { windowManager.removeView(it) }
                } catch (_: Exception) {
                }
                try {
                    panelView?.let { windowManager.removeView(it) }
                } catch (_: Exception) {
                }
                hideHandleView()
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

    // ---------------- Trackpad Panel (kenar-e keyboard) ----------------

    private fun setupPanel() {
        panelCollapsed = prefs.getBoolean("panel_collapsed", false)
        panelOnRight = prefs.getBoolean("panel_on_right", true)
        panelY = prefs.getInt("panel_y", -1)
        if (panelY < 0) panelY = (screenHeight * 0.18f).toInt()
        if (panelCollapsed) showHandle() else showPanel()
    }

    private fun sideGravity(): Int =
        if (panelOnRight) Gravity.TOP or Gravity.END else Gravity.TOP or Gravity.START

    private fun showPanel() {
        if (panelView != null) return
        panelCollapsed = false
        prefs.edit().putBoolean("panel_collapsed", false).apply()
        panelView = buildPanel()
        val lp = WindowManager.LayoutParams(
            dp(96f).toInt(),
            (screenHeight * 0.58f).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = sideGravity()
        lp.y = panelY
        panelParams = lp
        windowManager.addView(panelView, lp)
        hideHandleView()
    }

    private fun collapsePanel() {
        panelCollapsed = true
        prefs.edit().putBoolean("panel_collapsed", true).apply()
        panelView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        panelView = null
        showHandle()
    }

    private fun showHandle() {
        if (handleView != null) return
        val tab = TextView(this)
        tab.text = "▦"
        tab.setTextColor(Color.WHITE)
        tab.textSize = 18f
        tab.gravity = Gravity.CENTER
        tab.setBackgroundColor(0xE6141414.toInt())
        tab.setOnClickListener { showPanel() }
        handleView = tab
        val hp = WindowManager.LayoutParams(
            dp(26f).toInt(),
            dp(74f).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        hp.gravity = sideGravity()
        hp.y = panelY
        handleParams = hp
        windowManager.addView(handleView, hp)
    }

    private fun hideHandleView() {
        handleView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        handleView = null
    }

    private fun flipSide() {
        panelOnRight = !panelOnRight
        prefs.edit().putBoolean("panel_on_right", panelOnRight).apply()
        if (panelCollapsed) {
            hideHandleView()
            showHandle()
        } else {
            panelView?.let {
                panelParams?.gravity = sideGravity()
                try {
                    windowManager.updateViewLayout(it, panelParams)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun handleHeaderDrag(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                headerLastY = event.rawY
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = event.rawY - headerLastY
                headerLastY = event.rawY
                panelY = (panelY + dy).toInt().coerceIn(0, (screenHeight - dp(140f)).toInt())
                prefs.edit().putInt("panel_y", panelY).apply()
                val v = panelView
                if (v != null && panelParams != null) {
                    panelParams?.y = panelY
                    try {
                        windowManager.updateViewLayout(v, panelParams)
                    } catch (_: Exception) {
                    }
                } else if (handleView != null && handleParams != null) {
                    handleParams?.y = panelY
                    try {
                        windowManager.updateViewLayout(handleView, handleParams)
                    } catch (_: Exception) {
                    }
                }
                return true
            }
        }
        return false
    }

    private fun panelButton(text: String, onClick: () -> Unit, weight: Float): TextView {
        val b = TextView(this)
        b.text = text
        b.setTextColor(Color.WHITE)
        b.textSize = 15f
        b.gravity = Gravity.CENTER
        b.setBackgroundColor(0x33FFFFFF)
        val lp = LinearLayout.LayoutParams(0, dp(44f).toInt(), weight)
        val m = dp(2f).toInt()
        lp.setMargins(m, m, m, m)
        b.layoutParams = lp
        b.setOnClickListener { onClick() }
        return b
    }

    private fun buildPanel(): View {
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setBackgroundColor(0xE6141414.toInt())
        val p = dp(4f).toInt()
        panel.setPadding(p, p, p, p)

        // Header: drag(≡) + flip(⇄) + collapse(—)
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL

        val grip = TextView(this)
        grip.text = "≡"
        grip.setTextColor(Color.WHITE)
        grip.textSize = 16f
        grip.gravity = Gravity.CENTER
        grip.layoutParams = LinearLayout.LayoutParams(0, dp(30f).toInt(), 1f)
        grip.setOnTouchListener { _, event -> handleHeaderDrag(event) }
        header.addView(grip)

        val flip = TextView(this)
        flip.text = "⇄"
        flip.setTextColor(Color.WHITE)
        flip.textSize = 14f
        flip.gravity = Gravity.CENTER
        flip.layoutParams = LinearLayout.LayoutParams(0, dp(30f).toInt(), 1f)
        flip.setOnClickListener { flipSide() }
        header.addView(flip)

        val minBtn = TextView(this)
        minBtn.text = "—"
        minBtn.setTextColor(Color.WHITE)
        minBtn.textSize = 14f
        minBtn.gravity = Gravity.CENTER
        minBtn.layoutParams = LinearLayout.LayoutParams(0, dp(30f).toInt(), 1f)
        minBtn.setOnClickListener { collapsePanel() }
        header.addView(minBtn)

        panel.addView(header)

        // Trackpad: harekat + tap=click + long-press=menu
        val track = View(this)
        track.setBackgroundColor(0x33FFFFFF)
        track.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        )
        track.setOnTouchListener { _, event -> handlePadTouch(event) }
        panel.addView(track)

        // Scroll
        val scrollRow = LinearLayout(this)
        scrollRow.orientation = LinearLayout.HORIZONTAL
        scrollRow.addView(panelButton("⬆", { scroll(true) }, 0.5f))
        scrollRow.addView(panelButton("⬇", { scroll(false) }, 0.5f))
        panel.addView(scrollRow)

        // Clicks
        val row1 = LinearLayout(this)
        row1.orientation = LinearLayout.HORIZONTAL
        row1.addView(panelButton("L", { performLeftClick() }, 0.5f))
        row1.addView(panelButton("R", { performRightClick() }, 0.5f))
        panel.addView(row1)

        // Double + Drag + Menu
        val row2 = LinearLayout(this)
        row2.orientation = LinearLayout.HORIZONTAL
        row2.addView(panelButton("\u00d72", { performDoubleClick() }, 1f / 3f))
        val dragBtn = panelButton("\u2725", {
            if (dragMode) endDrag() else startDrag()
        }, 1f / 3f)
        dragButton = dragBtn
        row2.addView(dragBtn)
        row2.addView(panelButton("☰", { showMenu() }, 1f / 3f))
        panel.addView(row2)

        return panel
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
            panelView?.let { windowManager.removeView(it) }
        } catch (_: Exception) {
        }
        hideHandleView()
        cursorView = null
        panelView = null
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
}
