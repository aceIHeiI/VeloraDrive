package com.iheii.veloradrive

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorInt
import kotlin.math.min

/**
 * Medidor de arco liviano para la pantalla OBD2: pista gris de 240°, arco de valor con punta
 * redonda, zona roja opcional al final y tres textos al centro (valor, unidad y estado).
 * Sin animaciones ni temporizadores: solo redibuja cuando cambia algo.
 */
class GaugeView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val condensed = Typeface.create("sans-serif-condensed", Typeface.BOLD)

    private val trackPaint = arcPaint(0xFF232833.toInt())
    private val valuePaint = arcPaint(0xFFFF6D00.toInt())
    private val redPaint = arcPaint(RED)

    private val numberPaint = textPaint(0xFFF2F6FA.toInt(), condensed)
    private val labelPaint = textPaint(0xFF8A96A3.toInt(), Typeface.create("sans-serif-condensed", Typeface.NORMAL))
    private val subPaint = textPaint(0xFF8A96A3.toInt(), condensed).apply { letterSpacing = 0.1f }

    private val oval = RectF()

    private var min = 0f
    private var max = 100f
    private var redFrom: Float? = null
    private var value: Float? = null
    private var valueText = ObdFormat.NONE
    private var label = ""
    private var sub = ""

    private fun arcPaint(@ColorInt c: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = c
    }

    private fun textPaint(@ColorInt c: Int, tf: Typeface) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = c
        typeface = tf
        textAlign = Paint.Align.CENTER
    }

    fun setRange(min: Float, max: Float, redFrom: Float? = null) {
        this.min = min
        this.max = max
        this.redFrom = redFrom
        invalidate()
    }

    fun setLabel(text: String) {
        if (text == label) return
        label = text
        invalidate()
    }

    fun setValue(v: Float?, text: String) {
        if (v == value && text == valueText) return
        value = v
        valueText = text
        invalidate()
    }

    fun setArcColor(@ColorInt c: Int) {
        if (valuePaint.color == c) return
        valuePaint.color = c
        invalidate()
    }

    fun setSub(text: String, @ColorInt c: Int) {
        if (text == sub && subPaint.color == c) return
        sub = text
        subPaint.color = c
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val cw = (w - paddingLeft - paddingRight).toFloat()
        val ch = (h - paddingTop - paddingBottom).toFloat()
        // El arco abierto abajo ocupa 1.5 radios de alto (los extremos quedan a medio radio del centro).
        var r = min(cw / 2f, ch / 1.55f)
        val stroke = r * 0.13f
        r -= stroke / 2f
        trackPaint.strokeWidth = stroke
        valuePaint.strokeWidth = stroke
        redPaint.strokeWidth = stroke * 0.55f
        val cx = paddingLeft + cw / 2f
        val cy = paddingTop + (ch - r * 1.5f - stroke) / 2f + stroke / 2f + r
        oval.set(cx - r, cy - r, cx + r, cy + r)
        numberPaint.textSize = r * 0.46f
        labelPaint.textSize = r * 0.17f
        subPaint.textSize = r * 0.14f
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawArc(oval, START, SWEEP, false, trackPaint)
        val span = max - min
        redFrom?.let {
            val f = ((it - min) / span).coerceIn(0f, 1f)
            canvas.drawArc(oval, START + SWEEP * f, SWEEP * (1f - f), false, redPaint)
        }
        value?.let {
            val f = ((it - min) / span).coerceIn(0f, 1f)
            if (f > 0f) canvas.drawArc(oval, START, SWEEP * f, false, valuePaint)
        }
        val cx = oval.centerX()
        val cy = oval.centerY()
        val r = oval.width() / 2f
        canvas.drawText(valueText, cx, cy + r * 0.06f, numberPaint)
        if (label.isNotEmpty()) canvas.drawText(label, cx, cy + r * 0.30f, labelPaint)
        if (sub.isNotEmpty()) canvas.drawText(sub, cx, cy + r * 0.58f, subPaint)
    }

    companion object {
        const val RED = 0xFFFF3D6E.toInt()
        private const val START = 150f
        private const val SWEEP = 240f
    }
}
