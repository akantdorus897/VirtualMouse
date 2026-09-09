package com.majazi.vmouse

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var sensitivityLabel: TextView
    private lateinit var sizeLabel: TextView
    private lateinit var currentConfig: TextView
    private lateinit var statusText: TextView
    private lateinit var notifStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("vmouse", Context.MODE_PRIVATE)

        MouseConfig.sensitivityLevel = prefs.getInt("sensitivity", 8)
        MouseConfig.cursorSizeDp = prefs.getInt("size", 34)
        MouseConfig.shape = prefs.getInt("shape", CursorView.SHAPE_ARROW)
        MouseConfig.color = prefs.getInt("color", Color.WHITE)

        sensitivityLabel = findViewById(R.id.sensitivity_label)
        sizeLabel = findViewById(R.id.size_label)
        currentConfig = findViewById(R.id.current_config)
        statusText = findViewById(R.id.status_text)
        notifStatus = findViewById(R.id.notif_status)

        setupSensitivity()
        setupSize()
        setupShapeButtons()
        setupColorButtons()
        updateLabels()

        findViewById<Button>(R.id.btn_accessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btn_app_info).setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:$packageName")
                )
            )
        }

        val btnNotif = findViewById<Button>(R.id.btn_notification)
        if (Build.VERSION.SDK_INT >= 33) {
            btnNotif.setOnClickListener {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        } else {
            btnNotif.visibility = View.GONE
            notifStatus.visibility = View.GONE
        }
    }

    private fun setupSensitivity() {
        val seek = findViewById<SeekBar>(R.id.sensitivity_seek)
        seek.max = 19
        seek.progress = MouseConfig.sensitivityLevel - 1
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                MouseConfig.sensitivityLevel = progress + 1
                prefs.edit().putInt("sensitivity", MouseConfig.sensitivityLevel).apply()
                updateLabels()
                notifyService()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun setupSize() {
        val seek = findViewById<SeekBar>(R.id.size_seek)
        seek.max = 56
        seek.progress = MouseConfig.cursorSizeDp - 24
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                MouseConfig.cursorSizeDp = progress + 24
                prefs.edit().putInt("size", MouseConfig.cursorSizeDp).apply()
                updateLabels()
                notifyService()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun setupShapeButtons() {
        val map = mapOf(
            R.id.shape_arrow to CursorView.SHAPE_ARROW,
            R.id.shape_hand to CursorView.SHAPE_HAND,
            R.id.shape_center to CursorView.SHAPE_CENTER,
            R.id.shape_circle to CursorView.SHAPE_CIRCLE
        )
        for ((id, shape) in map) {
            findViewById<Button>(id).setOnClickListener {
                MouseConfig.shape = shape
                prefs.edit().putInt("shape", shape).apply()
                updateLabels()
                notifyService()
            }
        }
    }

    private fun setupColorButtons() {
        val map = mapOf(
            R.id.color_purple to Color.rgb(156, 39, 176),
            R.id.color_white to Color.WHITE,
            R.id.color_red to Color.rgb(244, 67, 54),
            R.id.color_green to Color.rgb(76, 175, 80),
            R.id.color_blue to Color.rgb(33, 150, 243)
        )
        for ((id, color) in map) {
            findViewById<Button>(id).setOnClickListener {
                MouseConfig.color = color
                prefs.edit().putInt("color", color).apply()
                updateLabels()
                notifyService()
            }
        }
    }

    private fun notifyService() {
        MouseAccessibilityService.instance?.applySettings()
    }

    private fun updateLabels() {
        sensitivityLabel.text = getString(R.string.sens_format, MouseConfig.sensitivityLevel)
        sizeLabel.text = getString(R.string.size_format, MouseConfig.cursorSizeDp)
        currentConfig.text = getString(R.string.current_format, shapeName(), colorName())
    }

    private fun shapeName(): String = when (MouseConfig.shape) {
        CursorView.SHAPE_HAND -> "دست"
        CursorView.SHAPE_CENTER -> "مرکز"
        CursorView.SHAPE_CIRCLE -> "دایره"
        else -> "پیکان"
    }

    private fun colorName(): String = when (MouseConfig.color) {
        Color.rgb(156, 39, 176) -> "بنفش"
        Color.rgb(244, 67, 54) -> "قرمز"
        Color.rgb(76, 175, 80) -> "سبز"
        Color.rgb(33, 150, 243) -> "آبی"
        else -> "سفید"
    }

    override fun onResume() {
        super.onResume()
        val active = MouseAccessibilityService.instance != null
        statusText.text = if (active) getString(R.string.status_active) else getString(R.string.status_inactive)
        statusText.setTextColor(if (active) Color.rgb(0, 150, 70) else Color.rgb(200, 60, 60))

        if (Build.VERSION.SDK_INT >= 33) {
            val ok = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            notifStatus.text = getString(R.string.notif_status, if (ok) "✅" else "❌")
            notifStatus.setTextColor(if (ok) Color.rgb(0, 150, 70) else Color.rgb(200, 60, 60))
        }
    }
}
