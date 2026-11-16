package com.iheii.veloradrive

import android.content.Context
import android.media.audiofx.BassBoost

/**
 * Refuerzo de bajos global (BassBoost sobre la mezcla de salida, sesión 0), 0-1000.
 * Muchos estéreos lo aplican a todo el audio; algunos firmwares lo bloquean, y en ese caso
 * [isSupported] es false y el slider se desactiva. El nivel se guarda y se reaplica al volver.
 */
object BassController {
    private const val PREFS = "audio"
    private const val KEY = "pref_bass_level"

    private var effect: BassBoost? = null

    val isSupported get() = effect != null

    fun init(context: Context) {
        if (effect != null) return
        effect = runCatching {
            BassBoost(0, 0).takeIf { it.strengthSupported }?.apply { enabled = true }
        }.getOrNull()
        effect?.let { applyStrength(it, level(context)) }
    }

    fun level(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, 0)

    fun setLevel(context: Context, level: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, level).apply()
        effect?.let { applyStrength(it, level) }
    }

    private fun applyStrength(e: BassBoost, level: Int) {
        runCatching { e.setStrength(level.coerceIn(0, 1000).toShort()) }
    }
}
