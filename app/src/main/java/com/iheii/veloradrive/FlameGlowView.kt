package com.iheii.veloradrive

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorInt
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Aro de fuego del screensaver, dibujado con trazos superpuestos y un halo radial (sin blur por
 * software, que en un A7 sería caro). Cada golpe de bajo expande el aro 5-15% y lanza chispas.
 * El color sale de "pref_screensaver_fire_color" ([setFireColors]), independiente de la cabina.
 * Solo anima mientras [setRunning] es true; detenido no consume CPU.
 */
class FlameGlowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density

    private var running = false
    private var phase = 0f
    /** Expansión por el último golpe de bajo (0 = reposo, decae sola). */
    private var boost = 0f
    private var reactive = false

    private var cx = 0f
    private var cy = 0f
    private var baseRadius = 0f

    @ColorInt private var outerColor = 0xFFFF3D00.toInt()
    @ColorInt private var innerColor = 0xFFFF9100.toInt()

    private val points = 120
    /** Desplazamiento radial de cada capa: las exteriores quedan por fuera, el núcleo claro por dentro. */
    private val layerOffset = floatArrayOf(0.07f, 0.04f, 0.015f, 0f, -0.01f)
    private val cosT = FloatArray(points) { cos(2 * Math.PI * it / points).toFloat() }
    private val sinT = FloatArray(points) { sin(2 * Math.PI * it / points).toFloat() }
    private val path = Path()

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flamePaints = Array(5) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeWidth = floatArrayOf(34f, 22f, 13f, 6f, 1.5f)[it] * density
        }
    }
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var sparkHot = 0
    private var sparkCool = 0

    // Chispas en un pool fijo: sin asignaciones mientras anima.
    private val sparkCount = 70
    private val sparkAngle = FloatArray(sparkCount)
    private val sparkDist = FloatArray(sparkCount)
    private val sparkSpeed = FloatArray(sparkCount)
    private val sparkLife = FloatArray(sparkCount)

    init {
        applyColors()
    }

    fun setFireColors(@ColorInt outer: Int, @ColorInt inner: Int) {
        outerColor = outer
        innerColor = inner
        applyColors()
        buildHalo()
        invalidate()
    }

    private fun applyColors() {
        val core = ThemeManager.lighten(inner(), 0.7f)
        val colors = intArrayOf(
            ThemeManager.withAlpha(outerColor, 0x22),
            ThemeManager.withAlpha(outerColor, 0x55),
            ThemeManager.withAlpha(innerColor, 0x99),
            ThemeManager.withAlpha(ThemeManager.lighten(innerColor, 0.3f), 0xCC),
            ThemeManager.withAlpha(core, 0x88),
        )
        flamePaints.forEachIndexed { i, p -> p.color = colors[i] }
        sparkHot = ThemeManager.lighten(innerColor, 0.5f)
        sparkCool = innerColor
    }

    private fun inner() = innerColor

    fun setRunning(on: Boolean) {
        running = on
        removeCallbacks(frame)
        if (on) post(frame)
    }

    /** true si el Visualizer funciona: el fuego sigue el bajo. false: llama tenue de reposo. */
    fun setReactive(on: Boolean) {
        reactive = on
    }

    fun onBeat(strength: Float) {
        boost = (0.05f + 0.10f * strength).coerceAtLeast(boost)
        val burst = (8 + 16 * strength).toInt()
        var spawned = 0
        for (i in 0 until sparkCount) {
            if (sparkLife[i] <= 0f && spawned < burst) {
                spawn(i)
                spawned++
            }
        }
    }

    private fun spawn(i: Int) {
        sparkAngle[i] = Random.nextFloat() * 6.2832f
        sparkDist[i] = baseRadius * (0.95f + Random.nextFloat() * 0.15f)
        sparkSpeed[i] = baseRadius * (0.004f + Random.nextFloat() * 0.01f)
        sparkLife[i] = 1f
    }

    private val frame = object : Runnable {
        override fun run() {
            phase += if (reactive) 0.09f else 0.05f
            boost *= 0.88f
            // Algunas chispas sueltas también en reposo, como brasas.
            if (Random.nextFloat() < 0.25f) {
                val i = sparkLife.indexOfFirst { it <= 0f }
                if (i >= 0) spawn(i)
            }
            for (i in 0 until sparkCount) {
                if (sparkLife[i] > 0f) {
                    sparkDist[i] += sparkSpeed[i]
                    sparkLife[i] -= 0.025f
                }
            }
            invalidate()
            // ~30 fps con música; ~15 fps en reposo para gastar menos.
            if (running) postDelayed(this, if (reactive) 33L else 66L)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(frame)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cx = w / 2f
        cy = h / 2f
        baseRadius = min(w, h) * 0.38f
        buildHalo()
    }

    private fun buildHalo() {
        if (baseRadius <= 0f) return
        haloPaint.shader = RadialGradient(
            cx, cy, baseRadius * 1.5f,
            intArrayOf(
                Color.TRANSPARENT, Color.TRANSPARENT,
                ThemeManager.withAlpha(outerColor, 0x55), ThemeManager.withAlpha(innerColor, 0x22), Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.45f, 0.66f, 0.8f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val scale = 1f + boost
        canvas.save()
        canvas.scale(scale, scale, cx, cy)
        canvas.drawCircle(cx, cy, baseRadius * 1.5f, haloPaint)

        // Lenguas de fuego: radio modulado por ondas que se desplazan con el tiempo.
        for ((layer, paint) in flamePaints.withIndex()) {
            path.rewind()
            val l = layer * 0.7f
            for (i in 0..points) {
                val k = i % points
                val a = 6.2832f * k / points
                // Cada capa con su propia fase: las lenguas no quedan alineadas y parecen fuego.
                val flicker = 0.025f * sin(23 * a + phase * 4.1f + l * 2.3f) * (0.6f + 0.4f * sin(phase * 0.7f + a * 3))
                val r = baseRadius * (1f + layerOffset[layer] +
                    0.05f * sin(6 * a + phase * 1.3f + l) +
                    0.035f * sin(11 * a - phase * 2.1f + l * 1.7f) +
                    flicker)
                val x = cx + r * cosT[k]
                val y = cy + r * sinT[k]
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, paint)
        }
        canvas.restore()

        for (i in 0 until sparkCount) {
            val life = sparkLife[i]
            if (life <= 0f) continue
            val d = sparkDist[i] * scale
            sparkPaint.color = if (life > 0.5f) sparkHot else sparkCool
            sparkPaint.alpha = (255 * life).toInt()
            canvas.drawCircle(cx + d * cos(sparkAngle[i]), cy + d * sin(sparkAngle[i]), 1.6f * density * (0.5f + life), sparkPaint)
        }
    }
}
