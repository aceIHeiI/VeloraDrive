package com.iheii.veloradrive

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorInt
import androidx.annotation.StringRes
import kotlin.math.min

/** Formas del emblema central. Son diseños genéricos, no logotipos oficiales. */
enum class EmblemStyle(@param:StringRes val title: Int) {
    RING_BAR(R.string.emblem_ring),
    OVAL(R.string.emblem_oval),
    SHIELD(R.string.emblem_shield),
    HEXAGON(R.string.emblem_hex),
    BOWTIE(R.string.emblem_bowtie),
}

/**
 * Emblema de marca dibujado por código: contorno cromado con halo neón y el nombre (currentBrand)
 * en una barra biselada. La forma la elige el usuario ([EmblemStyle]). Es estático: se dibuja solo
 * al cambiar marca, forma, color o tamaño.
 */
class BrandLogoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    @ColorInt private var accent = 0xFF00E5FF.toInt()

    var brand: String = ""
        set(value) {
            field = value
            fitText()
            invalidate()
        }

    var style: EmblemStyle = EmblemStyle.RING_BAR
        set(value) {
            field = value
            buildShape()
            buildShaders()
            fitText()
            invalidate()
        }

    private var cx = 0f
    private var cy = 0f
    private var r = 0f
    private val bar = RectF()
    private val barInner = RectF()
    private val shape = Path()

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val chromePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val barGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        letterSpacing = 0.12f
    }

    fun setAccent(@ColorInt c: Int) {
        accent = c
        buildShaders()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cx = w / 2f
        cy = h / 2f
        r = min(w * 0.9f / 2.4f, h / 2f * 0.82f)
        buildShape()
        buildShaders()
        fitText()
    }

    /** Contorno de cada estilo y la barra del nombre. */
    private fun buildShape() {
        if (r <= 0f) return
        shape.rewind()
        var barW = r * 1.2f
        var barH = r * 0.46f
        when (style) {
            EmblemStyle.RING_BAR -> shape.addCircle(cx, cy, r, Path.Direction.CW)
            EmblemStyle.OVAL -> {
                shape.addOval(RectF(cx - r * 1.25f, cy - r * 0.78f, cx + r * 1.25f, cy + r * 0.78f), Path.Direction.CW)
                barW = r * 1.0f
                barH = r * 0.42f
            }
            EmblemStyle.SHIELD -> {
                shape.moveTo(cx - r * 0.95f, cy - r * 0.95f)
                shape.lineTo(cx + r * 0.95f, cy - r * 0.95f)
                shape.lineTo(cx + r * 0.95f, cy + r * 0.05f)
                shape.quadTo(cx + r * 0.9f, cy + r * 0.75f, cx, cy + r * 1.08f)
                shape.quadTo(cx - r * 0.9f, cy + r * 0.75f, cx - r * 0.95f, cy + r * 0.05f)
                shape.close()
                barW = r * 1.15f
                barH = r * 0.42f
            }
            EmblemStyle.HEXAGON -> {
                for (i in 0 until 6) {
                    val a = Math.toRadians(60.0 * i)
                    val x = cx + r * 1.05f * kotlin.math.cos(a).toFloat()
                    val y = cy + r * 0.95f * kotlin.math.sin(a).toFloat()
                    if (i == 0) shape.moveTo(x, y) else shape.lineTo(x, y)
                }
                shape.close()
                barW = r * 1.25f
            }
            EmblemStyle.BOWTIE -> {
                // Moño: cuerpo central con puntas inclinadas.
                shape.moveTo(cx - r * 1.25f, cy - r * 0.18f)
                shape.lineTo(cx - r * 0.45f, cy - r * 0.18f)
                shape.lineTo(cx - r * 0.35f, cy - r * 0.55f)
                shape.lineTo(cx + r * 0.35f, cy - r * 0.55f)
                shape.lineTo(cx + r * 0.45f, cy - r * 0.18f)
                shape.lineTo(cx + r * 1.25f, cy - r * 0.18f)
                shape.lineTo(cx + r * 1.12f, cy + r * 0.18f)
                shape.lineTo(cx + r * 0.35f, cy + r * 0.18f)
                shape.lineTo(cx + r * 0.25f, cy + r * 0.55f)
                shape.lineTo(cx - r * 0.45f, cy + r * 0.55f)
                shape.lineTo(cx - r * 0.35f, cy + r * 0.18f)
                shape.lineTo(cx - r * 1.12f, cy + r * 0.18f)
                shape.close()
                barW = r * 0.95f
                barH = r * 0.34f
            }
        }
        bar.set(cx - barW, cy - barH / 2, cx + barW, cy + barH / 2)
        barInner.set(bar)
        barInner.inset(2.2f * density, 2.2f * density)
    }

    private fun fitText() {
        if (r <= 0f) return
        textPaint.textSize = bar.height() * 0.58f
        // Nombres largos: se achica hasta caber en la barra.
        val maxW = bar.width() * 0.86f
        while (textPaint.measureText(brand) > maxW && textPaint.textSize > 8f) textPaint.textSize -= 1f
    }

    private fun buildShaders() {
        if (r <= 0f) return
        val light = ThemeManager.lighten(accent, 0.85f)
        val dark = ThemeManager.darken(accent, 0.35f)
        // Contorno cromado: bandas claras y oscuras alrededor, teñidas con el color de cabina.
        chromePaint.shader = SweepGradient(
            cx, cy,
            intArrayOf(light, dark, ThemeManager.lighten(accent, 0.4f), dark, light, dark, ThemeManager.lighten(accent, 0.4f), dark, light),
            null,
        )
        chromePaint.strokeWidth = r * if (style == EmblemStyle.RING_BAR) 0.17f else 0.11f
        edgePaint.strokeWidth = 1.2f * density
        edgePaint.color = ThemeManager.withAlpha(accent, 230)
        haloPaint.strokeWidth = r * 0.36f
        haloPaint.color = ThemeManager.withAlpha(accent, 38)
        innerPaint.color = ThemeManager.withAlpha(accent, if (style == EmblemStyle.RING_BAR) 0 else 22)

        barPaint.shader = LinearGradient(
            0f, bar.top, 0f, bar.bottom,
            intArrayOf(0xFF2A3440.toInt(), 0xFF0C1016.toInt(), 0xFF1A222B.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        barEdgePaint.shader = LinearGradient(0f, bar.top, 0f, bar.bottom, intArrayOf(light, accent, dark), null, Shader.TileMode.CLAMP)
        barEdgePaint.strokeWidth = 2.2f * density
        barGlowPaint.strokeWidth = 8f * density
        barGlowPaint.color = ThemeManager.withAlpha(accent, 45)
        textPaint.color = 0xFFEFFCFF.toInt()
        textPaint.setShadowLayer(10f * density, 0f, 0f, accent)
    }

    override fun onDraw(canvas: Canvas) {
        // Forma con halo, relleno de vidrio y contorno cromado
        canvas.drawPath(shape, haloPaint)
        canvas.drawPath(shape, innerPaint)
        canvas.drawPath(shape, chromePaint)
        canvas.drawPath(shape, edgePaint)

        // Barra biselada con el nombre
        val corner = 3f * density
        canvas.drawRoundRect(bar, corner, corner, barGlowPaint)
        canvas.drawRoundRect(bar, corner, corner, barPaint)
        canvas.drawRoundRect(barInner, corner, corner, barEdgePaint)
        val baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(brand, cx, baseline, textPaint)
    }
}
