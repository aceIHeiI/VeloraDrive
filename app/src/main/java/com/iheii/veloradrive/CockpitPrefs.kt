package com.iheii.veloradrive

import android.content.Context

/** Ajustes del cockpit en SharedPreferences. */
object CockpitPrefs {
    private const val PREFS = "cockpit"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun obdEnabled(c: Context) = prefs(c).getBoolean("pref_obd_enabled", false)
    fun setObdEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("pref_obd_enabled", on).apply()

    fun obdDevice(c: Context): String? = prefs(c).getString("pref_obd_device", null)
    fun setObdDevice(c: Context, address: String?) = prefs(c).edit().putString("pref_obd_device", address).apply()

    /** Modo NOCHE: baja el brillo de la pantalla del launcher (no toca el brillo del sistema). */
    fun nightMode(c: Context) = prefs(c).getBoolean("pref_night_mode", false)
    fun setNightMode(c: Context, on: Boolean) = prefs(c).edit().putBoolean("pref_night_mode", on).apply()

    /**
     * Marca que guardaban las versiones anteriores (antes de ProfileStore). Solo se lee para no
     * perderla al actualizar; la marca actual vive en Preferences DataStore ("car_brand").
     */
    fun legacyBrand(c: Context): String? = prefs(c).getString("pref_brand", null)
        ?.lowercase()?.replaceFirstChar { it.titlecase() }

    /** Animar las montañas de sonido aunque no suene nada (gasta algo de CPU). */
    fun idleAnimation(c: Context) = prefs(c).getBoolean("pref_idle_animation", true)
    fun setIdleAnimation(c: Context, on: Boolean) = prefs(c).edit().putBoolean("pref_idle_animation", on).apply()

    /** Abrir la música Bluetooth del estéreo al conectarse OfflineTune (para que suene). */
    fun btWake(c: Context) = prefs(c).getBoolean("pref_bt_wake", true)
    fun setBtWake(c: Context, on: Boolean) = prefs(c).edit().putBoolean("pref_bt_wake", on).apply()

    /** Con Android Auto: si OfflineTune suena pero el estéreo no, intentar abrir el sonido solo. */
    fun audioFix(c: Context) = prefs(c).getBoolean("pref_audio_fix", true)
    fun setAudioFix(c: Context, on: Boolean) = prefs(c).edit().putBoolean("pref_audio_fix", on).apply()

    /** Mostrar el reproductor mini encima de Android Auto (ZLink, TLink...) al usar CAR SYNC. */
    fun overlayOnProjection(c: Context) = prefs(c).getBoolean("pref_overlay_projection", false)
    fun setOverlayOnProjection(c: Context, on: Boolean) = prefs(c).edit().putBoolean("pref_overlay_projection", on).apply()

    // ---- Paquete 2026-11 (contratos compartidos entre pantallas) ----

    /** OBD2 de demostración: datos falsos para ver los medidores sin escáner. */
    fun obdDemo(c: Context) = prefs(c).getBoolean("pref_obd_demo", false)
    fun setObdDemo(c: Context, on: Boolean) = prefs(c).edit().putBoolean("pref_obd_demo", on).apply()

    /** Usar la hora y zona del celular (OfflineTune Car) en el reloj de Velora. */
    fun phoneTime(c: Context) = prefs(c).getBoolean("pref_phone_time", true)
    fun setPhoneTime(c: Context, on: Boolean) = prefs(c).edit().putBoolean("pref_phone_time", on).apply()

    /**
     * Ventanitas del recuadro de arriba a la derecha, en orden, separadas por coma.
     * Ids: trip, fuel, total, phone (más adelante: next, lyrics, weather...).
     */
    fun infoPages(c: Context): List<String> =
        (prefs(c).getString("pref_info_pages", null) ?: "trip,fuel,total,phone").split(',').filter { it.isNotBlank() }
    fun setInfoPages(c: Context, pages: List<String>) = prefs(c).edit().putString("pref_info_pages", pages.joinToString(",")).apply()

    /** 0 = nunca. */
    fun idleMinutes(c: Context) = prefs(c).getInt("pref_idle_minutes", 2)
    fun setIdleMinutes(c: Context, m: Int) = prefs(c).edit().putInt("pref_idle_minutes", m).apply()
}

