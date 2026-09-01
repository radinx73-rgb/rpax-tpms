package com.rpax.tpms

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date

/**
 * Pixel-fitted 800x480 dashboard replicating the reference photo:
 *  Left: huge GPS speed + clock/date
 *  Right: status bar, cruiser motorcycle graphic with alert glow, FRONT/REAR tiles
 */
class CustomDashboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ---- Interaction ----
    var onSettingsClick: (() -> Unit)? = null
    private val settingsIconRect = RectF()

    // ---- Live data ----
    var speedKmh: Int = 0
        set(value) { field = value; invalidate() }

    var frontPressureBar: Float = 0f
    var frontTempC: Int = 0
    var frontAlert: Boolean = false

    var rearPressureBar: Float = 0f
    var rearTempC: Int = 0
    var rearAlert: Boolean = false

    private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("EEE dd MMM", Locale.getDefault())

    fun updateFront(pressure: Float, temp: Int, alert: Boolean) {
        frontPressureBar = pressure; frontTempC = temp; frontAlert = alert
        invalidate()
    }

    fun updateRear(pressure: Float, temp: Int, alert: Boolean) {
        rearPressureBar = pressure; rearTempC = temp; rearAlert = alert
        invalidate()
    }

    // ---- Colors ----
    private val bgColor = Color.parseColor("#0A0A0A")
    private val accentGreen = Color.parseColor("#2ECC71")
    private val accentRed = Color.parseColor("#E74C3C")
    private val panelColor = Color.parseColor("#161616")
    private val tileColor = Color.parseColor("#1E1E1E")
    private val textWhite = Color.parseColor("#F5F5F5")
    private val textGray = Color.parseColor("#8A8A8A")

    private val bgPaint = Paint().apply { color = bgColor }
    private val dividerPaint = Paint().apply { color = Color.parseColor("#2A2A2A"); strokeWidth = 2f }

    private val speedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textWhite
        typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        textSize = 190f
    }
    private val speedUnitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textGray
        textAlign = Paint.Align.CENTER
        textSize = 34f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }
    private val clockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textWhite
        textAlign = Paint.Align.CENTER
        textSize = 56f
        typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
    }
    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textGray
        textAlign = Paint.Align.CENTER
        textSize = 26f
        letterSpacing = 0.15f
    }

    private val statusBarTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 30f
        typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
    }

    private val tileLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textGray
        textAlign = Paint.Align.LEFT
        textSize = 22f
        letterSpacing = 0.1f
    }
    private val tileValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textWhite
        textAlign = Paint.Align.LEFT
        textSize = 46f
        typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
    }
    private val tileTempPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textGray
        textAlign = Paint.Align.LEFT
        textSize = 24f
    }

    private val settingsIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#8A8A8A")
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
        drawSettingsIcon(canvas, w)
    }

    private fun drawSettingsIcon(canvas: Canvas, w: Float) {
        val iconRadius = 16f
        val cx = w - 30f
        val cy = 30f
        settingsIconRect.set(cx - iconRadius - 12f, cy - iconRadius - 12f, cx + iconRadius + 12f, cy + iconRadius + 12f)

        canvas.drawCircle(cx, cy, iconRadius * 0.5f, settingsIconPaint)
        val teeth = 8
        for (i in 0 until teeth) {
            val angle = (2 * Math.PI * i / teeth).toFloat()
            val innerR = iconRadius * 0.65f
            val outerR = iconRadius
            val x1 = cx + innerR * Math.cos(angle.toDouble()).toFloat()
            val y1 = cy + innerR * Math.sin(angle.toDouble()).toFloat()
            val x2 = cx + outerR * Math.cos(angle.toDouble()).toFloat()
            val y2 = cy + outerR * Math.sin(angle.toDouble()).toFloat()
            canvas.drawLine(x1, y1, x2, y2, settingsIconPaint)
        }
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.action == android.view.MotionEvent.ACTION_UP) {
            if (settingsIconRect.contains(event.x, event.y)) {
                onSettingsClick?.invoke()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun drawLeftPanel(canvas: Canvas, panelWidth: Float, h: Float) {
        val centerX = panelWidth / 2f

        // Clock + date near top
        val now = Date()
        canvas.drawText(clockFormat.format(now), centerX, 70f, clockPaint)
        canvas.drawText(dateFormat.format(now).uppercase(Locale.getDefault()), centerX, 105f, datePaint)

        // Huge speed value, vertically centered
        val speedBaseline = h / 2f + 60f
        canvas.drawText(speedKmh.toString(), centerX, speedBaseline, speedPaint)
        canvas.drawText("km/h", centerX, speedBaseline + 55f, speedUnitPaint)
    }

    private fun drawRightPanel(canvas: Canvas, left: Float, right: Float, h: Float) {
        val anyAlert = frontAlert || rearAlert
        val panelLeft = left + 24f
        val panelRight = right - 24f
        val panelWidthPx = panelRight - panelLeft

        // Status bar
        val statusRect = RectF(panelLeft, 18f, panelRight, 70f)
        val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (anyAlert) accentRed else accentGreen
        }
        canvas.drawRoundRect(statusRect, 14f, 14f, statusPaint)
        val statusText = if (!anyAlert) {
            "SYSTEM OK"
        } else if (frontAlert && rearAlert) {
            "ALERT: CHECK FRONT & REAR PRESSURE"
        } else if (frontAlert) {
            "ALERT: LOW FRONT PRESSURE"
        } else {
            "ALERT: LOW REAR PRESSURE"
        }
        canvas.drawText(
            statusText,
            statusRect.centerX(),
            statusRect.centerY() + 10f,
            statusBarTextPaint
        )

        // Motorcycle graphic area
        val motoTop = statusRect.bottom + 20f
        val motoBottom = h - 160f
        val motoRect = RectF(panelLeft, motoTop, panelRight, motoBottom)
        drawMotorcycle(canvas, motoRect, anyAlert)

        // Tiles: FRONT / REAR
        val tileTop = motoBottom + 16f
        val tileBottom = h - 16f
        val tileGap = 16f
        val tileWidth = (panelWidthPx - tileGap) / 2f

        val frontTileRect = RectF(panelLeft, tileTop, panelLeft + tileWidth, tileBottom)
        val rearTileRect = RectF(panelLeft + tileWidth + tileGap, tileTop, panelRight, tileBottom)

        drawTile(canvas, frontTileRect, "FRONT", frontPressureBar, frontTempC, frontAlert)
        drawTile(canvas, rearTileRect, "REAR", rearPressureBar, rearTempC, rearAlert)
    }

    private fun drawMotorcycle(canvas: Canvas, rect: RectF, alert: Boolean) {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val scale = minOf(rect.width(), rect.height()) / 260f

        val wheelRadius = 44f * scale
        // Front wheel on the LEFT, rear wheel on the RIGHT.
        val frontWheelX = cx - 95f * scale
        val rearWheelX = cx + 95f * scale
        val wheelY = cy + 58f * scale

        // Red glow under the front wheel when alerting.
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

        // --- Wheels with spokes and fenders ---
        drawWheel(canvas, frontWheelX, wheelY, wheelRadius, bodyPaint, spokePaint)
        drawWheel(canvas, rearWheelX, wheelY, wheelRadius, bodyPaint, spokePaint)

        // Front fender
        val frontFender = Path().apply {
            addArc(
                frontWheelX - wheelRadius * 1.05f, wheelY - wheelRadius * 1.25f,
                frontWheelX + wheelRadius * 1.05f, wheelY + wheelRadius * 0.4f,
                200f, 140f
            )
        }
        canvas.drawPath(frontFender, bodyPaint)

        // Rear fender (wider, cruiser-style)
        val rearFender = Path().apply {
            addArc(
                rearWheelX - wheelRadius * 1.15f, wheelY - wheelRadius * 1.3f,
                rearWheelX + wheelRadius * 1.15f, wheelY + wheelRadius * 0.35f,
                190f, 155f
            )
        }
        canvas.drawPath(rearFender, bodyPaint)

        // --- Frame / fork / tank / seat ---
        val forkTopX = frontWheelX + 18f * scale
        val forkTopY = wheelY - wheelRadius * 2.3f
        val tankLeftX = cx - 10f * scale
        val tankTopY = cy - 48f * scale
        val seatRightX = rearWheelX - 15f * scale
        val seatY = cy - 30f * scale

        val frame = Path().apply {
            // front fork
            moveTo(frontWheelX, wheelY - wheelRadius * 0.15f)
            lineTo(forkTopX, forkTopY)
            // steering neck to tank
            lineTo(tankLeftX, tankTopY)
            // tank top curve to seat
            quadTo(cx + 20f * scale, tankTopY - 10f * scale, seatRightX - 30f * scale, seatY)
            // seat to sissy bar base
            lineTo(seatRightX, seatY)
            // down to rear axle area
            lineTo(rearWheelX, wheelY - wheelRadius * 0.15f)
            // lower frame rail back to front wheel area
            moveTo(tankLeftX, tankTopY)
            lineTo(cx - 55f * scale, cy + 15f * scale)
            lineTo(frontWheelX, wheelY - wheelRadius * 0.15f)
            moveTo(cx - 55f * scale, cy + 15f * scale)
            lineTo(rearWheelX - 25f * scale, wheelY - wheelRadius * 0.1f)
        }
        canvas.drawPath(frame, bodyPaint)

        // Windshield + handlebar
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

        // Headlight
        canvas.drawCircle(forkTopX + 6f * scale, forkTopY + 2f * scale, 7f * scale, chromePaint)

        // Sissy bar / backrest at the rear
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

        // Saddlebag under the seat
        canvas.drawRoundRect(
            RectF(
                rearWheelX - wheelRadius * 0.9f, cy + 5f * scale,
                rearWheelX + wheelRadius * 0.5f, cy + 45f * scale
            ),
            6f, 6f, chromePaint
        )

        // Exhaust pipe
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
        alert: Boolean
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

        val padding = 18f
        val textX = rect.left + padding + 40f // leave room for tpms icon glyph
        val labelBaseline = rect.top + padding + 20f
        canvas.drawText(label, textX, labelBaseline, tileLabelPaint)

        val valuePaint = if (alert) {
            Paint(tileValuePaint).apply { color = accentRed }
        } else tileValuePaint

        val valueBaseline = labelBaseline + 46f
        canvas.drawText(String.format(Locale.US, "%.1f bar", pressureBar), textX, valueBaseline, valuePaint)

        val tempBaseline = valueBaseline + 30f
        canvas.drawText("$tempC°C", textX, tempBaseline, tileTempPaint)

        // Small tire glyph to the left, colored per alert state
        val iconCx = rect.left + padding + 12f
        val iconCy = rect.centerY()
        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = if (alert) accentRed else accentGreen
        }
        canvas.drawCircle(iconCx, iconCy, 20f, iconPaint)
        canvas.drawCircle(iconCx, iconCy, 8f, iconPaint)
    }
}
