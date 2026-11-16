package com.iheii.veloradrive

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.annotation.ColorInt
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

/**
 * Barra superior segmentada en arco suave (tacómetro de competición). Muestra RPM 0-10 x1000 con
 * OBD2, o la velocidad 0-240 km/h por GPS sin OBD2 ([setScale]). Solo redibuja al cambiar el
 * número de segmentos encendidos.
 */
class TachBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val segments = 56

    private var labels = Array(11) { it.toString() }
    private var fraction = 0f
    private var shownFraction = 0f
    private var animator: ValueAnimator? = null

    @ColorInt private var accent = 0xFF00E5FF.toInt()
    private val offColor = Color.parseColor("#1E242C")

    private val segPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.BUTT }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.BUTT }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9AA5B1")
        strokeWidth = 1.5f * resources.displayMetrics.density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C9D2DB")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC)
    }

    // Geometría del arco: un círculo muy grande del que solo se ve la parte superior.
    private var cx = 0f
    private var cy = 0f
    private var radius = 0f
    private var halfAngle = 0f
    private val segCos = FloatArray(segments)
    private val segSin = FloatArray(segments)

    fun setAccent(@ColorInt c: Int) {
        accent = c
        glowPaint.color = ThemeManager.withAlpha(c, 70)
        invalidate()
    }

    /** Etiquetas de la escala (11 valores: 0..10 para RPM, 0..240 para velocidad). */
    fun setScale(newLabels: Array<String>) {
        labels = newLabels
        invalidate()
    }

    /** 0..1 del recorrido de la barra. Se ignoran cambios que no encienden/apagan un segmento. */
    fun setFraction(f: Float) {
        val next = f.coerceIn(0f, 1f)
        if ((next * segments).toInt() == (fraction * segments).toInt()) {
            fraction = next
            return
        }
        fraction = next
        animator?.cancel()
        animator = ValueAnimator.ofFloat(shownFraction, next).apply {
            duration = 250
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                shownFraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stop() {
        animator?.cancel()
        shownFraction = fraction
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val pad = 16 * density
        val halfW = w / 2f - pad
        val sag = h * 0.22f
        // Círculo que pasa por los extremos y por el centro elevado "sag" píxeles.
        radius = (halfW * halfW + sag * sag) / (2 * sag)
        cx = w / 2f
        cy = pad + radius
        halfAngle = asin(halfW / radius)
        for (i in 0 until segments) {
            val t = (i + 0.5f) / segments
            val a = -Math.PI / 2 - halfAngle + 2 * halfAngle * t
            segCos[i] = cos(a).toFloat()
            segSin[i] = sin(a).toFloat()
        }
        val segW = (2 * halfAngle * radius / segments) * 0.62f
        segPaint.strokeWidth = segW
        glowPaint.strokeWidth = segW * 1.9f
        labelPaint.textSize = h * 0.17f
    }

    override fun onDraw(canvas: Canvas) {
        val outer = radius
        val inner = radius - height * 0.26f
        val lit = (shownFraction * segments).toInt()
        for (i in 0 until segments) {
            val c = segCos[i]
            val s = segSin[i]
            val x1 = cx + inner * c
            val y1 = cy + inner * s
            val x2 = cx + outer * c
            val y2 = cy + outer * s
            if (i < lit) {
                canvas.drawLine(x1, y1, x2, y2, glowPaint)
                // Los últimos segmentos encendidos más claros: efecto de "punta" del barrido.
                segPaint.color = if (i >= lit - 2) ThemeManager.lighten(accent, 0.6f) else accent
            } else {
                segPaint.color = offColor
            }
            canvas.drawLine(x1, y1, x2, y2, segPaint)
        }
        // Escala bajo el arco: 11 marcas con su número.
        val tickR = inner - 4 * density
        for (k in 0..10) {
            val a = -Math.PI / 2 - halfAngle + 2 * halfAngle * k / 10.0
            val c = cos(a).toFloat()
            val s = sin(a).toFloat()
            canvas.drawLine(cx + tickR * c, cy + tickR * s, cx + (tickR - 8 * density) * c, cy + (tickR - 8 * density) * s, tickPaint)
            val lr = tickR - 8 * density - labelPaint.textSize
            canvas.drawText(labels[k], cx + lr * c, cy + lr * s + labelPaint.textSize / 3f, labelPaint)
        }
    }
}
