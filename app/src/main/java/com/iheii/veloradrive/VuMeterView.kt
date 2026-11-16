package com.iheii.veloradrive

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorInt
import kotlin.random.Random

/**
 * Mini-vúmetro estéreo: dos barras horizontales segmentadas (L arriba, R abajo) que se degradan
 * hacia el color de la cabina.
 *
 * Es una simulación visual: leer el nivel real de la salida requiere el Visualizer, que solo se
 * enciende en el screensaver. Aquí solo se anima mientras suena música y la pantalla está activa.
 */
class VuMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val segPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    @ColorInt private var accent = 0xFF00E5FF.toInt()
    private val level = FloatArray(2)
    private val peak = IntArray(2) { -1 }
    private val peakHold = IntArray(2)
    private var energy = 0.6f

    private var playing = false
    private var running = false

    fun setAccent(@ColorInt c: Int) {
        accent = c
        invalidate()
    }

    fun setPlaying(on: Boolean) {
        playing = on
        update()
    }

    fun setRunning(on: Boolean) {
        running = on
        update()
    }

    private val frame = object : Runnable {
        override fun run() {
            step()
            invalidate()
            if (playing && running) postDelayed(this, FRAME_MS)
        }
    }

    private fun update() {
        removeCallbacks(frame)
        if (playing && running) {
            post(frame)
        } else {
            level.fill(0f)
            peak.fill(-1)
            invalidate()
        }
    }

    /** Caminata aleatoria con "golpes" de bajo para que se vea como música y no como ruido. */
    private fun step() {
        energy = (energy + (Random.nextFloat() - 0.5f) * 0.12f).coerceIn(0.35f, 0.85f)
        val kick = if (Random.nextFloat() < 0.18f) 0.3f else 0f
        for (c in 0..1) {
            val target = (energy + kick + (Random.nextFloat() - 0.5f) * 0.25f).coerceIn(0f, 1f)
            level[c] += (target - level[c]) * if (target > level[c]) 0.7f else 0.25f
            val seg = (level[c] * SEGMENTS).toInt().coerceAtMost(SEGMENTS - 1)
            if (seg >= peak[c]) {
                peak[c] = seg
                peakHold[c] = 8
            } else if (--peakHold[c] <= 0) {
                peak[c] = (peak[c] - 1).coerceAtLeast(-1)
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(frame)
    }

    override fun onDraw(canvas: Canvas) {
        val gap = 2 * density
        val rowGap = 5 * density
        val rowH = (height - rowGap) / 2f
        val segW = (width - gap * (SEGMENTS - 1)) / SEGMENTS
        for (ch in 0..1) {
            val top = ch * (rowH + rowGap)
            val lit = (level[ch] * SEGMENTS).toInt()
            for (i in 0 until SEGMENTS) {
                val left = i * (segW + gap)
                rect.set(left, top, left + segW, top + rowH)
                // Degradado hacia el color de cabina: más tenue al inicio, intenso al final.
                val on = i < lit || i == peak[ch]
                val alpha = if (on) 140 + 115 * i / (SEGMENTS - 1) else 40
                segPaint.color = ThemeManager.withAlpha(if (on) accent else 0xFF5A6570.toInt(), alpha)
                canvas.drawRect(rect, segPaint)
            }
        }
    }

    private companion object {
        const val SEGMENTS = 16
        const val FRAME_MS = 66L
    }
}
