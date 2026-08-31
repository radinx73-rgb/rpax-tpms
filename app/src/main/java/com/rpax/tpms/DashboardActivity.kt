package com.rpax.tpms

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Fullscreen 800x480 dashboard activity for the M560-TPMS display.
 * Hosts CustomDashboardView and updates it from BleScannerService broadcasts.
 */
class DashboardActivity : ComponentActivity() {

    private lateinit var dashboardView: CustomDashboardView

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
                    val alert = intent.getBooleanExtra(BleScannerService.EXTRA_ALERT, false)
                    when (position) {
                        TpmsDecoder.Position.FRONT.name -> dashboardView.updateFront(pressure, temp, alert)
                        TpmsDecoder.Position.REAR.name -> dashboardView.updateRear(pressure, temp, alert)
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

    override fun onStop() {
        unregisterReceiver(tpmsReceiver)
        super.onStop()
    }

    private fun hasAllPermissions(): Boolean = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun startTpmsService() {
        val serviceIntent = Intent(this, BleScannerService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
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
