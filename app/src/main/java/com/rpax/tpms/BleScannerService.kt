package com.rpax.tpms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.location.Location
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.Wearable

/**
 * Foreground service that:
 *  - Scans BLE advertisements from the two configured DJTPMS sensors
 *    (front/rear MAC addresses come from TpmsSettings, editable in the
 *    app's settings screen -- not hardcoded, so any pair of DJTPMS-protocol
 *    sensors can be paired without a code change)
 *  - Tracks GPS speed via FusedLocationProviderClient
 *  - Raises audible / haptic / Wear OS alerts when thresholds are exceeded
 *  - Broadcasts decoded readings to DashboardActivity via intents
 */
class BleScannerService : Service() {

    private lateinit var settings: TpmsSettings
    private lateinit var bleScanner: BluetoothLeScanner
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var vibrator: Vibrator
    private var soundPool: SoundPool? = null
    private var alertSoundId: Int = 0
    private lateinit var messageClient: MessageClient
    private lateinit var nodeClient: NodeClient

    private var lastFrontAlert = false
    private var lastRearAlert = false
    private var lastSpeedKmh = 0

    // Sensor pairing/acquisition state. When non-null, handleScanResult()
    // treats the *next* valid DJTPMS frame from an unassigned MAC (i.e. not
    // the sensor already paired to the other position) as the sensor for
    // this position, and binds it -- no hub/module to command, since these
    // sensors broadcast advertisements directly and we're already scanning.
    private var pairingPosition: TpmsDecoder.Position? = null
    private var pairingTimeoutRunnable: Runnable? = null

    // Counts consecutive onScanFailed calls, reset on every successful scan
    // result. Used to back off before restarting the scan -- an immediate
    // restart on every failure risks tripping Android's own
    // SCAN_FAILED_SCANNING_TOO_FREQUENTLY throttle again, making things
    // worse instead of better (matches the observed pattern of frames
    // arriving less and less often over time, rather than stopping outright).
    private var scanFailureCount = 0
    private var scanRestartRunnable: Runnable? = null

    // Outlier filter: a single implausible jump in pressure/temperature
    // (e.g. a corrupted frame slipping past the length check) is held back
    // as a "pending" candidate instead of being shown immediately. It's
    // only accepted once a second, consecutive frame confirms the same new
    // value -- a real physical change will keep reporting it, a one-off
    // glitch won't repeat identically.
    private var lastConfirmedFront: TpmsDecoder.TpmsReading? = null
    private var lastConfirmedRear: TpmsDecoder.TpmsReading? = null
    private var pendingFront: TpmsDecoder.TpmsReading? = null
    private var pendingRear: TpmsDecoder.TpmsReading? = null
    private var pendingRejectStreakFront = 0
    private var pendingRejectStreakRear = 0

    private val alertHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val frontAlertRunnables = mutableListOf<Runnable>()
    private val rearAlertRunnables = mutableListOf<Runnable>()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location: Location = result.lastLocation ?: return
            val speedMs = location.speed
            lastSpeedKmh = (speedMs * 3.6f).toInt().coerceAtLeast(0)
            broadcastSpeed(lastSpeedKmh)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { handleScanResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            scanFailureCount++
            stopScanning()
            scanRestartRunnable?.let { alertHandler.removeCallbacks(it) }
            val backoffMs = (2_000L * scanFailureCount).coerceAtMost(30_000L)
            val restartRunnable = Runnable { startScanning() }
            scanRestartRunnable = restartRunnable
            alertHandler.postDelayed(restartRunnable, backoffMs)
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = TpmsSettings(this)
        vibrator = getVibrator()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        messageClient = Wearable.getMessageClient(this)
        nodeClient = Wearable.getNodeClient(this)
        setupSoundPool()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_PAIRING -> {
                val positionName = intent.getStringExtra(EXTRA_PAIRING_POSITION)
                val position = positionName?.let {
                    runCatching { TpmsDecoder.Position.valueOf(it) }.getOrNull()
                }
                if (position != null && position != TpmsDecoder.Position.UNKNOWN) {
                    startPairing(position)
                }
                return START_STICKY
            }
            ACTION_CANCEL_PAIRING -> {
                cancelPairing(notifyTimeout = false)
                return START_STICKY
            }
            ACTION_REFRESH_SCAN -> {
                // Forces an immediate scan restart, bypassing any backoff
                // delay currently pending. Call this when the app returns
                // to the foreground (e.g. DashboardActivity.onResume()) --
                // Android/MIUI silently downgrades BLE scan duty cycle
                // while the app is backgrounded/screen-off, even for a
                // foreground service, and this snaps it back to full speed
                // instead of waiting out however much backoff had built up.
                scanFailureCount = 0
                scanRestartRunnable?.let { alertHandler.removeCallbacks(it) }
                scanRestartRunnable = null
                stopScanning()
                startScanning()
                return START_STICKY
            }
        }

