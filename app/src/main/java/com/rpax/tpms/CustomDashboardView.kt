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

    private val motoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D8D8D8")
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
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
        // Red glow under the wheel when alerting.
        if (alert) {
            val glowCx = rect.centerX()
            val glowCy = rect.bottom - 20f
            val glowRadius = rect.width() * 0.28f
            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    glowCx, glowCy, glowRadius,
                    Color.parseColor("#88E74C3C"), Color.parseColor("#00E74C3C"),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawCircle(glowCx, glowCy, glowRadius, glowPaint)
        }

        // Simplified cruiser silhouette: wheels + frame stroke path.
        val cx = rect.centerX()
        val cy = rect.centerY()
        val scale = minOf(rect.width(), rect.height()) / 260f

        val wheelRadius = 42f * scale
        val frontWheelX = cx + 90f * scale
        val rearWheelX = cx - 90f * scale
        val wheelY = cy + 60f * scale

        canvas.drawCircle(frontWheelX, wheelY, wheelRadius, motoPaint)
        canvas.drawCircle(rearWheelX, wheelY, wheelRadius, motoPaint)
        canvas.drawCircle(frontWheelX, wheelY, wheelRadius * 0.35f, motoPaint)
        canvas.drawCircle(rearWheelX, wheelY, wheelRadius * 0.35f, motoPaint)

        val frame = Path().apply {
            moveTo(rearWheelX, wheelY - wheelRadius * 0.2f)
            lineTo(cx - 20f * scale, cy - 40f * scale)
            lineTo(cx + 40f * scale, cy - 55f * scale)
            lineTo(frontWheelX, wheelY - wheelRadius * 0.2f)
            moveTo(cx - 20f * scale, cy - 40f * scale)
            lineTo(cx - 60f * scale, cy + 10f * scale)
            lineTo(rearWheelX, wheelY - wheelRadius * 0.2f)
            moveTo(cx + 40f * scale, cy - 55f * scale)
            lineTo(cx + 65f * scale, cy - 90f * scale)
            moveTo(cx - 20f * scale, cy - 40f * scale)
            lineTo(cx - 10f * scale, cy - 95f * scale)
        }
        canvas.drawPath(frame, motoPaint)
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
