package com.iheii.veloradrive

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.format.DateFormat
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorInt
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Reloj digital holográfico: esfera de vidrio con aros en órbita inclinada, hora grande con
 * resplandor, fecha en español y un anillo de 60 marcas que se van encendiendo con los segundos.
 * Redibuja una vez por segundo (alineado al cambio de segundo); [setRunning] (false) lo detiene.
 */
class HoloClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    @ColorInt private var accent = 0xFF00E5FF.toInt()
    private var running = false

    private val spanish = Locale.forLanguageTag("es")
    private val dateFormat = SimpleDateFormat("EEE d MMM", spanish)
    private val calendar = Calendar.getInstance()
    private val minuteCos = FloatArray(60) { cos(Math.toRadians(it * 6.0 - 90)).toFloat() }
    private val minuteSin = FloatArray(60) { sin(Math.toRadians(it * 6.0 - 90)).toFloat() }

    private var cx = 0f
    private var cy = 0f
    private var r = 0f
    private val oval = RectF()

    private val spherePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-light", Typeface.ITALIC)
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        letterSpacing = 0.15f
    }

    fun setAccent(@ColorInt c: Int) {
        accent = c
        buildShaders()
        invalidate()
    }

    fun setRunning(on: Boolean) {
        running = on
        removeCallbacks(tick)
        if (on) post(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            if (running) postDelayed(this, 1_000L - VeloraClock.now() % 1_000L)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(tick)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        r = min(w, h) / 2f * 0.86f
        cx = w / 2f
        cy = h / 2f
        timePaint.textSize = r * 0.48f
        subPaint.textSize = r * 0.14f
        buildShaders()
    }

    private fun buildShaders() {
        if (r <= 0f) return
        spherePaint.shader = RadialGradient(
            cx - r * 0.25f, cy - r * 0.3f, r * 1.1f,
            intArrayOf(ThemeManager.withAlpha(accent, 70), ThemeManager.withAlpha(accent, 22), ThemeManager.withAlpha(accent, 5)),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP,
        )
        shinePaint.shader = RadialGradient(
            cx - r * 0.4f, cy - r * 0.48f, r * 0.55f,
            intArrayOf(0x44FFFFFF, 0x00FFFFFF),
            null,
            Shader.TileMode.CLAMP,
        )
        timePaint.color = 0xFFEFFCFF.toInt()
        timePaint.setShadowLayer(6f * density, 0f, 0f, ThemeManager.withAlpha(accent, 170))
        subPaint.color = ThemeManager.withAlpha(accent, 230)
    }

    override fun onDraw(canvas: Canvas) {
        calendar.timeZone = VeloraClock.zone()
        calendar.timeInMillis = VeloraClock.now()
        val sec = calendar.get(Calendar.SECOND)

        // Esfera de vidrio
        canvas.drawCircle(cx, cy, r, spherePaint)
        canvas.drawCircle(cx, cy, r, shinePaint)

        // Aros en órbita inclinada (giran un poco con los segundos, efecto 3D)
        for ((i, tilt) in floatArrayOf(-28f, 62f).withIndex()) {
            canvas.save()
            canvas.rotate(tilt + sec * if (i == 0) 0.5f else -0.5f, cx, cy)
            oval.set(cx - r * 1.12f, cy - r * 0.42f, cx + r * 1.12f, cy + r * 0.42f)
            glowPaint.strokeWidth = 6 * density
            glowPaint.color = ThemeManager.withAlpha(accent, 26)
            canvas.drawOval(oval, glowPaint)
            ringPaint.strokeWidth = 1.2f * density
            ringPaint.color = ThemeManager.withAlpha(accent, 130)
            canvas.drawOval(oval, ringPaint)
            canvas.restore()
        }

        // Aros concéntricos
        for ((f, a) in listOf(1f to 220, 0.8f to 90)) {
            ringPaint.strokeWidth = (if (f == 1f) 2f else 1f) * density
            ringPaint.color = ThemeManager.withAlpha(accent, a)
            canvas.drawCircle(cx, cy, r * f, ringPaint)
        }
        glowPaint.strokeWidth = 8 * density
        glowPaint.color = ThemeManager.withAlpha(accent, 40)
        canvas.drawCircle(cx, cy, r, glowPaint)

        // Anillo de segundos: las marcas hasta el segundo actual se encienden.
        for (i in 0 until 60) {
            val major = i % 5 == 0
            val lit = i <= sec
            val len = r * if (major) 0.1f else 0.055f
            tickPaint.strokeWidth = (if (major) 2.4f else 1.4f) * density
            tickPaint.color = when {
                i == sec -> ThemeManager.lighten(accent, 0.7f)
                lit -> accent
                else -> ThemeManager.withAlpha(accent, 55)
            }
            val r0 = r * 0.96f
            canvas.drawLine(cx + r0 * minuteCos[i], cy + r0 * minuteSin[i], cx + (r0 - len) * minuteCos[i], cy + (r0 - len) * minuteSin[i], tickPaint)
        }

        // Hora digital, fecha y segundos
        val now = Date(calendar.timeInMillis)
        val is24 = DateFormat.is24HourFormat(context)
        dateFormat.timeZone = calendar.timeZone
        val time = SimpleDateFormat(if (is24) "HH:mm" else "h:mm", spanish).apply { timeZone = calendar.timeZone }.format(now)
        val baseline = cy + timePaint.textSize * 0.32f
        canvas.drawText(time, cx, baseline, timePaint)
        val date = dateFormat.format(now).replace(".", "").uppercase(spanish)
        canvas.drawText(date, cx, cy - timePaint.textSize * 0.62f, subPaint)
        val bottom = if (is24) "%02d".format(sec) else "%02d  %s".format(sec, if (calendar.get(Calendar.AM_PM) == 0) "AM" else "PM")
        canvas.drawText(bottom, cx, baseline + subPaint.textSize * 1.9f, subPaint)
    }
}
