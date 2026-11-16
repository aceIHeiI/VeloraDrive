package com.iheii.veloradrive

import android.content.Context
import android.location.Location
import android.os.SystemClock
import android.provider.Settings
import java.util.TimeZone

/**
 * Hora de Velora. Muchos estéreos chinos no tienen pila de reloj ni internet y arrancan con la hora
 * mal: con "Usar la hora del celular" (CockpitPrefs.phoneTime) se toma la del celular que manda
 * OfflineTune Car; si no hay celular, la del GPS; si tampoco, la del sistema.
 *
 * No se guarda un desfase contra System.currentTimeMillis() sino un ancla con elapsedRealtime
 * (monótono): si el estéreo cambia su reloj, la hora de Velora no salta. El ancla vale hasta la
 * siguiente actualización y sobrevive a desconexiones; tras reiniciar el estéreo se descarta
 * (otro BOOT_COUNT), pero la zona horaria del celular se conserva.
 */
object VeloraClock {
    private const val PREFS = "velora_clock"
    private const val KEY_TZ = "phone_tz"
    private const val KEY_TIME = "anchor_time"
    private const val KEY_ELAPSED = "anchor_elapsed"
    private const val KEY_BOOT = "anchor_boot"

    /** Fixes de GPS con fecha anterior a esto son del error de "rollover" de chips baratos. */
    private const val MIN_VALID_TIME = 1_704_067_200_000L // 2024-01-01

    private var app: Context? = null
    private var loaded = false

    /** Ancla del celular: hora real [phoneTime] cuando elapsedRealtime valía [phoneElapsed]. */
    private var phoneTime = 0L
    private var phoneElapsed = -1L
    private var phoneTz: TimeZone? = null

    /** Ancla del GPS (respaldo sin celular). */
    private var gpsTime = 0L
    private var gpsElapsed = -1L
    private var lastSavedElapsed = 0L

    fun init(context: Context) {
        if (loaded) return
        loaded = true
        val c = context.applicationContext
        app = c
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        phoneTz = p.getString(KEY_TZ, null)?.let { TimeZone.getTimeZone(it) }
        if (p.getInt(KEY_BOOT, -1) == bootCount(c) && bootCount(c) >= 0) {
            val elapsed = p.getLong(KEY_ELAPSED, -1L)
            if (elapsed in 0..SystemClock.elapsedRealtime()) {
                phoneTime = p.getLong(KEY_TIME, 0L)
                phoneElapsed = elapsed
            }
        }
    }

    private fun enabled() = app?.let { CockpitPrefs.phoneTime(it) } ?: false

    /** Hora actual (epoch ms) para todo lo que Velora muestra. */
    fun now(): Long {
        val sys = System.currentTimeMillis()
        if (!enabled()) return sys
        val e = SystemClock.elapsedRealtime()
        return when {
            phoneElapsed >= 0 -> phoneTime + (e - phoneElapsed)
            gpsElapsed >= 0 -> gpsTime + (e - gpsElapsed)
            else -> sys
        }
    }

    /** Zona horaria: la del celular (guardada) o la del sistema. */
    fun zone(): TimeZone = (if (enabled()) phoneTz else null) ?: TimeZone.getDefault()

    /**
     * Llega un CarState: [timeMs] es la hora del celular al armarlo y [receivedElapsed] el
     * elapsedRealtime en que se recibió (LinkUiState.stateAt).
     */
    fun onPhoneState(context: Context, timeMs: Long?, tz: String?, receivedElapsed: Long) {
        init(context)
        if (!CockpitPrefs.phoneTime(context)) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val edit = p.edit()
        var dirty = false
        if (!tz.isNullOrBlank() && tz != phoneTz?.id) {
            phoneTz = TimeZone.getTimeZone(tz)
            edit.putString(KEY_TZ, tz)
            dirty = true
        }
        if (timeMs != null && timeMs > MIN_VALID_TIME && receivedElapsed > 0 &&
            !(phoneTime == timeMs && phoneElapsed == receivedElapsed)
        ) {
            phoneTime = timeMs
            phoneElapsed = receivedElapsed
            // El celular manda estado cada 15 s: se guarda como mucho cada 5 min (o la primera vez).
            if (lastSavedElapsed == 0L || receivedElapsed - lastSavedElapsed > 5 * 60_000L) {
                lastSavedElapsed = receivedElapsed
                edit.putLong(KEY_TIME, timeMs).putLong(KEY_ELAPSED, receivedElapsed)
                    .putInt(KEY_BOOT, bootCount(context))
                dirty = true
            }
        }
        if (dirty) edit.apply()
    }

    /** Respaldo: la hora UTC que trae cada fix del GPS. Solo la primera y luego cada 10 min. */
    fun onGpsFix(loc: Location) {
        val t = loc.time
        if (t < MIN_VALID_TIME) return
        val e = loc.elapsedRealtimeNanos / 1_000_000L
        if (e <= 0) return
        if (gpsElapsed >= 0 && e - gpsElapsed < 10 * 60_000L) return
        gpsTime = t
        gpsElapsed = e
    }

    private fun bootCount(context: Context): Int =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1)
}
