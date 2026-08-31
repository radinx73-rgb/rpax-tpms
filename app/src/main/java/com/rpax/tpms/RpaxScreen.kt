package com.rpax.tpms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.model.ItemList
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Pane
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.util.Locale

/**
 * Read-only Android Auto template screen. Shows front/rear pressure, temperature,
 * and an alert row when thresholds are exceeded. Uses PaneTemplate (driver-distraction safe).
 */
class RpaxScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {

    private var frontPressure = 0f
    private var frontTemp = 0
    private var frontAlert = false

    private var rearPressure = 0f
    private var rearTemp = 0
    private var rearAlert = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BleScannerService.ACTION_TPMS_UPDATE) return
            val position = intent.getStringExtra(BleScannerService.EXTRA_POSITION)
            val pressure = intent.getFloatExtra(BleScannerService.EXTRA_PRESSURE, 0f)
            val temp = intent.getIntExtra(BleScannerService.EXTRA_TEMP, 0)
            val alert = intent.getBooleanExtra(BleScannerService.EXTRA_ALERT, false)
            when (position) {
                TpmsDecoder.Position.FRONT.name -> {
                    frontPressure = pressure; frontTemp = temp; frontAlert = alert
                }
                TpmsDecoder.Position.REAR.name -> {
                    rearPressure = pressure; rearTemp = temp; rearAlert = alert
                }
            }
            invalidate()
        }
    }

    init {
        lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        val filter = IntentFilter(BleScannerService.ACTION_TPMS_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            carContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            carContext.registerReceiver(receiver, filter)
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        carContext.unregisterReceiver(receiver)
    }

    override fun onGetTemplate(): Template {
        val paneBuilder = Pane.Builder()

        val overallAlert = frontAlert || rearAlert
        paneBuilder.addRow(
            Row.Builder()
                .setTitle(if (overallAlert) "ALERT: check tire pressure" else "System OK")
                .build()
        )

        paneBuilder.addRow(
            Row.Builder()
                .setTitle("Front")
                .addText(String.format(Locale.US, "%.1f bar · %d°C", frontPressure, frontTemp))
                .build()
        )

        paneBuilder.addRow(
            Row.Builder()
                .setTitle("Rear")
                .addText(String.format(Locale.US, "%.1f bar · %d°C", rearPressure, rearTemp))
                .build()
        )

        return PaneTemplate.Builder(paneBuilder.build())
            .setHeaderAction(androidx.car.app.model.Action.APP_ICON)
            .setTitle("RPax TPMS")
            .build()
    }
}
