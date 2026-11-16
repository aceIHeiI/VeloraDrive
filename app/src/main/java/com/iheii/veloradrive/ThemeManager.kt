package com.iheii.veloradrive

import android.content.Context
import androidx.annotation.ColorInt
import androidx.annotation.StringRes

/**
 * Colores de la cabina y del fuego del screensaver, guardados por separado en SharedPreferences.
 * Ambos se aplican en caliente, sin reiniciar la actividad.
 */
object ThemeManager {

    class Accent(@param:ColorInt val color: Int, @param:StringRes val name: Int)

    /** Paleta del fuego: del tono del borde exterior (start) al núcleo brillante (end). */
    class Fire(val id: String, @param:ColorInt val start: Int, @param:ColorInt val end: Int, @param:StringRes val name: Int)

    val presets = listOf(
        Accent(0xFF00E5FF.toInt(), R.string.theme_cyan),
        Accent(0xFFFF1744.toInt(), R.string.theme_red),
        Accent(0xFFFF6D00.toInt(), R.string.theme_orange),
        Accent(0xFF00E676.toInt(), R.string.theme_lime),
        Accent(0xFFD500F9.toInt(), R.string.theme_purple),
        Accent(0xFFFFAB00.toInt(), R.string.theme_amber),
    )

    const val FIRE_SYNC = "sync"

    val fires = listOf(
        Fire("lava", 0xFFFF3D00.toInt(), 0xFFFF9100.toInt(), R.string.fire_lava),
        Fire("plasma", 0xFF00B0FF.toInt(), 0xFF00E5FF.toInt(), R.string.fire_plasma),
        Fire("emerald", 0xFF00E676.toInt(), 0xFF1DE9B6.toInt(), R.string.fire_emerald),
        Fire("cyber", 0xFFAA00FF.toInt(), 0xFFD500F9.toInt(), R.string.fire_cyber),
        Fire("blood", 0xFFD50000.toInt(), 0xFFFF1744.toInt(), R.string.fire_blood),
        Fire("gold", 0xFFFFAB00.toInt(), 0xFFFFD600.toInt(), R.string.fire_gold),
    )

    private const val PREFS = "theme"
    private const val KEY_ACCENT = "pref_cockpit_accent_color"
    private const val KEY_ACCENT_OLD = "pref_accent_color"
    private const val KEY_FIRE = "pref_screensaver_fire_color"

    @ColorInt
    fun accent(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Respeta el color elegido en versiones anteriores.
        return prefs.getInt(KEY_ACCENT, prefs.getInt(KEY_ACCENT_OLD, presets.first().color))
    }

    fun setAccent(context: Context, @ColorInt color: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_ACCENT, color).apply()
    }

    /** Id del fuego elegido, o [FIRE_SYNC] para heredar el color de la cabina. */
    fun fireId(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_FIRE, fires.first().id)!!

    fun setFireId(context: Context, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_FIRE, id).apply()
    }

    /** Colores efectivos del fuego (start, end) según la preferencia. */
    fun fireColors(context: Context): Pair<Int, Int> {
        val id = fireId(context)
        if (id == FIRE_SYNC) {
            val a = accent(context)
            return darken(a) to a
        }
        val f = fires.firstOrNull { it.id == id } ?: fires.first()
        return f.start to f.end
    }

    /** El mismo color con otra opacidad (0-255), para halos y rellenos suaves. */
    @ColorInt
    fun withAlpha(@ColorInt color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    @ColorInt
    fun darken(@ColorInt color: Int, factor: Float = 0.7f): Int {
        val r = ((color shr 16 and 0xFF) * factor).toInt()
        val g = ((color shr 8 and 0xFF) * factor).toInt()
        val b = ((color and 0xFF) * factor).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    @ColorInt
    fun lighten(@ColorInt color: Int, amount: Float = 0.5f): Int {
        fun ch(c: Int) = (c + (255 - c) * amount).toInt()
        return (0xFF shl 24) or (ch(color shr 16 and 0xFF) shl 16) or (ch(color shr 8 and 0xFF) shl 8) or ch(color and 0xFF)
    }
}
