package com.iheii.veloradrive

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Una sola conexión OBD2 para todo el launcher: la pantalla principal y la pantalla OBD2 la
 * comparten. Vive mientras alguien la use ([acquire]/[release]); sin nadie a la vista se cierra
 * (con unos segundos de gracia, para que pasar de una pantalla a otra no corte el Bluetooth).
 *
 * Con [CockpitPrefs.obdDemo] encendido publica datos de demostración sin Bluetooth.
 */
object ObdHub {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val state = MutableStateFlow(ObdReading())

    /** Última lectura; StateFlow descarta repeticiones, así la UI solo redibuja con cambios. */
    val readings: StateFlow<ObdReading> = state.asStateFlow()

    private var users = 0
    private var mode: String? = null // null = nada corriendo; "off", "demo" o "bt:<dirección>"
    private var manager: ObdManager? = null
    private var demoJob: Job? = null
    private var pendingStop: Job? = null

    fun acquire(context: Context) {
        users++
        pendingStop?.cancel()
        pendingStop = null
        apply(context.applicationContext, restart = false)
    }

    fun release(@Suppress("UNUSED_PARAMETER") context: Context) {
        users = (users - 1).coerceAtLeast(0)
        if (users > 0 || pendingStop != null) return
        pendingStop = scope.launch {
            delay(STOP_GRACE_MS)
            pendingStop = null
            if (users == 0) {
                stopAll()
                mode = null
                state.value = ObdReading()
            }
        }
    }

    /**
     * Vuelve a leer Ajustes y permisos. [restart] fuerza reconectar (cambió el escáner o el switch);
     * sin él solo arranca si algo cambió (p. ej. se acaba de dar el permiso de Bluetooth).
     */
    fun refresh(context: Context, restart: Boolean = false) {
        val app = context.applicationContext
        if (users > 0) apply(app, restart) else if (desired(app) == "off") state.value = ObdReading()
    }

    /** Vuelve a leer los códigos de falla (nunca los borra). */
    fun requestDtc() {
        when {
            demoJob != null -> scope.launch {
                state.value = state.value.copy(dtcBusy = true)
                delay(DEMO_DTC_MS)
                state.value = state.value.copy(dtc = emptyList(), dtcBusy = false)
            }
            else -> manager?.requestDtc()
        }
    }

    private fun desired(c: Context): String {
        if (CockpitPrefs.obdDemo(c)) return "demo"
        if (!CockpitPrefs.obdEnabled(c)) return "off"
        val device = CockpitPrefs.obdDevice(c) ?: return "off"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(c, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) return "off"
        return "bt:$device"
    }

    private fun apply(c: Context, restart: Boolean) {
        val want = desired(c)
        if (!restart && want == mode) return
        stopAll()
        mode = want
        when {
            want == "demo" -> {
                state.value = ObdReading(status = ObdStatus.DEMO, dtc = emptyList())
                demoJob = scope.launch { runDemo() }
            }
            want.startsWith("bt:") -> {
                state.value = ObdReading(status = ObdStatus.WAITING)
                val m = manager ?: ObdManager(scope) { r -> if (manager != null && demoJob == null) state.value = r }
                manager = m
                m.start(want.removePrefix("bt:"))
            }
            else -> state.value = ObdReading()
        }
    }

    private fun stopAll() {
        manager?.stop()
        demoJob?.cancel()
        demoJob = null
    }

    /** Datos de demostración suaves: un auto que arranca en frío y pasea por la ciudad. */
    private suspend fun runDemo() {
        var rpm = 800f
        var target = 1500f
        var coolant = 45f
        var fuel = 62f
        var t = 0
        while (scope.isActive) {
            if (t % 5 == 0) target = 800f + Random.nextFloat() * 2700f
            rpm += (target - rpm) * 0.25f + (Random.nextFloat() - 0.5f) * 60f
            rpm = rpm.coerceIn(780f, 3500f)
            val f = (rpm - 800f) / 2700f
            coolant = (coolant + if (coolant < 89f) 0.8f else (Random.nextFloat() - 0.5f) * 0.6f).coerceAtMost(92f)
            fuel = (fuel - 0.002f).coerceAtLeast(5f)
            val load = (18f + f * 55f + (Random.nextFloat() - 0.5f) * 4f).coerceIn(0f, 100f)
            val prev = state.value
            state.value = ObdReading(
                status = ObdStatus.DEMO,
                rpm = (rpm / 10f).roundToInt() * 10,
                speedKmh = (f * 110f).roundToInt(),
                coolantC = coolant.roundToInt(),
                volts = ((13.8f + 0.3f + 0.3f * sin(t / 9.0).toFloat()) * 10f).roundToInt() / 10f,
                loadPct = load.roundToInt(),
                throttlePct = (load * 0.55f).roundToInt(),
                intakeC = (24f + coolant * 0.08f).roundToInt(),
                fuelPct = fuel.roundToInt(),
                dtc = prev.dtc ?: emptyList(),
                dtcBusy = prev.dtcBusy,
            )
            t++
            delay(1_000L)
        }
    }

    private const val STOP_GRACE_MS = 4_000L
    private const val DEMO_DTC_MS = 800L
}

/** Formatos compartidos por la tarjeta y la pantalla OBD2 ("--" cuando no hay dato). */
internal object ObdFormat {
    const val NONE = "--"
    fun int(v: Int?): String = v?.let { String.format(Locale.US, "%,d", it) } ?: NONE
    fun volts(v: Float?): String = v?.let { String.format(Locale.US, "%.1f", it) } ?: NONE
}
