package com.iheii.veloradrive

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorInt

/**
 * Switch grande para el auto (66x36 dp): pista en el color de cabina cuando está encendido.
 * No toma el toque por sí mismo; la fila completa lo cambia con [toggle] o [setChecked].
 */
class BigSwitch @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val d = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var pos = 0f
    private var anim: ValueAnimator? = null

    @ColorInt var accent: Int = 0xFFFF6D00.toInt()
        set(value) { field = value; invalidate() }

    var isChecked = false
        private set

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setChecked(on: Boolean, animate: Boolean = false) {
        if (on == isChecked && anim == null) {
            pos = if (on) 1f else 0f
            invalidate()
            return
        }
        isChecked = on
        anim?.cancel()
        if (!animate) {
            anim = null
            pos = if (on) 1f else 0f
            invalidate()
            return
        }
        anim = ValueAnimator.ofFloat(pos, if (on) 1f else 0f).apply {
            duration = 160
            addUpdateListener { pos = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    fun toggle(): Boolean {
        setChecked(!isChecked, animate = true)
        return isChecked
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((66 * d).toInt(), (36 * d).toInt())
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f
        trackPaint.color = blend(OFF_TRACK, accent, pos)
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, trackPaint)
        val inset = 4 * d
        val tr = r - inset
        val cx = inset + tr + (w - 2 * inset - 2 * tr) * pos
        thumbPaint.color = blend(OFF_THUMB, ON_THUMB, pos)
        canvas.drawCircle(cx, r, tr, thumbPaint)
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(c: Int, s: Int) = (c shr s) and 0xFF
        fun mix(s: Int) = (ch(a, s) + (ch(b, s) - ch(a, s)) * t).toInt()
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    private companion object {
        const val OFF_TRACK = 0xFF343C46.toInt()
        const val OFF_THUMB = 0xFF8A96A3.toInt()
        const val ON_THUMB = 0xFFFFFFFF.toInt()
    }
}