        val notification = buildForegroundNotification("RPax TPMS active", "Monitoring tire pressure")
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            else 0
        )
        startScanning()
        startLocationUpdates()
        return START_STICKY
    }

    override fun onDestroy() {
        stopScanning()
        scanRestartRunnable?.let { alertHandler.removeCallbacks(it) }
        scanRestartRunnable = null
        stopLocationUpdates()
        cancelPairing(notifyTimeout = false)
        cancelAlertSequence(frontAlertRunnables)
        cancelAlertSequence(rearAlertRunnables)
        soundPool?.release()
        soundPool = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------- BLE

    private fun startScanning() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter ?: return
        if (!adapter.isEnabled) return
        bleScanner = adapter.bluetoothLeScanner ?: return

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            bleScanner.startScan(null, scanSettings, scanCallback)
        } catch (_: SecurityException) {
            // Missing BLUETOOTH_SCAN permission; nothing to do until re-granted.
        }
    }

    private fun stopScanning() {
        try {
            if (::bleScanner.isInitialized) {
                bleScanner.stopScan(scanCallback)
            }
        } catch (_: SecurityException) {
            // Ignore; scan already inactive.
        }
    }

    private fun handleScanResult(result: ScanResult) {
        scanFailureCount = 0
        val mac = result.device.address ?: return

        val manufacturerData = result.scanRecord?.manufacturerSpecificData
        val manufacturerPayload = if (manufacturerData != null && manufacturerData.size() > 0) {
            manufacturerData.valueAt(0)
        } else null
        // The 2-byte manufacturer/company ID Android parsed out of the
        // advertisement -- required by TpmsDecoder.decode() to validate
        // the frame's checksum (that ID varies between sensors, e.g. front
        // used 0x0000 and rear used 0x0800 in one real capture, so it must
        // be read per-frame, never assumed to be 0).
        val manufacturerCompanyId = if (manufacturerData != null && manufacturerData.size() > 0) {
            manufacturerData.keyAt(0)
        } else 0

        // Diagnostic: log the ENTIRE raw scan record (result.scanRecord?.bytes),
        // not just the manufacturerSpecificData slice, for any device whose
        // advertised name contains "TPMS".
        val deviceName = try { result.device.name } catch (_: SecurityException) { null }
        if (deviceName != null && deviceName.contains("TPMS", ignoreCase = true)) {
            FullScanRecordLog.record(mac, result.rssi, result.scanRecord?.bytes, manufacturerPayload)
        }

        val payload = manufacturerPayload ?: return

        val currentPairing = pairingPosition
        if (currentPairing != null && tryAcceptPairingCandidate(currentPairing, mac, manufacturerCompanyId, payload)) {
            return
        }

        val position = TpmsDecoder.positionForMac(mac, settings.frontMac, settings.rearMac)
        if (position == TpmsDecoder.Position.UNKNOWN) return

        val reading = TpmsDecoder.decode(position, mac, manufacturerCompanyId, payload) ?: return

        // Always log the raw frame, even one the outlier filter below ends
        // up rejecting -- that's exactly what makes a glitch like a single
        // wild temperature/pressure spike diagnosable afterwards.
        RawFrameLog.record(position, mac, payload, reading.pressureBar, reading.temperatureC)

        val accepted = filterOutlier(reading) ?: return
        processReading(accepted)
    }

    /**
     * Returns [reading] if it's plausible given the last confirmed value for
     * its position, or if it closely matches a pending candidate from the
     * previous frame (two consecutive agreeing readings = accept as a real
     * change). Otherwise stores it as the new pending candidate and returns
     * null, holding it back from the UI/alerts for one frame.
     *
     * Safety valve: if readings keep getting rejected several times in a
     * row, the newest one is accepted anyway regardless of match. Without
     * this, a *wrong* confirmed baseline (e.g. from two glitchy frames that
     * happened to agree with each other) could get permanently stuck --
     * every subsequent *correct* reading would look like "the outlier"
     * relative to that bad baseline, and natural sensor jitter frame-to-
     * frame means it might never precisely re-match a single pending
     * snapshot twice. A bounded streak of rejections is a stronger signal
     * of a real, sustained change than of ongoing random corruption.
     */
    private fun filterOutlier(reading: TpmsDecoder.TpmsReading): TpmsDecoder.TpmsReading? {
        val lastConfirmed = confirmedFor(reading.position)

        if (isPlausibleJump(lastConfirmed, reading)) {
            acceptReading(reading)
            return reading
        }

        val pending = pendingFor(reading.position)
        val matchesPending = pending != null &&
            kotlin.math.abs(reading.pressureBar - pending.pressureBar) <= PENDING_MATCH_PRESSURE_TOLERANCE &&
            kotlin.math.abs(reading.temperatureC - pending.temperatureC) <= PENDING_MATCH_TEMP_TOLERANCE

        val streak = rejectStreakFor(reading.position) + 1
        setRejectStreak(reading.position, streak)

        if (matchesPending || streak >= MAX_REJECT_STREAK) {
            acceptReading(reading)
            return reading
        }

        setPending(reading.position, reading)
        return null
    }

    private fun acceptReading(reading: TpmsDecoder.TpmsReading) {
        setConfirmed(reading)
        setPending(reading.position, null)
        setRejectStreak(reading.position, 0)
    }

    private fun isPlausibleJump(previous: TpmsDecoder.TpmsReading?, candidate: TpmsDecoder.TpmsReading): Boolean {
        val prev = previous ?: return true
        val pressureDelta = kotlin.math.abs(candidate.pressureBar - prev.pressureBar)
        val tempDelta = kotlin.math.abs(candidate.temperatureC - prev.temperatureC)
        return pressureDelta <= MAX_PLAUSIBLE_PRESSURE_JUMP && tempDelta <= MAX_PLAUSIBLE_TEMP_JUMP
    }

    private fun confirmedFor(position: TpmsDecoder.Position): TpmsDecoder.TpmsReading? = when (position) {
        TpmsDecoder.Position.FRONT -> lastConfirmedFront
        TpmsDecoder.Position.REAR -> lastConfirmedRear
        TpmsDecoder.Position.UNKNOWN -> null
    }

    private fun pendingFor(position: TpmsDecoder.Position): TpmsDecoder.TpmsReading? = when (position) {
        TpmsDecoder.Position.FRONT -> pendingFront
        TpmsDecoder.Position.REAR -> pendingRear
        TpmsDecoder.Position.UNKNOWN -> null
    }

    private fun rejectStreakFor(position: TpmsDecoder.Position): Int = when (position) {
        TpmsDecoder.Position.FRONT -> pendingRejectStreakFront
        TpmsDecoder.Position.REAR -> pendingRejectStreakRear
        TpmsDecoder.Position.UNKNOWN -> 0
    }

    private fun setRejectStreak(position: TpmsDecoder.Position, value: Int) {
        when (position) {
            TpmsDecoder.Position.FRONT -> pendingRejectStreakFront = value
            TpmsDecoder.Position.REAR -> pendingRejectStreakRear = value
            TpmsDecoder.Position.UNKNOWN -> Unit
        }
    }

    private fun setConfirmed(reading: TpmsDecoder.TpmsReading) {
        when (reading.position) {
            TpmsDecoder.Position.FRONT -> lastConfirmedFront = reading
            TpmsDecoder.Position.REAR -> lastConfirmedRear = reading
            TpmsDecoder.Position.UNKNOWN -> Unit
        }
    }

    private fun setPending(position: TpmsDecoder.Position, reading: TpmsDecoder.TpmsReading?) {
        when (position) {
            TpmsDecoder.Position.FRONT -> pendingFront = reading
            TpmsDecoder.Position.REAR -> pendingRear = reading
            TpmsDecoder.Position.UNKNOWN -> Unit
        }
    }

    /**
     * While [position] is being paired, binds the first MAC that (a) isn't
     * already the sensor paired to the *other* position, and (b) decodes as
     * a plausible DJTPMS frame (length check in TpmsDecoder.decode) -- so we
     * don't accidentally pair with an unrelated nearby BLE device. Returns
     * true if this scan result was consumed by pairing (whether accepted or
     * still just "not a match yet"), so the caller skips normal processing
     * for it either way.
     */
    private fun tryAcceptPairingCandidate(
        position: TpmsDecoder.Position,
        mac: String,
        companyId: Int,
        payload: ByteArray
    ): Boolean {
        val otherMac = if (position == TpmsDecoder.Position.FRONT) settings.rearMac else settings.frontMac
        if (mac.equals(otherMac, ignoreCase = true)) return false

        if (TpmsDecoder.decode(position, mac, companyId, payload) == null) return false

        when (position) {
            TpmsDecoder.Position.FRONT -> settings.frontMac = mac
            TpmsDecoder.Position.REAR -> settings.rearMac = mac
            TpmsDecoder.Position.UNKNOWN -> Unit
        }
        broadcastPairingResult(position, mac)
        cancelPairing(notifyTimeout = false)
        return true
    }

    private fun startPairing(position: TpmsDecoder.Position) {
        pairingTimeoutRunnable?.let { alertHandler.removeCallbacks(it) }
        pairingPosition = position
        val timeoutRunnable = Runnable { cancelPairing(notifyTimeout = true) }
        pairingTimeoutRunnable = timeoutRunnable
        alertHandler.postDelayed(timeoutRunnable, PAIRING_TIMEOUT_MS)
    }

    private fun cancelPairing(notifyTimeout: Boolean) {
        val wasPairing = pairingPosition
        pairingTimeoutRunnable?.let { alertHandler.removeCallbacks(it) }
        pairingTimeoutRunnable = null
        pairingPosition = null
        if (notifyTimeout && wasPairing != null) {
            broadcastPairingTimeout(wasPairing)
        }
    }

    private fun broadcastPairingResult(position: TpmsDecoder.Position, mac: String) {
        val intent = Intent(ACTION_PAIRING_RESULT).apply {
            setPackage(packageName)
            putExtra(EXTRA_PAIRING_POSITION, position.name)
            putExtra(EXTRA_PAIRING_MAC, mac)
        }
        sendBroadcast(intent)
    }

    private fun broadcastPairingTimeout(position: TpmsDecoder.Position) {
        val intent = Intent(ACTION_PAIRING_TIMEOUT).apply {
            setPackage(packageName)
            putExtra(EXTRA_PAIRING_POSITION, position.name)
        }
        sendBroadcast(intent)
    }

    private fun processReading(reading: TpmsDecoder.TpmsReading) {
        val isAlert = when (reading.position) {
            TpmsDecoder.Position.FRONT -> settings.isFrontAlert(reading.pressureBar)
            TpmsDecoder.Position.REAR -> settings.isRearAlert(reading.pressureBar)
            TpmsDecoder.Position.UNKNOWN -> false
        } || settings.isTempAlert(reading.temperatureC)

        when (reading.position) {
            TpmsDecoder.Position.FRONT -> {
                if (isAlert && !lastFrontAlert) scheduleAlertSequence("LOW FRONT PRESSURE", frontAlertRunnables)
                if (!isAlert && lastFrontAlert) cancelAlertSequence(frontAlertRunnables)
                lastFrontAlert = isAlert
            }
            TpmsDecoder.Position.REAR -> {
                if (isAlert && !lastRearAlert) scheduleAlertSequence("LOW REAR PRESSURE", rearAlertRunnables)
                if (!isAlert && lastRearAlert) cancelAlertSequence(rearAlertRunnables)
                lastRearAlert = isAlert
            }
            TpmsDecoder.Position.UNKNOWN -> Unit
        }

        broadcastReading(reading, isAlert)
    }

    /**
     * Fires [message] as an alert immediately, then twice more 30 and 60
     * seconds later (three alerts total), unless cancelled early by
     * [cancelAlertSequence] once the underlying condition resolves.
     */
    private fun scheduleAlertSequence(message: String, runnables: MutableList<Runnable>) {
        cancelAlertSequence(runnables)
        val repeatCount = 3
        val intervalMs = 30_000L
        for (i in 0 until repeatCount) {
            val runnable = Runnable { triggerAlert(message) }
            runnables.add(runnable)
            alertHandler.postDelayed(runnable, i * intervalMs)
        }
    }

    private fun cancelAlertSequence(runnables: MutableList<Runnable>) {
        runnables.forEach { alertHandler.removeCallbacks(it) }
        runnables.clear()
    }

    // ------------------------------------------------------------- Alerts

    private fun triggerAlert(message: String) {
        if (settings.soundAlertsEnabled) playAlertSound()
        if (settings.vibrationAlertsEnabled) vibrateAlert()
        if (settings.watchNotificationsEnabled) sendWatchNotification(message)
        showAlertNotification(message)
    }

    private fun setupSoundPool() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(attrs)
            .build()
        alertSoundId = soundPool?.load(this, R.raw.tpms_alert_beep, 1) ?: 0
    }

    private fun playAlertSound() {
        soundPool?.play(alertSoundId, 1f, 1f, 1, 0, 1f)
    }

    private fun getVibrator(): Vibrator {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    private fun vibrateAlert() {
        if (!vibrator.hasVibrator()) return
        val pattern = longArrayOf(0, 250, 100, 250, 100, 400)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }

    private fun sendWatchNotification(message: String) {
        nodeClient.connectedNodes.addOnSuccessListener { nodes ->
            val payload = message.toByteArray(Charsets.UTF_8)
            for (node in nodes) {
                messageClient.sendMessage(node.id, WEAR_ALERT_PATH, payload)
            }
        }
    }

    private fun showAlertNotification(message: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tpms_icon)
            .setContentTitle("RPax TPMS Alert")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(ALERT_NOTIFICATION_ID, notification)
    }

    // ------------------------------------------------------------------ GPS

    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .build()
        try {
            fusedLocationClient.requestLocationUpdates(request, locationCallback, mainLooper)
        } catch (_: SecurityException) {
            // Missing ACCESS_FINE_LOCATION; speed will remain unavailable.
        }
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    // ------------------------------------------------------------ Broadcasts

    private fun broadcastReading(reading: TpmsDecoder.TpmsReading, isAlert: Boolean) {
        val intent = Intent(ACTION_TPMS_UPDATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_POSITION, reading.position.name)
            putExtra(EXTRA_PRESSURE, reading.pressureBar)
            putExtra(EXTRA_TEMP, reading.temperatureC)
            putExtra(EXTRA_BATTERY_OK, reading.batteryOk)
            putExtra(EXTRA_ALERT, isAlert)
        }
        sendBroadcast(intent)
    }

    private fun broadcastSpeed(speedKmh: Int) {
        val intent = Intent(ACTION_SPEED_UPDATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_SPEED, speedKmh)
        }
        sendBroadcast(intent)
    }

    // -------------------------------------------------------- Notification

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            SERVICE_CHANNEL_ID, "TPMS Monitoring", NotificationManager.IMPORTANCE_LOW
        )
        val alertChannel = NotificationChannel(
            ALERT_CHANNEL_ID, "TPMS Alerts", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            enableVibration(true)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
        manager.createNotificationChannel(alertChannel)
    }

    private fun buildForegroundNotification(title: String, text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, DashboardActivity::class.java).apply {
                // Without these flags, tapping the notification while
                // DashboardActivity is already running (e.g. in a
                // different task, which the launcher vs. this
                // notification can each start) creates a *second*
                // instance/task instead of bringing the existing one to
                // front -- symptom: the user has to back out/close the
                // app twice, since two separate instances are stacked.
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tpms_icon)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_TPMS_UPDATE = "com.rpax.tpms.ACTION_TPMS_UPDATE"
        const val ACTION_SPEED_UPDATE = "com.rpax.tpms.ACTION_SPEED_UPDATE"
        const val ACTION_START_PAIRING = "com.rpax.tpms.ACTION_START_PAIRING"
        const val ACTION_CANCEL_PAIRING = "com.rpax.tpms.ACTION_CANCEL_PAIRING"
        const val ACTION_REFRESH_SCAN = "com.rpax.tpms.ACTION_REFRESH_SCAN"
        const val ACTION_PAIRING_RESULT = "com.rpax.tpms.ACTION_PAIRING_RESULT"
        const val ACTION_PAIRING_TIMEOUT = "com.rpax.tpms.ACTION_PAIRING_TIMEOUT"

        const val EXTRA_POSITION = "extra_position"
        const val EXTRA_PRESSURE = "extra_pressure"
        const val EXTRA_TEMP = "extra_temp"
        const val EXTRA_BATTERY_OK = "extra_battery_ok"
        const val EXTRA_ALERT = "extra_alert"
        const val EXTRA_SPEED = "extra_speed"
        const val EXTRA_PAIRING_POSITION = "extra_pairing_position"
        const val EXTRA_PAIRING_MAC = "extra_pairing_mac"

        private const val PAIRING_TIMEOUT_MS = 60_000L

        // Between two consecutive frames from the same sensor (typically
        // seconds to at most ~1 minute apart), a real physical pressure or
        // temperature change won't plausibly jump by more than this --
        // anything bigger is more likely a corrupted frame than reality.
        private const val MAX_PLAUSIBLE_PRESSURE_JUMP = 0.4f
        private const val MAX_PLAUSIBLE_TEMP_JUMP = 15
        private const val PENDING_MATCH_PRESSURE_TOLERANCE = 0.05f
        private const val PENDING_MATCH_TEMP_TOLERANCE = 2
        // After this many consecutive "implausible" readings in a row for a
        // position, accept the latest one unconditionally rather than risk
        // staying stuck on a stale/wrong confirmed baseline forever.
        private const val MAX_REJECT_STREAK = 3

        private const val NOTIFICATION_ID = 1001
        private const val ALERT_NOTIFICATION_ID = 1002
        private const val SERVICE_CHANNEL_ID = "rpax_tpms_service"
        private const val ALERT_CHANNEL_ID = "rpax_tpms_alerts"
        private const val WEAR_ALERT_PATH = "/rpax/tpms/alert"
    }
}
