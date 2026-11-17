package com.iheii.veloradrive.video

import android.content.Context

/** Ajustes del video en SharedPreferences ("video"). */
object VideoPrefs {
    private const val PREFS = "video"
    private const val KEY_WARNING = "pref_video_warning_ok"
    private const val KEY_AUTO_MAX = "pref_video_auto_max"
    private const val KEY_HIDE_MOVING = "pref_video_hide_moving"
    private const val KEY_HEIGHT = "pref_video_start_height"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Ya se aceptó el aviso "No veas videos mientras conduces". */
    fun warningAccepted(c: Context) = prefs(c).getBoolean(KEY_WARNING, false)
    fun setWarningAccepted(c: Context, ok: Boolean) = prefs(c).edit().putBoolean(KEY_WARNING, ok).apply()

    /** Tope de la calidad "Automática": 720 (predeterminado) o 1080 si el estéreo lo decodifica. */
    fun autoMaxHeight(c: Context): Int = prefs(c).getInt(KEY_AUTO_MAX, 720).let { if (it >= 1080) 1080 else 720 }
    fun setAutoMaxHeight(c: Context, h: Int) = prefs(c).edit().putInt(KEY_AUTO_MAX, h).apply()

    /**
     * Calidad que se recuerda: [HEIGHT_MIN] (predeterminado: arranca en la mínima), [HEIGHT_AUTO] o la
     * altura que el usuario eligió (p. ej. 1080). Se guarda al elegirla en el menú del reproductor.
     */
    const val HEIGHT_MIN = -1
    const val HEIGHT_AUTO = 0
    fun savedHeight(c: Context): Int = prefs(c).getInt(KEY_HEIGHT, HEIGHT_MIN)
    fun setSavedHeight(c: Context, h: Int) = prefs(c).edit().putInt(KEY_HEIGHT, h).apply()

    /** Ocultar la imagen (queda el audio) si el coche va a más de 10 km/h. Apagado por defecto. */
    fun hideWhileMoving(c: Context) = prefs(c).getBoolean(KEY_HIDE_MOVING, false)
    fun setHideWhileMoving(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_HIDE_MOVING, on).apply()
}
