package com.rpax.tpms

import android.content.ContentValues
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import android.view.Gravity
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Configuration screen for pressure/temperature thresholds and alert channels.
 * Built programmatically (no XML layout) to keep this file self-contained.
 *
 * This activity is only ever reached from DashboardActivity's on-screen
 * settings (gear) icon, so it always sits directly on top of a
 * DashboardActivity instance in the back stack. "Open Dashboard" therefore
 * just finishes this screen -- returning to that existing instance -- rather
 * than starting a brand new one, which would otherwise stack duplicate
 * dashboards and require pressing Back repeatedly to fully exit.
 */
class MainActivity : ComponentActivity() {

    private lateinit var settings: TpmsSettings

    private lateinit var frontMacInput: EditText
    private lateinit var rearMacInput: EditText
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

        layout.addView(sectionTitle("RPax TPMS — Sensor Pairing"))

        layout.addView(fieldLabel("Front sensor MAC (e.g. 9C:7F:64:5B:2A:04)"))
        frontMacInput = macInput(settings.frontMac)
        layout.addView(frontMacInput)

        layout.addView(fieldLabel("Rear sensor MAC (e.g. 9C:7F:64:5B:2C:63)"))
        rearMacInput = macInput(settings.rearMac)
        layout.addView(rearMacInput)

        layout.addView(sectionTitle("Thresholds"))

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
            setTextColor(android.graphics.Color.WHITE)
            isChecked = settings.soundAlertsEnabled
        }
        layout.addView(soundCheck)

        vibeCheck = CheckBox(this).apply {
            text = "Vibration alerts"
            setTextColor(android.graphics.Color.WHITE)
            isChecked = settings.vibrationAlertsEnabled
        }
        layout.addView(vibeCheck)

        watchCheck = CheckBox(this).apply {
            text = "Wear OS notifications"
            setTextColor(android.graphics.Color.WHITE)
            isChecked = settings.watchNotificationsEnabled
        }
        layout.addView(watchCheck)

        layout.addView(sectionTitle("Diagnostics"))

        val exportLogButton = Button(this).apply {
            text = "Export Raw BLE Log"
            setOnClickListener { exportRawLog() }
        }
        layout.addView(exportLogButton)

        val saveButton = Button(this).apply {
            text = "Save"
            setOnClickListener { saveSettings() }
        }
        layout.addView(saveButton)

        val backToDashboardButton = Button(this).apply {
            text = "Open Dashboard"
            setOnClickListener {
                // Just close this screen -- the existing DashboardActivity
                // underneath it in the back stack becomes visible again.
                finish()
            }
        }
        layout.addView(backToDashboardButton)
    }

    private fun exportRawLog() {
        val lines = RawFrameLog.snapshot()
        if (lines.isEmpty()) {
            Toast.makeText(this, "No BLE frames captured yet -- ride a bit first", Toast.LENGTH_LONG).show()
            return
        }

        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val fileName = "rpax_ble_log_$timestamp.txt"
        val content = lines.joinToString("\n")

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val resolver = applicationContext.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            Toast.makeText(this, "Failed to create log file", Toast.LENGTH_LONG).show()
            return
        }

        resolver.openOutputStream(uri)?.use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)

        Toast.makeText(this, "Saved to Download/$fileName", Toast.LENGTH_LONG).show()
    }

    private fun saveSettings() {
        settings.frontMac = frontMacInput.text.toString()
        settings.rearMac = rearMacInput.text.toString()
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
        setTextColor(android.graphics.Color.WHITE)
        setPadding(0, 32, 0, 16)
        gravity = Gravity.START
    }

    private fun fieldLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(android.graphics.Color.parseColor("#B0B0B0"))
        setPadding(0, 16, 0, 4)
    }

    private fun macInput(initialValue: String): EditText = EditText(this).apply {
        inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        setTextColor(android.graphics.Color.WHITE)
        setHintTextColor(android.graphics.Color.parseColor("#808080"))
        hint = "XX:XX:XX:XX:XX:XX"
        setText(initialValue)
    }

    private fun numberInput(initialValue: Float): EditText = EditText(this).apply {
        inputType = android.text.InputType.TYPE_CLASS_NUMBER or
            android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
            android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        setTextColor(android.graphics.Color.WHITE)
        setHintTextColor(android.graphics.Color.parseColor("#808080"))
        setText(initialValue.toString())
    }
}
