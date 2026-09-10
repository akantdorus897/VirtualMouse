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

    // Hover-e chasb: press + edameh ba harekat-e cursor (Chrome :hover fa'al mimuneh)
    private var hoverActive = false
    private var hoverStroke: GestureDescription.StrokeDescription? = null
    private var hoverX = 0f
    private var hoverY = 0f
    private var hoverBtn: TextView? = null
    private var panelCollapsed = false
    private var panelX = 0
    private var panelY = 0
    private var panelW = 0
    private var panelH = 0
    private var panelLocked = false
    private var lockButton: TextView? = null
    private var headerLastX = 0f
    private var headerLastY = 0f
    private var resizeLastX = 0f
    private var resizeLastY = 0f

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
        MouseConfig.offsetX = prefs.getInt("cal_x", 0).toFloat()
        MouseConfig.offsetY = prefs.getInt("cal_y", 0).toFloat()
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
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
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

    // ---- Action-haye omoomi baraye control az app (tool ring-e MainActivity) ----

    fun clickLeft() = performLeftClick()

    fun clickRight() = performRightClick()

    fun clickDouble() = performDoubleClick()

    fun hoverToggle() = performHover()

    fun scrollUp() = scroll(true)

    fun scrollDown() = scroll(false)

    /** Harekat-e daghigh-e cursor bedoone trackpad (px) */
    fun nudge(dx: Float, dy: Float) = moveCursor(dx, dy)

    fun isHoverActive(): Boolean = hoverActive

    private fun moveCursor(dx: Float, dy: Float) {
        cursorX = min(max(cursorX + dx, 0f), (screenWidth - 1).toFloat())
        cursorY = min(max(cursorY + dy, 0f), (screenHeight - 1).toFloat())
        updateCursorPosition()
        // Agar hover-e chasb fa'al ast, stroke ro be ja-ye jadid edameh bedeh
        if (hoverActive) continueHover()
    }

    private fun updateCursorPosition() {
        val view = cursorView ?: return
        cursorParams.x = cursorX.toInt()
        cursorParams.y = cursorY.toInt()
        try {
            windowManager.updateViewLayout(view, cursorParams)
        } catch (_: Exception) {
        }
        // Agar cursor zire panel-e rafteh, cursor ro bala byar (re-attach)
        // ta neshangar hameshe didan beshe
        val pv = panelView
        if (pv != null) {
            val pl = panelParams
            if (pl != null) {
                val px0 = pl.x
                val py0 = pl.y
                val px1 = px0 + pl.width
                val py1 = py0 + pl.height
                val cx = cursorX.toInt()
                val cy = cursorY.toInt()
                if (cx >= px0 && cx <= px1 && cy >= py0 && cy <= py1) {
                    try {
                        windowManager.removeView(view)
                        windowManager.addView(view, cursorParams)
                    } catch (_: Exception) {
                    }
                }
            }
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
                endHover()
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
        panelLocked = prefs.getBoolean("panel_locked", false)
        panelW = prefs.getInt("panel_w", -1).let { if (it < 0) dp(84f).toInt() else it }
        panelH = prefs.getInt("panel_h", -1).let { if (it < 0) dp(200f).toInt() else it }
        panelX = prefs.getInt("panel_x", -1).let { if (it < 0) screenWidth - panelW - dp(6f).toInt() else it }
        panelY = prefs.getInt("panel_y", -1).let { if (it < 0) (screenHeight * 0.15f).toInt() else it }
        if (panelCollapsed) showHandle() else showPanel()
    }

    private fun showPanel() {
        if (panelView != null) return
        panelCollapsed = false
        prefs.edit().putBoolean("panel_collapsed", false).apply()
        panelView = buildPanel()
        val lp = WindowManager.LayoutParams(
            panelW,
            panelH,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = panelX
        lp.y = panelY
        panelParams = lp
        windowManager.addView(panelView, lp)
        hideHandleView()
        // Cursor hamishe rooye panel bashe
        cursorView?.let { cv ->
            try {
                windowManager.removeView(cv)
                windowManager.addView(cv, cursorParams)
            } catch (_: Exception) {
            }
        }
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        hp.gravity = Gravity.TOP or Gravity.START
        hp.x = panelX
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

    private fun handleResizeDrag(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resizeLastX = event.rawX
                resizeLastY = event.rawY
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (panelLocked) return true
                panelTouched()
                val dx = event.rawX - resizeLastX
                val dy = event.rawY - resizeLastY
                resizeLastX = event.rawX
                resizeLastY = event.rawY
                panelW = (panelW + dx).toInt().coerceIn(dp(70f).toInt(), dp(260f).toInt())
                panelH = (panelH + dy).toInt().coerceIn(dp(140f).toInt(), (screenHeight * 0.75f).toInt())
                prefs.edit().putInt("panel_w", panelW).putInt("panel_h", panelH).apply()
                panelView?.let {
                    panelParams?.width = panelW
                    panelParams?.height = panelH
                    try {
                        windowManager.updateViewLayout(it, panelParams)
                    } catch (_: Exception) {
                    }
                }
                return true
            }
        }
        return false
    }

    private fun toggleLock() {
        panelLocked = !panelLocked
        prefs.edit().putBoolean("panel_locked", panelLocked).apply()
        lockButton?.text = if (panelLocked) "🔒" else "🔓"
    }

    private fun performGlobal(action: Int) {
        try {
            performGlobalAction(action)
        } catch (_: Exception) {
        }
    }

    private fun handleHeaderDrag(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                panelTouched()
                headerLastX = event.rawX
                headerLastY = event.rawY
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (panelLocked) return true
                val dx = event.rawX - headerLastX
                val dy = event.rawY - headerLastY
                headerLastX = event.rawX
                headerLastY = event.rawY
                // AZAD: mitune harja bereh, hatta rooye notification bar / navbar
                // faghat kamelan kharej-e screen man dast nemikunem
                panelX = (panelX + dx).toInt().coerceIn(-panelW / 3, (screenWidth - panelW * 2 / 3))
                panelY = (panelY + dy).toInt().coerceIn(-dp(20f).toInt(), (screenHeight - dp(40f)).toInt())
                prefs.edit().putInt("panel_x", panelX).putInt("panel_y", panelY).apply()
                val v = panelView
                if (v != null && panelParams != null) {
                    panelParams?.x = panelX
                    panelParams?.y = panelY
                    try {
                        windowManager.updateViewLayout(v, panelParams)
                    } catch (_: Exception) {
                    }
                } else if (handleView != null && handleParams != null) {
                    handleParams?.x = panelX
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

    private val idleFadeRunnable = Runnable { setPanelAlpha(0.35f) }

    private fun panelTouched() {
        setPanelAlpha(1f)
        handler.removeCallbacks(idleFadeRunnable)
        handler.postDelayed(idleFadeRunnable, 2500L)
    }

    private fun setPanelAlpha(alpha: Float) {
        panelView?.animate()?.alpha(alpha)?.setDuration(300)?.start()
        handleView?.animate()?.alpha(alpha)?.setDuration(300)?.start()
    }

    private fun pulseCursor() {
        val v = cursorView ?: return
        v.animate().scaleX(0.6f).scaleY(0.6f).setDuration(70)
            .withEndAction {
                v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }.start()
    }

    private fun panelButton(text: String, onClick: () -> Unit, weight: Float): TextView {
        val b = TextView(this)
        b.text = text
        b.setTextColor(Color.WHITE)
        b.textSize = 12f
        b.gravity = Gravity.CENTER
        b.setBackgroundColor(0x2EFFFFFF)
        val lp = LinearLayout.LayoutParams(0, dp(28f).toInt(), weight)
        val m = dp(1.5f).toInt()
        lp.setMargins(m, m, m, m)
        b.layoutParams = lp
        b.setOnClickListener { onClick() }
        return b
    }

    private fun buildPanel(): View {
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        val bg = android.graphics.drawable.GradientDrawable()
        bg.setColor(0xE6141414.toInt())
        bg.cornerRadius = dp(18f)
        panel.background = bg
        panel.clipToOutline = true
        val p = dp(2.5f).toInt()
        panel.setPadding(p, p, p, p)

        // Header: drag(≡) + flip(⇄) + collapse(—) — compact
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL

        val grip = TextView(this)
        grip.text = "◎"
        grip.setTextColor(Color.WHITE)
        grip.textSize = 14f
        grip.gravity = Gravity.CENTER
        grip.layoutParams = LinearLayout.LayoutParams(0, dp(22f).toInt(), 1f)
        grip.setOnTouchListener { _, event -> handleHeaderDrag(event) }
        header.addView(grip)
        header.setOnTouchListener { _, event -> handleHeaderDrag(event) }

        val resize = TextView(this)
        resize.text = "↘"
        resize.setTextColor(Color.WHITE)
        resize.textSize = 11f
        resize.gravity = Gravity.CENTER
        resize.layoutParams = LinearLayout.LayoutParams(0, dp(22f).toInt(), 1f)
        resize.setOnTouchListener { _, event -> handleResizeDrag(event) }
        header.addView(resize)

        val lock = TextView(this)
        lock.text = if (panelLocked) "🔒" else "🔓"
        lock.setTextColor(Color.WHITE)
        lock.textSize = 10f
        lock.gravity = Gravity.CENTER
        lock.layoutParams = LinearLayout.LayoutParams(0, dp(22f).toInt(), 1f)
        lock.setOnClickListener { toggleLock() }
        lockButton = lock
        header.addView(lock)

        val minBtn = TextView(this)
        minBtn.text = "—"
        minBtn.setTextColor(Color.WHITE)
        minBtn.textSize = 11f
        minBtn.gravity = Gravity.CENTER
        minBtn.layoutParams = LinearLayout.LayoutParams(0, dp(22f).toInt(), 1f)
        minBtn.setOnClickListener { collapsePanel() }
        header.addView(minBtn)

        panel.addView(header)

        // Trackpad: moraba-e gooshe-gerd (~ andazeh do angosht)
        val track = View(this)
        val tb = android.graphics.drawable.GradientDrawable()
        tb.setColor(0x2EFFFFFF)
        tb.cornerRadius = dp(16f)
        track.background = tb
        track.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        )
        track.minimumHeight = dp(70f).toInt()
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
        row1.addView(panelButton("L", { performLeftClick() }, 1f / 3f))
        row1.addView(panelButton("R", { performRightClick() }, 1f / 3f))
        row1.addView(panelButton("H", { performHover() }, 1f / 3f).also { hoverBtn = it })
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

        // System navigation (gesture-haye vaghei-e sistem + global action)
        val row3 = LinearLayout(this)
        row3.orientation = LinearLayout.HORIZONTAL
        row3.addView(panelButton("◀", { performGlobal(GLOBAL_ACTION_BACK) }, 1f / 3f))
        row3.addView(panelButton("⌂", { performGlobal(GLOBAL_ACTION_HOME) }, 1f / 3f))
        row3.addView(panelButton("≡", { performGlobal(GLOBAL_ACTION_RECENTS) }, 1f / 3f))
        panel.addView(row3)

        // Notification + Quick Settings ba gesture-e vaghei (keshidan az bala/paein)
        val row4 = LinearLayout(this)
        row4.orientation = LinearLayout.HORIZONTAL
        row4.addView(panelButton("🔔⬇", { openNotificationByGesture() }, 0.5f))
        row4.addView(panelButton("⚙⬆", { openQuickSettingsByGesture() }, 0.5f))
        panel.addView(row4)

        val row5 = LinearLayout(this)
        row5.orientation = LinearLayout.HORIZONTAL
        if (Build.VERSION.SDK_INT >= 28) {
            row5.addView(panelButton("📷", { performGlobal(GLOBAL_ACTION_TAKE_SCREENSHOT) }, 1f / 3f))
            row5.addView(panelButton("🔒scr", { performGlobal(GLOBAL_ACTION_LOCK_SCREEN) }, 1f / 3f))
        } else {
            row5.addView(panelButton("🔔", { performGlobal(GLOBAL_ACTION_NOTIFICATIONS) }, 0.5f))
            row5.addView(panelButton("", {}, 0.5f))
        }
        row5.addView(panelButton("✕", { setMouseVisible(false) }, if (Build.VERSION.SDK_INT >= 28) 1f / 3f else 0.001f))
        panel.addView(row5)

        return panel
    }

    private fun handlePadTouch(event: MotionEvent): Boolean {
        panelTouched()
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
                    if (dragMode) dragPath?.lineTo(clickX(), clickY())
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
        addItem("هاور (باز کردن منوها)") { performHover() }
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
        dragPath = Path().apply { moveTo(clickX(), clickY()) }
        dragButton?.text = "رها کردن ⬆"
    }

    private fun endDrag() {
        dragMode = false
        dragButton?.text = "درگ ✋"
        val path = dragPath
        dragPath = null
        if (path == null) return
        path.lineTo(clickX(), clickY())
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

    private fun tipOffset(): FloatArray {
        val size = cursorParams.width.toFloat()
        return when (MouseConfig.shape) {
            CursorView.SHAPE_CENTER, CursorView.SHAPE_CIRCLE -> floatArrayOf(size / 2f, size / 2f)
            CursorView.SHAPE_HAND -> floatArrayOf(size * 17f / 34f, size * 2f / 34f)
            else -> floatArrayOf(size * 4f / 34f, size * 2f / 34f)
        }
    }

    private fun clickX(): Float = cursorX + tipOffset()[0] + MouseConfig.offsetX

    private fun clickY(): Float = cursorY + tipOffset()[1] + MouseConfig.offsetY

    private fun strokeAt(x: Float, y: Float, durationMs: Long): GestureDescription {
        val path = Path()
        path.moveTo(x, y)
        path.lineTo(x + 0.5f, y + 0.5f)
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
    }

    private fun performLeftClick() {
        pulseCursor()
        if (hoverActive) {
            endHover()
            handler.postDelayed({ dispatchGesture(strokeAt(clickX(), clickY(), 90L), null, null) }, 150L)
            return
        }
        dispatchGesture(strokeAt(clickX(), clickY(), 90L), null, null)
    }

    private fun performRightClick() {
        pulseCursor()
        if (hoverActive) {
            endHover()
            handler.postDelayed({ dispatchGesture(strokeAt(clickX(), clickY(), 600L), null, null) }, 150L)
            return
        }
        dispatchGesture(strokeAt(clickX(), clickY(), 600L), null, null)
    }

    private fun performHover() {
        pulseCursor()
        if (Build.VERSION.SDK_INT >= 26) {
            // Hover-e CHASB: yek bar H bezan -> press shoroo misheh
            // bad ba trackpad harakat kon -> menu-haye peykan-dar baz mimunan
            // dobare H bezan -> raha misheh
            if (hoverActive) endHover() else startHover()
            return
        }
        // API < 26: press-e 350ms (rahnema-ye qadim)
        val cx = clickX()
        val cy = clickY()
        val path = Path()
        path.moveTo(cx, cy)
        path.lineTo(cx + 0.5f, cy + 0.5f)
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 350L))
                .build(),
            null, null
        )
    }

    private fun startHover() {
        hoverX = clickX()
        hoverY = clickY()
        val path = Path()
        path.moveTo(hoverX, hoverY)
        path.lineTo(hoverX + 0.5f, hoverY + 0.5f)
        val stroke = GestureDescription.StrokeDescription(path, 0, 60L, true)
        hoverStroke = stroke
        hoverActive = true
        hoverBtn?.text = "H●"
        try {
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        } catch (_: Exception) {
        }
    }

    private fun continueHover() {
        val stroke = hoverStroke ?: return
        val nx = clickX()
        val ny = clickY()
        if (abs(nx - hoverX) < 2f && abs(ny - hoverY) < 2f) return
        val path = Path()
        path.moveTo(hoverX, hoverY)
        path.lineTo(nx, ny)
        try {
            val next = stroke.continueStroke(path, 0, 80L, true)
            hoverStroke = next
            hoverX = nx
            hoverY = ny
            dispatchGesture(GestureDescription.Builder().addStroke(next).build(), null, null)
        } catch (_: Exception) {
            hoverActive = false
            hoverStroke = null
            hoverBtn?.text = "H"
        }
    }

    fun endHover() {
        if (!hoverActive) return
        hoverActive = false
        hoverBtn?.text = "H"
        val stroke = hoverStroke
        hoverStroke = null
        if (stroke == null) return
        val path = Path()
        path.moveTo(hoverX, hoverY)
        path.lineTo(hoverX + 0.5f, hoverY + 0.5f)
        try {
            val next = stroke.continueStroke(path, 0, 40L, false)
            dispatchGesture(GestureDescription.Builder().addStroke(next).build(), null, null)
        } catch (_: Exception) {
        }
    }

    private fun performDoubleClick() {
        pulseCursor()
        if (hoverActive) {
            endHover()
            handler.postDelayed({ performDoubleClickGesture() }, 150L)
            return
        }
        performDoubleClickGesture()
    }

    private fun performDoubleClickGesture() {
        val cx = clickX()
        val cy = clickY()
        val path1 = Path()
        path1.moveTo(cx, cy)
        path1.lineTo(cx + 0.5f, cy + 0.5f)
        val path2 = Path()
        path2.moveTo(cx, cy)
        path2.lineTo(cx + 0.5f, cy + 0.5f)
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
        val cx = clickX()
        val cy = clickY()
        val dist = 360f
        val endY = if (up) min((screenHeight - 1).toFloat(), cy + dist) else max(1f, cy - dist)
        val path = Path()
        path.moveTo(cx, cy)
        path.lineTo(cx, endY)
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 250L))
                .build(),
            null,
            null
        )
    }

    // ---------------- System Gestures (mouse-e kamel bedon OTG) ----------------

    // Keshidane notification bar az bala-e screen (shade-e status bar)
    private fun openNotificationByGesture() {
        val cx = screenWidth / 2f
        val path = Path()
        path.moveTo(cx, 2f)
        path.lineTo(cx, (screenHeight * 0.5f))
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 300L))
                .build(), null, null
        )
    }

    // Keshidan az paein = quick settings / navigation gesture bar
    private fun openQuickSettingsByGesture() {
        val cx = screenWidth / 2f
        val startY = (screenHeight - 8f)
        val path = Path()
        path.moveTo(cx, startY)
        path.lineTo(cx, (screenHeight * 0.4f))
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 300L))
                .build(), null, null
        )
    }

    // Gesture-e Back-e gesture-nav (keshidan az kenar-e chap)
    private fun backByGesture() {
        val cy = screenHeight / 2f
        val path = Path()
        path.moveTo(4f, cy)
        path.lineTo((screenWidth * 0.35f), cy)
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 220L))
                .build(), null, null
        )
    }

    // Gesture-e Home (swipe bala az paein)
    private fun homeByGesture() {
        val cx = screenWidth / 2f
        val startY = (screenHeight - 8f)
        val path = Path()
        path.moveTo(cx, startY)
        path.lineTo(cx, (screenHeight * 0.55f))
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 250L))
                .build(), null, null
        )
    }

    // Gesture-e Recents (swipe bala-o-seghat az paein)
    private fun recentsByGesture() {
        val cx = screenWidth / 2f
        val startY = (screenHeight - 8f)
        val path = Path()
        path.moveTo(cx, startY)
        path.lineTo(cx, (screenHeight * 0.55f))
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 180L))
                .addStroke(GestureDescription.StrokeDescription(path, 250, 400L))
                .build(), null, null
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
