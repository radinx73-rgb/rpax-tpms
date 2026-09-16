package com.rpax.tpms

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
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
    private lateinit var frontPairButton: Button
    private lateinit var rearPairButton: Button
    private val pairingBlinkHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pairingBlinkRunnable: Runnable? = null

    private val pairingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BleScannerService.ACTION_PAIRING_RESULT -> {
                    val mac = intent.getStringExtra(BleScannerService.EXTRA_PAIRING_MAC) ?: return
                    when (intent.getStringExtra(BleScannerService.EXTRA_PAIRING_POSITION)) {
                        TpmsDecoder.Position.FRONT.name -> frontMacInput.setText(mac)
                        TpmsDecoder.Position.REAR.name -> rearMacInput.setText(mac)
                    }
                    Toast.makeText(this@MainActivity, "Sparowano czujnik: $mac", Toast.LENGTH_LONG).show()
                    setPairingButtonsEnabled(true)
                }
                BleScannerService.ACTION_PAIRING_TIMEOUT -> {
                    Toast.makeText(
                        this@MainActivity,
                        "Nie znaleziono nowego czujnika -- spróbuj ponownie",
                        Toast.LENGTH_LONG
                    ).show()
                    setPairingButtonsEnabled(true)
                }
            }
        }
    }

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

        layout.addView(fieldLabel("Front sensor MAC"))
        frontMacInput = macInput(settings.frontMac)
        layout.addView(frontMacInput)

        frontPairButton = Button(this).apply {
            text = "Paruj czujnik PRZÓD"
            setOnClickListener { beginPairing(TpmsDecoder.Position.FRONT, this) }
            setOnLongClickListener { unbindSensor(TpmsDecoder.Position.FRONT); true }
        }
        layout.addView(frontPairButton)

        layout.addView(fieldLabel("Rear sensor MAC"))
        rearMacInput = macInput(settings.rearMac)
        layout.addView(rearMacInput)

        rearPairButton = Button(this).apply {
            text = "Paruj czujnik TYŁ"
            setOnClickListener { beginPairing(TpmsDecoder.Position.REAR, this) }
            setOnLongClickListener { unbindSensor(TpmsDecoder.Position.REAR); true }
        }
        layout.addView(rearPairButton)

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

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(BleScannerService.ACTION_PAIRING_RESULT)
            addAction(BleScannerService.ACTION_PAIRING_TIMEOUT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pairingReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(pairingReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        stopPairingBlink()
        unregisterReceiver(pairingReceiver)
    }

    /**
     * Puts BleScannerService into pairing mode for [position]. The service
     * is guaranteed to already be running -- this activity is only ever
     * reached from DashboardActivity's gear icon (see class doc comment),
     * and Dashboard starts the service -- so this just flips a mode flag,
     * it doesn't need to (re)start scanning itself.
     */
    private fun beginPairing(position: TpmsDecoder.Position, sourceButton: Button) {
        setPairingButtonsEnabled(false)
        sourceButton.text = "Nakręć czujnik na wentyl... (60s)"
        startPairingBlink(sourceButton)
        val intent = Intent(this, BleScannerService::class.java).apply {
            action = BleScannerService.ACTION_START_PAIRING
            putExtra(BleScannerService.EXTRA_PAIRING_POSITION, position.name)
        }
        startService(intent)
        Toast.makeText(
            this,
            "Tryb parowania: nakręć czujnik ${if (position == TpmsDecoder.Position.FRONT) "PRZÓD" else "TYŁ"} na wentyl",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun setPairingButtonsEnabled(enabled: Boolean) {
        stopPairingBlink()
        frontPairButton.isEnabled = enabled
        rearPairButton.isEnabled = enabled
        if (enabled) {
            frontPairButton.text = "Paruj czujnik PRZÓD"
            rearPairButton.text = "Paruj czujnik TYŁ"
        }
    }

    /**
     * Visual "listening..." cue while pairing is in progress -- pulses the
     * button's alpha, same idea as the reference app's flicker thread on
     * the wheel icon, just applied to this screen instead of the dashboard
     * (which isn't visible right now, since this activity sits on top of it).
     */
    private fun startPairingBlink(button: Button) {
        stopPairingBlink()
        val runnable = object : Runnable {
            var visible = true
            override fun run() {
                visible = !visible
                button.alpha = if (visible) 1f else 0.35f
                pairingBlinkHandler.postDelayed(this, 400L)
            }
        }
        pairingBlinkRunnable = runnable
        pairingBlinkHandler.post(runnable)
    }

    private fun stopPairingBlink() {
        pairingBlinkRunnable?.let { pairingBlinkHandler.removeCallbacks(it) }
        pairingBlinkRunnable = null
        frontPairButton.alpha = 1f
        rearPairButton.alpha = 1f
    }

    /**
     * Long-press: cancels any in-progress pairing and clears this position's
     * MAC, so BleScannerService stops matching readings to it until it's
     * paired again (mirrors the reference app's long-press unbind).
     */
    private fun unbindSensor(position: TpmsDecoder.Position) {
        val intent = Intent(this, BleScannerService::class.java).apply {
            action = BleScannerService.ACTION_CANCEL_PAIRING
        }
        startService(intent)
        setPairingButtonsEnabled(true)

        val label: String
        when (position) {
            TpmsDecoder.Position.FRONT -> {
                settings.frontMac = ""
                frontMacInput.setText("")
                label = "PRZÓD"
            }
            TpmsDecoder.Position.REAR -> {
                settings.rearMac = ""
                rearMacInput.setText("")
                label = "TYŁ"
            }
            TpmsDecoder.Position.UNKNOWN -> label = ""
        }
        Toast.makeText(this, "Odwiązano czujnik $label", Toast.LENGTH_SHORT).show()
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
