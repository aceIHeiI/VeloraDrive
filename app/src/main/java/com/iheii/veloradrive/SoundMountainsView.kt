package com.iheii.veloradrive

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorInt
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/**
 * "Montañas de sonido" estilo holograma:
 *  - Picos separados y afilados (de mayor a menor hacia la derecha). Cada pico se dibuja con varias
 *    líneas de contorno superpuestas, como una figura de alambre, con degradado cian → azul → violeta.
 *  - Debajo, una malla de puntos que fluye en perspectiva hacia un punto de fuga a la derecha.
 *  - Con música y Visualizer: cada pico sigue un grupo de frecuencias (graves a la izquierda).
 *    Sin datos: simulación suave para que parezca que siempre está analizando.
 * Anima a ~24 fps con música y ~10 fps en reposo; [setRunning] (false) lo detiene por completo.
 * Los shaders se crean al cambiar tamaño o color, nunca dentro de onDraw.
 */
class SoundMountainsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    @ColorInt private var accent = 0xFF00E5FF.toInt()

    private var running = false
    private var playing = false
    private var idleAnimation = true
    private var time = 0f

    private val bands = FloatArray(AudioSpectrum.BANDS)
    private var lastRealBandsAt = 0L

    // Picos: posición (0..1), ancho, altura máxima relativa y bandas que lo alimentan.
    private val peakX = floatArrayOf(0.09f, 0.22f, 0.35f, 0.48f, 0.60f, 0.71f)
    private val peakW = floatArrayOf(0.045f, 0.05f, 0.045f, 0.04f, 0.035f, 0.03f)
    private val peakMax = floatArrayOf(0.85f, 1.0f, 0.75f, 0.6f, 0.45f, 0.32f)
    private val peakBands = arrayOf(0..2, 3..5, 6..9, 10..14, 15..21, 22..31)
    private val peak = FloatArray(peakX.size)
    private val peakTarget = FloatArray(peakX.size)

    // Contornos de cada cresta (de atrás hacia adelante).
    private val contours = 6
    private val samples = 110
    private val path = Path()
    private val linePaint = Array(contours) { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND } }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPath = Path()

    // Malla de puntos en perspectiva
    private val meshLines = 22
    private val meshSamples = 56
    private val meshPts = FloatArray(meshSamples * 2)
    // Puntos cuadrados sin antialias: en la GPU cuestan mucho menos que los redondos y a este tamaño se ven igual.
    private val meshPaint = Array(meshLines) { Paint().apply { strokeCap = Paint.Cap.SQUARE } }

    fun setAccent(@ColorInt c: Int) {
        accent = c
        buildPaints()
        invalidate()
    }

    /** Espectro real 0..1 (AudioSpectrum). */
    fun setBands(values: FloatArray) {
        for (i in bands.indices) bands[i] = values.getOrElse(i) { 0f }
        lastRealBandsAt = System.currentTimeMillis()
    }

    fun setPlaying(on: Boolean) {
        playing = on
        reschedule()
    }

    fun setIdleAnimation(on: Boolean) {
        idleAnimation = on
        reschedule()
    }

    fun setRunning(on: Boolean) {
        running = on
        reschedule()
    }

    private val frame = object : Runnable {
        override fun run() {
            step()
            invalidate()
            if (shouldAnimate()) postDelayed(this, if (playing) 42L else 100L)
        }
    }

    private fun shouldAnimate() = running && (playing || idleAnimation)

    private fun reschedule() {
        removeCallbacks(frame)
        if (shouldAnimate()) post(frame) else invalidate()
    }

    private fun step() {
        time += if (playing) 0.06f else 0.025f
        val real = System.currentTimeMillis() - lastRealBandsAt < 400
        for (i in peak.indices) {
            peakTarget[i] = if (real) {
                var s = 0f
                for (b in peakBands[i]) s += bands[b]
                (s / (peakBands[i].last - peakBands[i].first + 1) * 1.25f).coerceIn(0f, 1f)
            } else {
                // Simulación: cada pico "respira" a su ritmo; con música hay golpes de bajo.
                val beat = if (playing && i < 2) (0.5f + 0.5f * sin(time * 7.5f)).let { it * it } * 0.35f else 0f
                (0.55f + 0.3f * sin(time * (1.1f + i * 0.37f) + i * 1.9f) + 0.12f * sin(time * 3.3f + i) + beat)
                    .coerceIn(0.15f, 1f) * if (playing) 1f else 0.85f
            }
            peak[i] += (peakTarget[i] - peak[i]) * if (peakTarget[i] > peak[i]) 0.5f else 0.15f
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(frame)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = buildPaints()

    private fun buildPaints() {
        if (width == 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val cyan = ThemeManager.lighten(accent, 0.15f)
        val blue = shiftHue(accent, 35f)
        val violet = shiftHue(accent, 70f)
        val stroke = LinearGradient(
            0f, 0f, w, 0f,
            intArrayOf(cyan, accent, blue, violet),
            floatArrayOf(0f, 0.3f, 0.6f, 1f),
            Shader.TileMode.CLAMP,
        )
        for (c in 0 until contours) {
            val front = c / (contours - 1f)
            linePaint[c].shader = stroke
            linePaint[c].strokeWidth = (0.7f + 1.6f * front) * density
            linePaint[c].alpha = (70 + 185 * front).toInt()
        }
        glowPaint.shader = stroke
        glowPaint.strokeWidth = 9f * density
        glowPaint.alpha = 55
        fillPaint.shader = LinearGradient(
            0f, h * 0.1f, 0f, h * 0.68f,
            intArrayOf(ThemeManager.withAlpha(ThemeManager.lighten(accent, 0.5f), 70), ThemeManager.withAlpha(blue, 25), ThemeManager.withAlpha(blue, 0)),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP,
        )
        for (l in 0 until meshLines) {
            val t = l / (meshLines - 1f)
            meshPaint[l].color = ThemeManager.withAlpha(blend(blue, ThemeManager.lighten(accent, 0.2f), t), (45 + 190 * t).toInt())
            meshPaint[l].strokeWidth = (1f + 1.6f * t) * density
        }
    }

    @ColorInt
    private fun shiftHue(@ColorInt c: Int, degrees: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        hsv[0] = (hsv[0] + degrees + 360f) % 360f
        return Color.HSVToColor(hsv)
    }

    private fun blend(@ColorInt a: Int, @ColorInt b: Int, t: Float): Int {
        fun ch(s: Int) = ((a shr s and 0xFF) * (1 - t) + (b shr s and 0xFF) * t).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Altura (0..1) de la cordillera en x01: el pico más alto domina (picos separados, no sumados). */
    private fun ridge(x01: Float, widthScale: Float): Float {
        var v = 0f
        for (i in peak.indices) {
            val d = (x01 - peakX[i]) / (peakW[i] * widthScale)
            v = max(v, peak[i] * peakMax[i] * exp(-d * d))
        }
        // Colinas bajas entre picos y desvanecido hacia la derecha.
        val hills = 0.06f + 0.04f * sin(x01 * 14f + time)
        return max(v, hills * (1f - x01))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        drawMesh(canvas, w, h)

        val base = h * 0.66f
        val ridgeHeight = h * 0.62f
        // Relleno suave bajo la cresta delantera.
        fillPath.rewind()
        fillPath.moveTo(0f, base)
        for (s in 0..samples) {
            val x01 = s / samples.toFloat()
            fillPath.lineTo(x01 * w * 0.92f, base - ridgeHeight * ridge(x01, 1f))
        }
        fillPath.lineTo(w * 0.92f, base)
        fillPath.close()
        canvas.drawPath(fillPath, fillPaint)

        // Contornos: cada uno un poco más bajo, más ancho y corrido, como capas de alambre.
        for (c in 0 until contours) {
            val back = 1f - c / (contours - 1f)
            val scale = 1f - back * 0.42f
            val shiftX = back * 10f * density
            val shiftY = back * 9f * density
            val widen = 1f + back * 0.9f
            path.rewind()
            for (s in 0..samples) {
                val x01 = s / samples.toFloat()
                val x = x01 * w * 0.92f + shiftX
                val y = base + shiftY - ridgeHeight * scale * ridge(x01, widen)
                if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            if (c == contours - 1) canvas.drawPath(path, glowPaint)
            canvas.drawPath(path, linePaint[c])
        }
    }

    /**
     * Malla de puntos: líneas que nacen abajo a la izquierda y convergen hacia un punto de fuga a la
     * derecha, ondulando un poco con el tiempo y los graves.
     */
    private fun drawMesh(canvas: Canvas, w: Float, h: Float) {
        val vanishX = w * 1.02f
        val bass = (peak[0] + peak[1]) * 0.5f
        for (l in 0 until meshLines) {
            val t = l / (meshLines - 1f)          // 0 = fondo, 1 = frente
            val startY = h * (0.62f + 0.42f * t)
            val startX = -w * 0.05f
            // Al fondo las líneas casi se juntan, pero no en un solo punto (evita el efecto "peine").
            val endY = h * (0.64f + 0.22f * t)
            for (k in 0 until meshSamples) {
                // Cada línea corrida medio paso: los puntos no se alinean en columnas.
                val u = ((k + if (l % 2 == 0) 0f else 0.5f) / (meshSamples - 1f)).coerceAtMost(1f)
                val x = startX + (vanishX - startX) * u
                val yLine = startY + (endY - startY) * u * u
                val wave = sin(u * 9f + t * 4f - time * 1.5f) * h * 0.025f * (1f - u) * (0.6f + bass)
                meshPts[k * 2] = x
                meshPts[k * 2 + 1] = yLine - wave
            }
            canvas.drawPoints(meshPts, meshPaint[l])
        }
    }
}
