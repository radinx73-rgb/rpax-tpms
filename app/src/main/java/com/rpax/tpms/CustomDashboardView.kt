package com.rpax.tpms

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pixel-fitted 800x480+ dashboard replicating the reference photo:
 *  Left: huge GPS speed + clock/date
 *  Right: status bar (with settings/exit icons), cruiser motorcycle graphic
 *  with alert glow, FRONT/REAR tiles
 */
class CustomDashboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density.coerceAtLeast(1f)

    // ---- Interaction ----
    var onSettingsClick: (() -> Unit)? = null
    var onExitClick: (() -> Unit)? = null
    private val settingsIconRect = RectF()
    private val exitIconRect = RectF()

    // ---- Settings (thresholds) ----
    private val settings = TpmsSettings(context)

    // ---- Custom motorcycle artwork (optional) ----
    // If res/drawable/motorcycle_illustration.png (or .webp) exists, it is used instead
    // of the built-in vector drawing. Falls back to the vector silhouette if absent.
    private val motorcycleBitmap: Bitmap? by lazy {
        val resId = resources.getIdentifier("motorcycle_illustration", "drawable", context.packageName)
        if (resId != 0) BitmapFactory.decodeResource(resources, resId) else null
    }

    // ---- Live data ----
    var speedKmh: Int = 0
        set(value) { field = value; invalidate() }

    var frontPressureBar: Float = 0f
    var frontTempC: Int = 0
    var frontBatteryOk: Boolean = true
    private var frontLastUpdateAt: Long = 0L

    var rearPressureBar: Float = 0f
    var rearTempC: Int = 0
    var rearBatteryOk: Boolean = true
    private var rearLastUpdateAt: Long = 0L

    private val frontHasData: Boolean get() = frontLastUpdateAt != 0L
    private val rearHasData: Boolean get() = rearLastUpdateAt != 0L

    // A sensor is "stale" once too long has passed since its last real reading.
    // Chosen generously (2 minutes) so normal gaps between periodic BLE
    // transmissions -- which slow down while the bike is stationary -- never
    // get mistaken for a lost connection or a pressure alarm.
    private val staleAfterMillis = 120_000L

    private val frontStale: Boolean
        get() = frontHasData && (System.currentTimeMillis() - frontLastUpdateAt) > staleAfterMillis
    private val rearStale: Boolean
        get() = rearHasData && (System.currentTimeMillis() - rearLastUpdateAt) > staleAfterMillis

    // Real pressure/temperature alert -- only meaningful once we have a
    // reading, and only while that reading is still fresh. This is the only
    // thing allowed to trigger the red "ALERT" state; a mere gap between
    // transmissions never does.
    private val frontAlert: Boolean
        get() = frontHasData && !frontStale &&
            (settings.isFrontAlert(frontPressureBar) || settings.isTempAlert(frontTempC))
    private val rearAlert: Boolean
        get() = rearHasData && !rearStale &&
            (settings.isRearAlert(rearPressureBar) || settings.isTempAlert(rearTempC))

    private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("EEE dd MMM", Locale.getDefault())

    fun updateFront(pressure: Float, temp: Int, batteryOk: Boolean = true) {
        frontPressureBar = pressure; frontTempC = temp; frontBatteryOk = batteryOk
        frontLastUpdateAt = System.currentTimeMillis()
        invalidate()
    }

    fun updateRear(pressure: Float, temp: Int, batteryOk: Boolean = true) {
        rearPressureBar = pressure; rearTempC = temp; rearBatteryOk = batteryOk
        rearLastUpdateAt = System.currentTimeMillis()
        invalidate()
    }

    // ---- Periodic ticker: keeps clock + stale-detection current even
    // without new sensor broadcasts ----
    private val tickHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            invalidate()
            tickHandler.postDelayed(this, 1000L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        tickHandler.post(tickRunnable)
    }

    override fun onDetachedFromWindow() {
        tickHandler.removeCallbacks(tickRunnable)
        super.onDetachedFromWindow()
    }

    // ---- Colors ----
    private val bgColor = Color.parseColor("#0A0A0A")
    private val accentGreen = Color.parseColor("#2ECC71")
    private val accentRed = Color.parseColor("#E74C3C")
    private val accentAmber = Color.parseColor("#B8860B")
    private val tileColor = Color.parseColor("#1E1E1E")
    private val textWhite = Color.parseColor("#F5F5F5")
    private val textGray = Color.parseColor("#8A8A8A")
    private val neutralGray = Color.parseColor("#6E6E6E")

    private val bgPaint = Paint().apply { color = bgColor }
    private val dividerPaint = Paint().apply { color = Color.parseColor("#2A2A2A"); strokeWidth = 2f }

    // Speed digits: enlarged ~75% versus the original design (190 -> ~335).
    private val speedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textWhite
        typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        textSize = 335f
    }
    private val speedUnitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textGray
        textAlign = Paint.Align.CENTER
        textSize = 40f
    }
    private val clockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textWhite
        textAlign = Paint.Align.CENTER
        textSize = 78f
        typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
    }
    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textGray
        textAlign = Paint.Align.CENTER
        textSize = 34f
        letterSpacing = 0.15f
    }

    private val statusBarTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 34f
        typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
    }

    // Tile fonts: enlarged and centered per request.
    private val tileLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textGray
        textAlign = Paint.Align.CENTER
        textSize = 28f
        letterSpacing = 0.1f
    }
    private val tileValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textWhite
        textAlign = Paint.Align.CENTER
        textSize = 92f
        typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
    }
    private val tileTempPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textGray
        textAlign = Paint.Align.CENTER
        textSize = 36f
    }

    private val iconStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val leftWidth = w * 0.40f
        drawLeftPanel(canvas, leftWidth, h)
        canvas.drawLine(leftWidth, 20f, leftWidth, h - 20f, dividerPaint)
        drawRightPanel(canvas, leftWidth, w, h)
    }

    private fun drawLeftPanel(canvas: Canvas, panelWidth: Float, h: Float) {
        val centerX = panelWidth / 2f

        // Clock + date near top
        val now = Date()
        canvas.drawText(clockFormat.format(now), centerX, 90f, clockPaint)
        canvas.drawText(dateFormat.format(now).uppercase(Locale.getDefault()), centerX, 132f, datePaint)

        // Huge speed value, vertically centered
        val speedBaseline = h / 2f + 90f
        canvas.drawText(speedKmh.toString(), centerX, speedBaseline, speedPaint)
        canvas.drawText("km/h", centerX, speedBaseline + 60f, speedUnitPaint)
    }

    private fun drawRightPanel(canvas: Canvas, left: Float, right: Float, h: Float) {
        val anyAlert = frontAlert || rearAlert
        val noDataYet = !frontHasData && !rearHasData
        val panelLeft = left + 24f
        val panelRight = right - 24f
        val panelWidthPx = panelRight - panelLeft

        // Status bar
        val statusBarHeight = 64f * density
        val statusRect = RectF(panelLeft, 18f, panelRight, 18f + statusBarHeight)
        val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = when {
                anyAlert -> accentRed
                noDataYet -> accentAmber
                else -> accentGreen
            }
        }
        canvas.drawRoundRect(statusRect, 14f, 14f, statusPaint)
        val statusText = when {
            anyAlert && frontAlert && rearAlert -> "ALERT: CHECK FRONT & REAR PRESSURE"
            anyAlert && frontAlert -> "ALERT: LOW FRONT PRESSURE"
            anyAlert -> "ALERT: LOW REAR PRESSURE"
            noDataYet -> "WAITING FOR SENSORS"
            else -> "SYSTEM OK"
        }
        canvas.drawText(
            statusText,
            statusRect.centerX(),
            statusRect.centerY() + 12f,
            statusBarTextPaint
        )

        drawStatusBarIcons(canvas, statusRect)

        // Motorcycle graphic area
        val motoTop = statusRect.bottom + 20f
        val motoBottom = h - 260f
        val motoRect = RectF(panelLeft, motoTop, panelRight, motoBottom)
        drawMotorcycle(canvas, motoRect, anyAlert)

        // Tiles: FRONT / REAR -- extended down toward the wheels for bigger,
        // centered readouts.
        val tileTop = motoBottom + 16f
        val tileBottom = h - 16f
        val tileGap = 16f
        val tileWidth = (panelWidthPx - tileGap) / 2f

        val frontTileRect = RectF(panelLeft, tileTop, panelLeft + tileWidth, tileBottom)
        val rearTileRect = RectF(panelLeft + tileWidth + tileGap, tileTop, panelRight, tileBottom)

        drawTile(canvas, frontTileRect, "FRONT", frontPressureBar, frontTempC, frontAlert, frontHasData, frontStale, frontBatteryOk)
        drawTile(canvas, rearTileRect, "REAR", rearPressureBar, rearTempC, rearAlert, rearHasData, rearStale, rearBatteryOk)
    }

    /**
     * Settings (gear) and exit (power) icons, drawn INSIDE the status bar so
     * they're always a fixed, visible, easily-tappable size regardless of
     * screen resolution (previous version anchored them to raw pixel offsets
     * from the screen edge, which made them nearly invisible on high-res
     * displays).
     */
    private fun drawStatusBarIcons(canvas: Canvas, statusRect: RectF) {
        val iconRadius = 16f * density
        val margin = 20f * density
        val iconCy = statusRect.centerY()

        // Exit (power) icon: far right.
        val exitCx = statusRect.right - margin - iconRadius
        exitIconRect.set(
            exitCx - iconRadius - 12f * density, iconCy - iconRadius - 12f * density,
            exitCx + iconRadius + 12f * density, iconCy + iconRadius + 12f * density
        )
        canvas.drawCircle(exitCx, iconCy, iconRadius * 0.62f, iconStrokePaint)
        canvas.drawLine(exitCx, iconCy - iconRadius * 0.75f, exitCx, iconCy - iconRadius * 0.1f, iconStrokePaint)

        // Settings (gear) icon: just to the left of the exit icon.
        val gearCx = exitCx - iconRadius * 2f - margin
        settingsIconRect.set(
            gearCx - iconRadius - 12f * density, iconCy - iconRadius - 12f * density,
            gearCx + iconRadius + 12f * density, iconCy + iconRadius + 12f * density
        )
        canvas.drawCircle(gearCx, iconCy, iconRadius * 0.5f, iconStrokePaint)
        val teeth = 8
        for (i in 0 until teeth) {
            val angle = (2 * Math.PI * i / teeth).toFloat()
            val innerR = iconRadius * 0.65f
            val outerR = iconRadius
            val x1 = gearCx + innerR * Math.cos(angle.toDouble()).toFloat()
            val y1 = iconCy + innerR * Math.sin(angle.toDouble()).toFloat()
            val x2 = gearCx + outerR * Math.cos(angle.toDouble()).toFloat()
            val y2 = iconCy + outerR * Math.sin(angle.toDouble()).toFloat()
            canvas.drawLine(x1, y1, x2, y2, iconStrokePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            if (settingsIconRect.contains(event.x, event.y)) {
                onSettingsClick?.invoke()
                return true
            }
            if (exitIconRect.contains(event.x, event.y)) {
                onExitClick?.invoke()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun drawMotorcycle(canvas: Canvas, rect: RectF, alert: Boolean) {
        val bitmap = motorcycleBitmap
        if (bitmap != null) {
            drawMotorcycleBitmap(canvas, rect, alert, bitmap)
        } else {
            drawMotorcycleVector(canvas, rect, alert)
        }
    }

    private fun drawMotorcycleBitmap(canvas: Canvas, rect: RectF, alert: Boolean, bitmap: Bitmap) {
        val bitmapAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
        val rectAspect = rect.width() / rect.height()

        val drawWidth: Float
        val drawHeight: Float
        if (bitmapAspect > rectAspect) {
            drawWidth = rect.width()
            drawHeight = drawWidth / bitmapAspect
        } else {
            drawHeight = rect.height()
            drawWidth = drawHeight * bitmapAspect
        }

        val left = rect.centerX() - drawWidth / 2f
        val top = rect.centerY() - drawHeight / 2f
        val destRect = RectF(left, top, left + drawWidth, top + drawHeight)

        if (alert) {
            val glowCx = destRect.left + drawWidth * 0.22f
            val glowCy = destRect.bottom - drawHeight * 0.12f
            val glowRadius = drawWidth * 0.22f
            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    glowCx, glowCy, glowRadius,
                    Color.parseColor("#88E74C3C"), Color.parseColor("#00E74C3C"),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawCircle(glowCx, glowCy, glowRadius, glowPaint)
        }

        val srcRect = Rect(0, 0, bitmap.width, bitmap.height)
        val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
        canvas.drawBitmap(bitmap, srcRect, destRect, bitmapPaint)
    }

    private fun drawMotorcycleVector(canvas: Canvas, rect: RectF, alert: Boolean) {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val scale = minOf(rect.width(), rect.height()) / 260f

        val wheelRadius = 44f * scale
        val frontWheelX = cx - 95f * scale
        val rearWheelX = cx + 95f * scale
        val wheelY = cy + 58f * scale

        if (alert) {
            val glowCx = frontWheelX
            val glowCy = wheelY
            val glowRadius = wheelRadius * 2.2f
            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    glowCx, glowCy, glowRadius,
                    Color.parseColor("#88E74C3C"), Color.parseColor("#00E74C3C"),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawCircle(glowCx, glowCy, glowRadius, glowPaint)
        }

        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.parseColor("#E8E8E8")
        }
        val spokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
            color = Color.parseColor("#8A8A8A")
        }
        val chromePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            strokeCap = Paint.Cap.ROUND
            color = Color.parseColor("#C9C9C9")
        }

        drawWheel(canvas, frontWheelX, wheelY, wheelRadius, bodyPaint, spokePaint)
        drawWheel(canvas, rearWheelX, wheelY, wheelRadius, bodyPaint, spokePaint)

        val frontFender = Path().apply {
            addArc(
                frontWheelX - wheelRadius * 1.05f, wheelY - wheelRadius * 1.25f,
                frontWheelX + wheelRadius * 1.05f, wheelY + wheelRadius * 0.4f,
                200f, 140f
            )
        }
        canvas.drawPath(frontFender, bodyPaint)

        val rearFender = Path().apply {
            addArc(
                rearWheelX - wheelRadius * 1.15f, wheelY - wheelRadius * 1.3f,
                rearWheelX + wheelRadius * 1.15f, wheelY + wheelRadius * 0.35f,
                190f, 155f
            )
        }
        canvas.drawPath(rearFender, bodyPaint)

        val forkTopX = frontWheelX + 18f * scale
        val forkTopY = wheelY - wheelRadius * 2.3f
        val tankLeftX = cx - 10f * scale
        val tankTopY = cy - 48f * scale
        val seatRightX = rearWheelX - 15f * scale
        val seatY = cy - 30f * scale

        val frame = Path().apply {
            moveTo(frontWheelX, wheelY - wheelRadius * 0.15f)
            lineTo(forkTopX, forkTopY)
            lineTo(tankLeftX, tankTopY)
            quadTo(cx + 20f * scale, tankTopY - 10f * scale, seatRightX - 30f * scale, seatY)
            lineTo(seatRightX, seatY)
            lineTo(rearWheelX, wheelY - wheelRadius * 0.15f)
            moveTo(tankLeftX, tankTopY)
            lineTo(cx - 55f * scale, cy + 15f * scale)
            lineTo(frontWheelX, wheelY - wheelRadius * 0.15f)
            moveTo(cx - 55f * scale, cy + 15f * scale)
            lineTo(rearWheelX - 25f * scale, wheelY - wheelRadius * 0.1f)
        }
        canvas.drawPath(frame, bodyPaint)

        val windshield = Path().apply {
            moveTo(forkTopX - 6f * scale, forkTopY)
            lineTo(forkTopX + 12f * scale, forkTopY - 70f * scale)
            lineTo(forkTopX + 26f * scale, forkTopY - 4f * scale)
        }
        canvas.drawPath(windshield, chromePaint)
        canvas.drawLine(
            forkTopX - 10f * scale, forkTopY + 4f * scale,
            forkTopX + 16f * scale, forkTopY - 2f * scale,
            chromePaint
        )

        canvas.drawCircle(forkTopX + 6f * scale, forkTopY + 2f * scale, 7f * scale, chromePaint)

        val sissyBar = Path().apply {
            moveTo(seatRightX, seatY)
            lineTo(seatRightX + 4f * scale, seatY - 34f * scale)
        }
        canvas.drawPath(sissyBar, bodyPaint)
        canvas.drawRoundRect(
            RectF(
                seatRightX - 2f * scale, seatY - 46f * scale,
                seatRightX + 12f * scale, seatY - 30f * scale
            ),
            3f, 3f, bodyPaint
        )

        canvas.drawRoundRect(
            RectF(
                rearWheelX - wheelRadius * 0.9f, cy + 5f * scale,
                rearWheelX + wheelRadius * 0.5f, cy + 45f * scale
            ),
            6f, 6f, chromePaint
        )

        canvas.drawLine(
            cx - 40f * scale, cy + 40f * scale,
            rearWheelX - wheelRadius * 0.3f, wheelY + wheelRadius * 0.3f,
            chromePaint
        )
    }

    private fun drawWheel(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        radius: Float,
        rimPaint: Paint,
        spokePaint: Paint
    ) {
        canvas.drawCircle(centerX, centerY, radius, rimPaint)
        canvas.drawCircle(centerX, centerY, radius * 0.3f, rimPaint)
        val spokeCount = 12
        for (i in 0 until spokeCount) {
            val angle = (2 * Math.PI * i / spokeCount).toFloat()
            val innerR = radius * 0.32f
            val outerR = radius * 0.98f
            val startX = centerX + innerR * Math.cos(angle.toDouble()).toFloat()
            val startY = centerY + innerR * Math.sin(angle.toDouble()).toFloat()
            val endX = centerX + outerR * Math.cos(angle.toDouble()).toFloat()
            val endY = centerY + outerR * Math.sin(angle.toDouble()).toFloat()
            canvas.drawLine(startX, startY, endX, endY, spokePaint)
        }
    }

    private fun drawTile(
        canvas: Canvas,
        rect: RectF,
        label: String,
        pressureBar: Float,
        tempC: Int,
        alert: Boolean,
        hasData: Boolean,
        stale: Boolean,
        batteryOk: Boolean
    ) {
        val bgPaintTile = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (alert) Color.parseColor("#3A1414") else tileColor
        }
        canvas.drawRoundRect(rect, 16f, 16f, bgPaintTile)

        if (alert) {
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 3f
                color = accentRed
            }
            canvas.drawRoundRect(rect, 16f, 16f, borderPaint)
        }

        val centerX = rect.centerX()
        val padding = 22f

        // Small tire glyph, top-left corner (out of the way of centered text).
        val iconCx = rect.left + padding + 14f
        val iconCy = rect.top + padding + 14f
        val iconColor = when {
            alert -> accentRed
            !hasData || stale -> neutralGray
            else -> accentGreen
        }
        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = iconColor
        }
        canvas.drawCircle(iconCx, iconCy, 16f, iconPaint)
        canvas.drawCircle(iconCx, iconCy, 6f, iconPaint)

        val labelBaseline = rect.top + padding + 22f
        canvas.drawText(label, centerX, labelBaseline, tileLabelPaint)

        if (stale) {
            val stalePaint = Paint(tileLabelPaint).apply { color = accentAmber }
            canvas.drawText("NO SIGNAL", rect.right - padding - 60f, labelBaseline, Paint(stalePaint).apply {
                textAlign = Paint.Align.RIGHT
            })
        }

        val valuePaint = when {
            alert -> Paint(tileValuePaint).apply { color = accentRed }
            !hasData || stale -> Paint(tileValuePaint).apply { color = neutralGray }
            else -> tileValuePaint
        }

        val valueBaseline = rect.centerY() + 30f
        val valueText = if (hasData) String.format(Locale.US, "%.1f bar", pressureBar) else "-- bar"
        canvas.drawText(valueText, centerX, valueBaseline, valuePaint)

        val tempPaint = if (!hasData || stale) {
            Paint(tileTempPaint).apply { color = neutralGray }
        } else tileTempPaint
        val tempBaseline = valueBaseline + 46f
        val tempText = if (hasData) "$tempC°C" else "--°C"
        canvas.drawText(tempText, centerX, tempBaseline, tempPaint)

        if (hasData && !batteryOk) {
            val lowBatteryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accentRed
                textAlign = Paint.Align.RIGHT
                textSize = 24f
                typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            }
            canvas.drawText("LOW BATTERY", rect.right - padding, rect.bottom - padding + 2f, lowBatteryPaint)
        }
    }
}
