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
import com.google.android.gms.wearable.Wearable

/**
 * Foreground service that:
 *  - Scans BLE advertisements from the two DJTPMS sensors (front/rear)
 *  - Tracks GPS speed via FusedLocationProviderClient
 *  - Raises audible / haptic / Wear OS alerts when thresholds are exceeded
 *  - Broadcasts decoded readings to DashboardActivity via LocalBroadcastManager-style intents
 */
class BleScannerService : Service() {

    private lateinit var settings: TpmsSettings
    private lateinit var bleScanner: BluetoothLeScanner
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var vibrator: Vibrator
    private var soundPool: SoundPool? = null
    private var alertSoundId: Int = 0
    private lateinit var messageClient: MessageClient

    private var lastFrontAlert = false
    private var lastRearAlert = false
    private var lastSpeedKmh = 0

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
            // Restart scanning after a short delay on failure.
            stopScanning()
            startScanning()
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = TpmsSettings(this)
        vibrator = getVibrator()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        messageClient = Wearable.getMessageClient(this)
        setupSoundPool()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        stopLocationUpdates()
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
        val mac = result.device.address ?: return
        val position = TpmsDecoder.positionForMac(mac)
        if (position == TpmsDecoder.Position.UNKNOWN) return

        val manufacturerData = result.scanRecord?.manufacturerSpecificData ?: return
        if (manufacturerData.size() == 0) return

        val payload = manufacturerData.valueAt(0) ?: return
        val reading = TpmsDecoder.decode(mac, payload) ?: return

        processReading(reading)
    }

    private fun processReading(reading: TpmsDecoder.TpmsReading) {
        val isAlert = when (reading.position) {
            TpmsDecoder.Position.FRONT -> settings.isFrontAlert(reading.pressureBar)
            TpmsDecoder.Position.REAR -> settings.isRearAlert(reading.pressureBar)
            TpmsDecoder.Position.UNKNOWN -> false
        } || settings.isTempAlert(reading.temperatureC)

        when (reading.position) {
            TpmsDecoder.Position.FRONT -> {
                if (isAlert && !lastFrontAlert) triggerAlert("LOW FRONT PRESSURE")
                lastFrontAlert = isAlert
            }
            TpmsDecoder.Position.REAR -> {
                if (isAlert && !lastRearAlert) triggerAlert("LOW REAR PRESSURE")
                lastRearAlert = isAlert
            }
            TpmsDecoder.Position.UNKNOWN -> Unit
        }

        broadcastReading(reading, isAlert)
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
        messageClient.getConnectedNodes().addOnSuccessListener { nodes ->
            val payload = message.toByteArray(Charsets.UTF_8)
            nodes.forEach { node ->
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
            fusedLocationClient.requestLocationUpdates(request, locationCallback, mainLooperSafe())
        } catch (_: SecurityException) {
            // Missing ACCESS_FINE_LOCATION; speed will remain unavailable.
        }
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    private fun mainLooperSafe() = mainLooper

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
            Intent(this, DashboardActivity::class.java),
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

        const val EXTRA_POSITION = "extra_position"
        const val EXTRA_PRESSURE = "extra_pressure"
        const val EXTRA_TEMP = "extra_temp"
        const val EXTRA_BATTERY_OK = "extra_battery_ok"
        const val EXTRA_ALERT = "extra_alert"
        const val EXTRA_SPEED = "extra_speed"

        private const val NOTIFICATION_ID = 1001
        private const val ALERT_NOTIFICATION_ID = 1002
        private const val SERVICE_CHANNEL_ID = "rpax_tpms_service"
        private const val ALERT_CHANNEL_ID = "rpax_tpms_alerts"
        private const val WEAR_ALERT_PATH = "/rpax/tpms/alert"
    }
}
