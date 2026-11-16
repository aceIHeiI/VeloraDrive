package com.iheii.veloradrive

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Cuatro barritas de señal del celular (0-4 encendidas). Solo se redibuja al cambiar el nivel. */
class SignalBarsView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val on = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val off = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FFFFFF }

    var level = 0
        set(value) {
            val v = value.coerceIn(0, 4)
            if (v != field) { field = v; invalidate() }
        }

    override fun onDraw(canvas: Canvas) {
        val gap = width * 0.1f
        val bw = (width - gap * 3) / 4f
        for (i in 0 until 4) {
            val h = height * (i + 1) / 4f
            val x = i * (bw + gap)
            canvas.drawRect(x, height - h, x + bw, height.toFloat(), if (i < level) on else off)
        }
    }
}
