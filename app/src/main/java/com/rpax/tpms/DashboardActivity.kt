package com.rpax.tpms

import android.Manifest
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Rational
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Fullscreen dashboard activity for the M560-TPMS display.
 * Hosts CustomDashboardView and updates it from BleScannerService broadcasts.
 * This is the app's launcher activity; the settings screen (MainActivity) is
 * reached only via the on-screen gear icon.
 */
class DashboardActivity : ComponentActivity() {

    private lateinit var dashboardView: CustomDashboardView

    // Set right before launching MainActivity so onUserLeaveHint() knows
    // NOT to enter Picture-in-Picture for that -- onUserLeaveHint fires
    // for any "user navigates away" event, including opening our own
    // settings screen, not just pressing Home.
    private var suppressNextPipEntry = false

    private val requiredPermissions = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasAllPermissions()) {
            startTpmsService()
        }
    }

    private val tpmsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BleScannerService.ACTION_TPMS_UPDATE -> {
                    val position = intent.getStringExtra(BleScannerService.EXTRA_POSITION)
                    val pressure = intent.getFloatExtra(BleScannerService.EXTRA_PRESSURE, 0f)
                    val temp = intent.getIntExtra(BleScannerService.EXTRA_TEMP, 0)
                    val batteryOk = intent.getBooleanExtra(BleScannerService.EXTRA_BATTERY_OK, true)
                    when (position) {
                        TpmsDecoder.Position.FRONT.name -> dashboardView.updateFront(pressure, temp, batteryOk)
                        TpmsDecoder.Position.REAR.name -> dashboardView.updateRear(pressure, temp, batteryOk)
                    }
                }
                BleScannerService.ACTION_SPEED_UPDATE -> {
                    dashboardView.speedKmh = intent.getIntExtra(BleScannerService.EXTRA_SPEED, 0)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        goFullscreen()

        dashboardView = CustomDashboardView(this)
        dashboardView.onSettingsClick = {
            suppressNextPipEntry = true
            startActivity(Intent(this, MainActivity::class.java))
        }
        dashboardView.onExitClick = {
            confirmExit()
        }
        setContentView(dashboardView)

        if (hasAllPermissions()) {
            startTpmsService()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(BleScannerService.ACTION_TPMS_UPDATE)
            addAction(BleScannerService.ACTION_SPEED_UPDATE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(tpmsReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(tpmsReceiver, filter)
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasAllPermissions()) {
            startService(Intent(this, BleScannerService::class.java).apply {
                action = BleScannerService.ACTION_REFRESH_SCAN
            })
        }
    }

    override fun onStop() {
        unregisterReceiver(tpmsReceiver)
        super.onStop()
    }

    /**
     * Fires right before the user navigates away (Home button, recent apps,
     * etc.) -- this is the standard place to enter Picture-in-Picture, so
     * the app keeps showing a small always-on-top floating window (like
     * YouTube's mini player) instead of just disappearing into the
     * background. Skipped when we're the ones launching MainActivity
     * (opening settings shouldn't trigger PiP).
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (suppressNextPipEntry) {
            suppressNextPipEntry = false
            return
        }
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(2, 1))
            .build()
        try {
            enterPictureInPictureMode(params)
        } catch (_: Exception) {
            // Some devices/manufacturers disable or don't support PiP;
            // just stay in the normal background state instead of crashing.
        }
    }

    /**
     * Switches CustomDashboardView to its compact PiP rendering (big
     * colored status dots) while in the floating window, and back to the
     * full dashboard once expanded/restored.
     */
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        dashboardView.isPipMode = isInPictureInPictureMode
    }

    private fun hasAllPermissions(): Boolean = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun startTpmsService() {
        val serviceIntent = Intent(this, BleScannerService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    /**
     * Asks for confirmation before fully shutting the app down -- this stops
     * BLE scanning and GPS tracking (via BleScannerService), removes the app
     * from Recents, and kills the process outright, so nothing keeps running
     * in the background until the user manually restarts it.
     */
    private fun confirmExit() {
        AlertDialog.Builder(this)
            .setTitle("Stop RPax TPMS?")
            .setMessage("This stops tire monitoring and closes the app completely.")
            .setPositiveButton("Stop & Close") { _, _ -> exitApp() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exitApp() {
        stopService(Intent(this, BleScannerService::class.java))
        finishAndRemoveTask()
        Process.killProcess(Process.myPid())
    }

    private fun goFullscreen() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
