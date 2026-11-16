package com.iheii.veloradrive

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat

/**
 * Kilómetros y tiempo manejando, con el GPS del estéreo. Es el ÚNICO que escucha al GPS en todo
 * Velora: TripService lo mantiene vivo en segundo plano (modo lento: 2 s / 10 m) y la pantalla
 * principal, mientras se ve, pide el modo rápido (1 s) y recibe cada fix para su velocímetro.
 *
 * Solo cuentan fixes con precisión < 30 m y velocidad > 3 km/h; los saltos imposibles se ignoran.
 * Tres contadores: este viaje (en memoria; se reinicia al arrancar el proceso o tras 30 min sin
 * moverse), desde la carga de gasolina y total desde que se instaló Velora (estos dos se guardan
 * en SharedPreferences como mucho cada 30 s y al detenerse).
 *
 * Gancho OBD2: si más adelante llega la velocidad del auto por OBD2, puede usarse aquí en lugar
 * de la del GPS para decidir si el coche se mueve (la distancia seguiría saliendo del GPS).
 */
object TripTracker {

    interface Listener {
        /** Cada fix del GPS (aunque no cuente para los km), para velocímetro y estado del GPS. */
        fun onFix(loc: Location) {}
        fun onProviderDisabled() {}
        /** Cambió algún contador. */
        fun onStats() {}
    }

    class Stats(val meters: Double, val movingMs: Long)

    private const val PREFS = "trip"
    private const val MAX_ACCURACY_M = 30f
    private const val MIN_MOVING_KMH = 3f
    private const val MAX_PLAUSIBLE_KMH = 220f
    private const val MAX_GAP_MS = 120_000L
    private const val SESSION_GAP_MS = 30 * 60_000L
    private const val SAVE_EVERY_MS = 30_000L

    private var app: Context? = null
    private var lm: LocationManager? = null
    private var fastMode = false
    private var users = 0
    private var fastUsers = 0
    private val listeners = mutableSetOf<Listener>()

    // ---- contadores
    private var loaded = false
    private var tripMeters = 0.0
    private var tripMs = 0L
    private var fuelMeters = 0.0
    private var fuelMs = 0L
    private var fuelSince = 0L
    private var totalMeters = 0.0
    private var totalMs = 0L

    private var anchor: Location? = null
    private var rejects = 0
    /** elapsedRealtime del último fix en movimiento (0 = aún no se mueve en este proceso). */
    private var lastMovingAt = 0L
    private var lastSaveAt = 0L
    private var dirty = false

    val trip get() = Stats(tripMeters, tripMs)
    val fuel get() = Stats(fuelMeters, fuelMs)
    val total get() = Stats(totalMeters, totalMs)

    /** Cuándo se marcó la última carga de gasolina (epoch ms, 0 = nunca). */
    fun fuelSince(context: Context): Long {
        load(context)
        return fuelSince
    }

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun addListener(l: Listener) { listeners += l }
    fun removeListener(l: Listener) { listeners -= l }

    /** Empieza a usar el GPS. [fast] = la pantalla está a la vista y quiere fixes cada segundo. */
    fun acquire(context: Context, fast: Boolean) {
        load(context)
        users++
        if (fast) fastUsers++
        checkSession()
        updateRequest()
    }

    fun release(context: Context, fast: Boolean) {
        users = (users - 1).coerceAtLeast(0)
        if (fast) fastUsers = (fastUsers - 1).coerceAtLeast(0)
        save(force = true)
        updateRequest()
    }

    /** Botón "Cargué gasolina". */
    fun resetFuel(context: Context) {
        load(context)
        fuelMeters = 0.0
        fuelMs = 0L
        fuelSince = VeloraClock.now()
        dirty = true
        save(force = true)
        notifyStats()
    }

    /** Si el coche estuvo más de 30 min sin moverse, el "este viaje" vuelve a cero. */
    fun checkSession() {
        if (lastMovingAt != 0L && SystemClock.elapsedRealtime() - lastMovingAt > SESSION_GAP_MS) {
            tripMeters = 0.0
            tripMs = 0L
            lastMovingAt = 0L
            anchor = null
            notifyStats()
        }
    }

    private fun load(context: Context) {
        if (loaded) return
        loaded = true
        val c = context.applicationContext
        app = c
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fuelMeters = p.getLong("fuel_m", 0L).toDouble()
        fuelMs = p.getLong("fuel_ms", 0L)
        fuelSince = p.getLong("fuel_since", 0L)
        totalMeters = p.getLong("total_m", 0L).toDouble()
        totalMs = p.getLong("total_ms", 0L)
        // "Este viaje" no se guarda: proceso nuevo = coche recién encendido.
    }

    private fun save(force: Boolean) {
        val c = app ?: return
        if (!dirty) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastSaveAt < SAVE_EVERY_MS) return
        lastSaveAt = now
        dirty = false
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("fuel_m", fuelMeters.toLong())
            .putLong("fuel_ms", fuelMs)
            .putLong("fuel_since", fuelSince)
            .putLong("total_m", totalMeters.toLong())
            .putLong("total_ms", totalMs)
            .apply()
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onLocation(location)
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) {
            anchor = null
            listeners.toList().forEach { it.onProviderDisabled() }
        }
        @Deprecated("Requerido en API < 29")
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    @SuppressLint("MissingPermission")
    private fun updateRequest() {
        val c = app ?: return
        if (users == 0 || !hasPermission(c)) {
            lm?.removeUpdates(locationListener)
            lm = null
            return
        }
        val wantFast = fastUsers > 0
        if (lm != null && wantFast == fastMode) return
        val manager = c.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!manager.allProviders.contains(LocationManager.GPS_PROVIDER)) return
        fastMode = wantFast
        // Volver a pedir con el mismo listener reemplaza la petición anterior.
        runCatching {
            if (wantFast) {
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, locationListener, Looper.getMainLooper())
            } else {
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2_000L, 10f, locationListener, Looper.getMainLooper())
            }
            lm = manager
        }
    }

    private fun onLocation(loc: Location) {
        VeloraClock.onGpsFix(loc)
        listeners.toList().forEach { it.onFix(loc) }
        if (!loc.hasAccuracy() || loc.accuracy >= MAX_ACCURACY_M) return

        val prev = anchor
        if (prev == null) {
            anchor = loc
            return
        }
        val dtMs = (loc.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1_000_000L
        if (dtMs <= 0) return
        if (dtMs > MAX_GAP_MS) {
            // Hueco largo (estuvo parado o sin señal): se vuelve a anclar sin sumar.
            anchor = loc
            return
        }
        val step = prev.distanceTo(loc)
        val impliedKmh = step / (dtMs / 1000f) * 3.6f
        if (impliedKmh > MAX_PLAUSIBLE_KMH) {
            // Salto imposible: se ignora. Si se repite, el ancla era la mala.
            if (++rejects >= 3) {
                rejects = 0
                anchor = loc
            }
            return
        }
        rejects = 0
        val kmh = if (loc.hasSpeed()) loc.speed * 3.6f else impliedKmh
        anchor = loc
        if (kmh <= MIN_MOVING_KMH) return

        checkSession()
        lastMovingAt = SystemClock.elapsedRealtime()
        tripMeters += step
        fuelMeters += step
        totalMeters += step
        tripMs += dtMs
        fuelMs += dtMs
        totalMs += dtMs
        dirty = true
        save(force = false)
        notifyStats()
    }

    private fun notifyStats() = listeners.toList().forEach { it.onStats() }
}
