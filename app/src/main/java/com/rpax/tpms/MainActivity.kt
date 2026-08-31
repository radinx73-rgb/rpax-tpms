package com.rpax.tpms

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import android.view.Gravity
import android.widget.Toast

/**
 * Configuration screen for pressure/temperature thresholds and alert channels.
 * Built programmatically (no XML layout) to keep this file self-contained.
 */
class MainActivity : ComponentActivity() {

    private lateinit var settings: TpmsSettings

    private lateinit var frontMinInput: EditText
    private lateinit var frontMaxInput: EditText
    private lateinit var rearMinInput: EditText
    private lateinit var rearMaxInput: EditText
    private lateinit var maxTempInput: EditText
    private lateinit var soundCheck: CheckBox
    private lateinit var vibeCheck: CheckBox
    private lateinit var watchCheck: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = TpmsSettings(this)

        val root = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        root.addView(layout)
        setContentView(root)

        layout.addView(sectionTitle("RPax TPMS — Thresholds"))

        layout.addView(fieldLabel("Front min pressure (bar)"))
        frontMinInput = numberInput(settings.frontMinBar)
        layout.addView(frontMinInput)

        layout.addView(fieldLabel("Front max pressure (bar)"))
        frontMaxInput = numberInput(settings.frontMaxBar)
        layout.addView(frontMaxInput)

        layout.addView(fieldLabel("Rear min pressure (bar)"))
        rearMinInput = numberInput(settings.rearMinBar)
        layout.addView(rearMinInput)

        layout.addView(fieldLabel("Rear max pressure (bar)"))
        rearMaxInput = numberInput(settings.rearMaxBar)
        layout.addView(rearMaxInput)

        layout.addView(fieldLabel("Max temperature (°C)"))
        maxTempInput = numberInput(settings.maxTempC.toFloat())
        layout.addView(maxTempInput)

        layout.addView(sectionTitle("Alert channels"))

        soundCheck = CheckBox(this).apply {
            text = "Sound alerts"
            isChecked = settings.soundAlertsEnabled
        }
        layout.addView(soundCheck)

        vibeCheck = CheckBox(this).apply {
            text = "Vibration alerts"
            isChecked = settings.vibrationAlertsEnabled
        }
        layout.addView(vibeCheck)

        watchCheck = CheckBox(this).apply {
            text = "Wear OS notifications"
            isChecked = settings.watchNotificationsEnabled
        }
        layout.addView(watchCheck)

        val saveButton = Button(this).apply {
            text = "Save"
            setOnClickListener { saveSettings() }
        }
        layout.addView(saveButton)

        val launchDashboardButton = Button(this).apply {
            text = "Open Dashboard"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, DashboardActivity::class.java))
            }
        }
        layout.addView(launchDashboardButton)
    }

    private fun saveSettings() {
        settings.frontMinBar = frontMinInput.text.toString().toFloatOrNull() ?: settings.frontMinBar
        settings.frontMaxBar = frontMaxInput.text.toString().toFloatOrNull() ?: settings.frontMaxBar
        settings.rearMinBar = rearMinInput.text.toString().toFloatOrNull() ?: settings.rearMinBar
        settings.rearMaxBar = rearMaxInput.text.toString().toFloatOrNull() ?: settings.rearMaxBar
        settings.maxTempC = maxTempInput.text.toString().toFloatOrNull()?.toInt() ?: settings.maxTempC
        settings.soundAlertsEnabled = soundCheck.isChecked
        settings.vibrationAlertsEnabled = vibeCheck.isChecked
        settings.watchNotificationsEnabled = watchCheck.isChecked
        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 20f
        setPadding(0, 32, 0, 16)
        gravity = Gravity.START
    }

    private fun fieldLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setPadding(0, 16, 0, 4)
    }

    private fun numberInput(initialValue: Float): EditText = EditText(this).apply {
        inputType = android.text.InputType.TYPE_CLASS_NUMBER or
            android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
            android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        setText(initialValue.toString())
    }
}
