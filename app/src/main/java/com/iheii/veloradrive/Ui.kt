package com.iheii.veloradrive

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.util.DisplayMetrics
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding

/** Oculta las barras del sistema; se muestran deslizando desde el borde. */
fun Activity.goImmersive() {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowInsetsControllerCompat(window, window.decorView).apply {
        hide(WindowInsetsCompat.Type.systemBars())
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}

/**
 * Pulsación sostenida de duración fija (1 s por defecto, más larga que el long press del sistema
 * para que un toque con baches no reasigne un botón por accidente). Si se dispara, el clic se anula.
 */
@SuppressLint("ClickableViewAccessibility")
fun View.setOnHoldListener(holdMs: Long = 1_000L, onHold: () -> Unit) {
    var fired = false
    val trigger = Runnable {
        fired = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onHold()
    }
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fired = false
                v.postDelayed(trigger, holdMs)
            }
            MotionEvent.ACTION_MOVE ->
                if (e.x < 0 || e.y < 0 || e.x > v.width || e.y > v.height) v.removeCallbacks(trigger)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.removeCallbacks(trigger)
                if (fired) {
                    v.isPressed = false
                    return@setOnTouchListener true
                }
            }
        }
        false
    }
}

/** Tamaño de diseño del cockpit (dp). Toda pantalla horizontal se escala para medir esto. */
private const val DESIGN_W = 1024f
private const val DESIGN_H = 600f

/**
 * Contexto con la densidad ajustada para que la pantalla real mida 1024x600 dp, sea cual sea
 * su resolución o los DPI que reporte el estéreo. Así el diseño se ve igual en 1024x600 a 240 dpi,
 * 1280x720, 1920x720, etc. Las letras no siguen la escala de fuente del sistema para no encimarse.
 */
fun Context.designScaled(): Context {
    val dm = DisplayMetrics()
    @Suppress("DEPRECATION")
    (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
    val w = maxOf(dm.widthPixels, dm.heightPixels)
    val h = minOf(dm.widthPixels, dm.heightPixels)
    if (w <= 0 || h <= 0) return this
    val scale = minOf(w / DESIGN_W, h / DESIGN_H)
    val cfg = Configuration(resources.configuration).apply {
        densityDpi = (scale * DisplayMetrics.DENSITY_DEFAULT).toInt().coerceAtLeast(80)
        fontScale = 1f
    }
    return createConfigurationContext(cfg)
}
