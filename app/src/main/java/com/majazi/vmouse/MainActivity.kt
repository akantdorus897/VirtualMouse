package com.majazi.vmouse

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var sensitivityLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        sensitivityLabel = findViewById(R.id.sensitivity_label)

        val prefs = getSharedPreferences("vmouse", Context.MODE_PRIVATE)
        val saved = prefs.getFloat("sensitivity", 1.6f)
        MouseAccessibilityService.sensitivity = saved

        val seekBar = findViewById<SeekBar>(R.id.sensitivity_seek)
        seekBar.max = 35
        seekBar.progress = ((saved - 0.5f) / 0.1f).toInt().coerceIn(0, 35)
        updateSensitivityLabel(saved)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = 0.5f + progress * 0.1f
                MouseAccessibilityService.sensitivity = value
                prefs.edit().putFloat("sensitivity", value).apply()
                updateSensitivityLabel(value)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        findViewById<Button>(R.id.btn_accessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun updateSensitivityLabel(value: Float) {
        sensitivityLabel.text = getString(R.string.sensitivity_value, value)
    }

    override fun onResume() {
        super.onResume()
        val active = MouseAccessibilityService.instance != null
        statusText.text = if (active) {
            getString(R.string.status_active)
        } else {
            getString(R.string.status_inactive)
        }
        statusText.setTextColor(if (active) Color.rgb(0, 150, 70) else Color.rgb(200, 60, 60))
    }
}
