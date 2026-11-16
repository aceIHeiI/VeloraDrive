package com.iheii.veloradrive

import android.content.Context
import android.media.audiofx.Equalizer
import androidx.annotation.StringRes

/**
 * Aplica presets al ecualizador global del sistema (Equalizer sobre la sesión 0), usando los que
 * trae el propio Android (Normal, Pop, Rock, Hip Hop...). Los ids son los mismos que manda Velora a
 * OfflineTune con set_preset. Bass Boost va aparte ([setBass]) y se suma a cualquier preset.
 * Si el estéreo no expone el ecualizador global, [isSupported] es false.
 */
object EqPresets {

    /** Preset del selector: id del protocolo, nombre en pantalla y nombres de Android que le sirven. */
    class Preset(val id: String, @param:StringRes val label: Int, val androidNames: List<String>)

    val all = listOf(
        Preset("normal", R.string.eq_normal, listOf("normal")),
        Preset("pop", R.string.eq_pop, listOf("pop")),
        Preset("rock", R.string.eq_rock, listOf("rock")),
        Preset("hiphop", R.string.eq_hiphop, listOf("hiphop")),
        Preset("dance", R.string.eq_dance, listOf("dance")),
        Preset("jazz", R.string.eq_jazz, listOf("jazz")),
        Preset("classical", R.string.eq_classical, listOf("classical", "classic")),
        Preset("heavymetal", R.string.eq_heavymetal, listOf("heavymetal", "metal")),
        Preset("folk", R.string.eq_folk, listOf("folk")),
        Preset("flat", R.string.eq_flat, listOf("flat", "normal")),
    )

    fun byId(id: String?): Preset? = id?.let { k -> all.firstOrNull { it.id == normalize(k) } }

    private const val PREFS = "audio"
    private const val KEY = "pref_eq_preset"
    private const val KEY_BASS = "pref_bass_on"
    private const val BASS_LEVEL = 800
    /** Lo que guardaban versiones anteriores (nombres del enum ROCK, POP, FLAT, BASS_BOOST). */
    private const val LEGACY_BASS = "BASS_BOOST"

    private var eq: Equalizer? = null

    val isSupported get() = eq != null

    fun init(context: Context) {
        if (eq != null) return
        eq = runCatching { Equalizer(0, 0).apply { enabled = true } }.getOrNull()
        // Versiones anteriores guardaban "BASS BOOST" como preset: pasa a PLANO + Bass encendido.
        if (prefs(context).getString(KEY, null) == LEGACY_BASS) {
            prefs(context).edit().putBoolean(KEY_BASS, true).putString(KEY, "flat").apply()
        }
        currentId(context)?.let { apply(context, it) }
    }

    /** Id del preset guardado (normal, pop, rock...), o null si nunca se eligió. */
    fun currentId(context: Context): String? = byId(prefs(context).getString(KEY, null))?.id

    fun bassOn(context: Context): Boolean = prefs(context).getBoolean(KEY_BASS, false)

    /** Enciende o apaga el refuerzo de bajos. Devuelve false si el estéreo no lo permite. */
    fun setBass(context: Context, on: Boolean): Boolean {
        if (!BassController.isSupported) return false
        BassController.setLevel(context, if (on) BASS_LEVEL else 0)
        prefs(context).edit().putBoolean(KEY_BASS, on).apply()
        return true
    }

    /** Devuelve false si el estéreo no permite aplicarlo o no trae ese preset. */
    fun apply(context: Context, id: String): Boolean {
        val e = eq ?: return false
        val preset = byId(id) ?: return false
        val names = (0 until e.numberOfPresets).map { i ->
            normalize(runCatching { e.getPresetName(i.toShort()) }.getOrNull().orEmpty())
        }
        // Primero el nombre exacto; si no, uno que lo contenga ("Heavy Metal" / "Metal").
        val index = preset.androidNames.firstNotNullOfOrNull { wanted -> names.indexOf(wanted).takeIf { it >= 0 } }
            ?: preset.androidNames.firstNotNullOfOrNull { wanted ->
                names.indexOfFirst { it.isNotEmpty() && it.contains(wanted) }.takeIf { it >= 0 }
            }
            ?: return false
        if (runCatching { e.usePreset(index.toShort()) }.isFailure) return false
        prefs(context).edit().putString(KEY, preset.id).apply()
        return true
    }

    /** Sin mayúsculas, espacios, guiones ni acentos: "Hip Hop" = "hiphop". */
    private fun normalize(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .filter { it.isLetterOrDigit() }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
