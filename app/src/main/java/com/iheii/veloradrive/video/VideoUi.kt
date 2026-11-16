package com.iheii.veloradrive.video

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.format.DateFormat
import android.text.style.RelativeSizeSpan
import androidx.annotation.ColorInt
import java.text.SimpleDateFormat
import java.util.Locale

/** Piezas de dibujo y formato compartidas por la biblioteca y el reproductor. */
internal object VideoUi {

    /** Fondo con borde (y relleno opcional) y ripple, pintado en código con el color de cabina. */
    fun outline(
        context: Context,
        @ColorInt stroke: Int,
        @ColorInt fill: Int,
        radiusDp: Float,
        oval: Boolean = false,
        strokeDp: Float = 1.5f,
    ): Drawable {
        val d = context.resources.displayMetrics.density
        val shape = GradientDrawable().apply {
            shape = if (oval) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
            if (!oval) cornerRadius = radiusDp * d
            setColor(fill)
            setStroke(maxOf(1, (strokeDp * d).toInt()), stroke)
        }
        return RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape, null)
    }

    /** 3:52, 1:02:05 */
    fun duration(sec: Long): String {
        val s = sec.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val ss = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, ss)
        else String.format(Locale.US, "%d:%02d", m, ss)
    }

    /** Hora de VeloraClock ("7:12 PM" con el PM chico, o "19:12"). */
    fun clock(context: Context): CharSequence {
        val is24 = DateFormat.is24HourFormat(context)
        val now = java.util.Date(com.iheii.veloradrive.VeloraClock.now())
        val zone = com.iheii.veloradrive.VeloraClock.zone()
        val time = SimpleDateFormat(if (is24) "H:mm" else "h:mm", Locale.US).apply { timeZone = zone }.format(now)
        if (is24) return time
        val amPm = SimpleDateFormat("a", Locale.US).apply { timeZone = zone }.format(now).uppercase(Locale.US)
        val full = "$time $amPm"
        return SpannableString(full).apply {
            setSpan(RelativeSizeSpan(0.55f), time.length + 1, full.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** Milisegundos hasta el siguiente cambio de minuto de VeloraClock. */
    fun msToNextMinute(): Long = 60_000L - (com.iheii.veloradrive.VeloraClock.now() % 60_000L) + 50L
}
