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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * Onda de audio del reproductor, con la vida de las antiguas montañas de sonido: varias líneas
 * luminosas en degradado (claro → color de cabina → azul → violeta), picos que suben y bajan y un
 * relleno brillante debajo de la principal.
 *
 * Con el espectro real ([setBands]) los picos siguen la música. Si suena pero no hay espectro
 * (OfflineTune por Bluetooth: el estéreo no "oye" ese audio), los picos laten con un ritmo propio
 * para que no se vea plana. Sin música queda una ondulación suave, o quieta si la animación en
 * reposo está apagada.
 */
class WaveLinesView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val bands = FloatArray(32)
    private val smooth = FloatArray(32)
    private var playing = false
    private var idleAnimation = true
    private var running = false
    private var phase = 0f
    private var lastBandsAt = 0L
    @ColorInt private var accent = 0xFF00E5FF.toInt()

    private val paths = Array(LINES) { Path() }
    private val fillPath = Path()
    private val glow = Array(LINES) { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND } }
    private val core = Array(LINES) { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND } }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val env = FloatArray(SAMPLES + 1)

    init {
        setAccent(accent)
    }

    fun setAccent(@ColorInt c: Int) {
        accent = c
        buildShaders()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = buildShaders()

    /** Degradado horizontal como las montañas: claro, color de cabina, azul y violeta. */
    private fun buildShaders() {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val c = accent
        val stops = intArrayOf(ThemeManager.lighten(c, 0.45f), c, hue(c, 35f), hue(c, 70f))
        for (i in 0 until LINES) {
            val alpha = if (i == 0) 255 else 200 - i * 25
            val colors = IntArray(stops.size) { ThemeManager.withAlpha(stops[it], alpha) }
            val shader = LinearGradient(0f, 0f, w, 0f, colors, null, Shader.TileMode.CLAMP)
            core[i].shader = shader
            core[i].strokeWidth = (if (i == 0) 3f else 1.8f) * density
            glow[i].shader = LinearGradient(0f, 0f, w, 0f, IntArray(stops.size) { ThemeManager.withAlpha(stops[it], 60) }, null, Shader.TileMode.CLAMP)
            glow[i].strokeWidth = (if (i == 0) 12f else 6f) * density
        }
        // Relleno: brillo del color de cabina que se desvanece hacia abajo.
        fill.shader = LinearGradient(0f, 0f, 0f, h, ThemeManager.withAlpha(c, 90), ThemeManager.withAlpha(hue(c, 35f), 0), Shader.TileMode.CLAMP)
    }

    fun setBands(values: FloatArray) {
        val n = minOf(values.size, bands.size)
        System.arraycopy(values, 0, bands, 0, n)
        if (values.any { it > 0.02f }) lastBandsAt = System.currentTimeMillis()
    }

    fun setPlaying(on: Boolean) {
        playing = on
        if (!on) bands.fill(0f)
        restart()
    }

    fun setIdleAnimation(on: Boolean) {
        idleAnimation = on
        restart()
    }

    fun setRunning(on: Boolean) {
        running = on
        restart()
    }

    private fun restart() {
        removeCallbacks(frame)
        if (running) post(frame)
    }

    private val frame = object : Runnable {
        override fun run() {
            val animate = playing || idleAnimation
            phase += if (playing) 0.14f else 0.05f
            val real = System.currentTimeMillis() - lastBandsAt < 1_500
            for (i in smooth.indices) {
                // Sin espectro real mientras suena: ritmo propio para que la onda no se vea plana.
                val target = if (playing && !real) pulse(i) else bands[i]
                smooth[i] += (target - smooth[i]) * 0.3f
            }
            invalidate()
            if (running && animate) postDelayed(this, if (playing) 40L else 90L)
        }
    }

    /** Pulso por banda: graves más fuertes, con golpes que se repiten como un compás. */
    private fun pulse(i: Int): Float {
        val t = phase
        val beat = sin(t * 1.9f).let { if (it > 0) it.pow(6) else 0f }
        val lowBoost = 1f - i / bands.size.toFloat()
        val wave = 0.5f + 0.5f * sin(t * (0.7f + i * 0.09f) + i * 0.8f)
        return (0.18f + 0.45f * wave * (0.4f + 0.6f * lowBoost) + 0.35f * beat * lowBoost).coerceIn(0f, 1f)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(frame)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cy = h * 0.62f
        val maxAmp = h * 0.58f
        val idle = if (playing) 0.10f else 0.22f

        // Envolvente con picos separados (como las montañas): cada pico sigue un grupo de bandas.
        for (s in 0..SAMPLES) {
            val t = s / SAMPLES.toFloat()
            var e = idle * sin(PI * t).toFloat().pow(0.7f)
            for (p in PEAK_X.indices) {
                val d = (t - PEAK_X[p]) / PEAK_W
                val bell = exp(-d * d)
                val start = p * smooth.size / PEAK_X.size
                var energy = 0f
                for (k in start until start + smooth.size / PEAK_X.size) energy += smooth[k]
                energy /= (smooth.size / PEAK_X.size)
                e += bell * energy * PEAK_MAX[p]
            }
            env[s] = e.coerceAtMost(1f)
        }

        for (i in LINES - 1 downTo 0) {
            val path = paths[i]
            path.rewind()
            val freq = FREQS[i]
            val off = OFFSETS[i]
            val scale = 1f - i * 0.16f
            for (s in 0..SAMPLES) {
                val t = s / SAMPLES.toFloat()
                // La principal es un contorno de montaña (siempre hacia arriba); las demás ondulan.
                val shape = if (i == 0) abs(sin(PI * freq * t + phase * 0.6f + off).toFloat()).coerceAtLeast(0.55f)
                            else sin(2 * PI * freq * t + phase * (1f + i * 0.3f) + off).toFloat()
                val y = cy - maxAmp * env[s] * scale * shape
                val x = t * w
                if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            if (i == 0) {
                fillPath.rewind()
                fillPath.addPath(path)
                fillPath.lineTo(w, h)
                fillPath.lineTo(0f, h)
                fillPath.close()
                canvas.drawPath(fillPath, fill)
            }
            canvas.drawPath(path, glow[i])
            canvas.drawPath(path, core[i])
        }
    }

    private fun hue(@ColorInt c: Int, shift: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        hsv[0] = (hsv[0] + shift) % 360f
        return Color.HSVToColor(Color.alpha(c), hsv)
    }

    private companion object {
        const val LINES = 4
        const val SAMPLES = 80
        val FREQS = floatArrayOf(2.6f, 3.4f, 4.4f, 2.2f)
        val OFFSETS = floatArrayOf(0f, 1.7f, 3.4f, 5.1f)
        /** Picos separados de izquierda a derecha, más altos en los graves. */
        val PEAK_X = floatArrayOf(0.12f, 0.3f, 0.48f, 0.66f, 0.84f)
        val PEAK_MAX = floatArrayOf(1.0f, 0.9f, 0.75f, 0.6f, 0.5f)
        const val PEAK_W = 0.09f
    }
}
