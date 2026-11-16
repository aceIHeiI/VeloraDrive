package com.iheii.veloradrive

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.View
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.iheii.veloradrive.databinding.ActivityObdBinding
import com.iheii.veloradrive.databinding.ItemObdStatBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Cambia el texto solo si es distinto (evita relayouts en cada lectura). */
internal fun TextView.showText(s: CharSequence) {
    if (text.toString() != s.toString()) text = s
}

/**
 * Pantalla OBD2 completa: 4 medidores (RPM, velocidad, refrigerante, batería) y 5 tarjetas
 * (carga, acelerador, admisión, combustible y códigos de falla). Comparte la conexión de [ObdHub]
 * con la pantalla principal; solo redibuja cuando llega una lectura distinta.
 */
class ObdActivity : AppCompatActivity() {

    private lateinit var binding: ActivityObdBinding
    @ColorInt private var accent = 0
    private var reading: ObdReading? = null

    override fun attachBaseContext(base: Context) = super.attachBaseContext(base.designScaled())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityObdBinding.inflate(layoutInflater)
        setContentView(binding.root)
        goImmersive()

        binding.btnBack.setOnClickListener { finish() }
        binding.cardDtc.setOnClickListener { ObdHub.requestDtc() }

        with(binding) {
            gaugeRpm.setRange(0f, 8000f, redFrom = RED_RPM.toFloat())
            gaugeRpm.setLabel(getString(R.string.obd_gauge_rpm))
            gaugeRpm.setSub(getString(R.string.obd_red_zone, ObdFormat.int(RED_RPM)), DIM)
            gaugeSpeed.setRange(0f, 200f)
            gaugeSpeed.setLabel(getString(R.string.obd_gauge_speed))
            gaugeSpeed.setArcColor(TEAL)
            gaugeCoolant.setRange(40f, 130f, redFrom = HOT_C.toFloat())
            gaugeCoolant.setLabel(getString(R.string.obd_gauge_coolant))
            gaugeVolts.setRange(10f, 16f)
            gaugeVolts.setLabel(getString(R.string.obd_gauge_volts))
            setupStat(cardLoad, R.string.obd_load, R.string.obd_unit_pct)
            setupStat(cardThrottle, R.string.obd_throttle, R.string.obd_unit_pct)
            setupStat(cardIntake, R.string.obd_intake, R.string.obd_unit_celsius)
            setupStat(cardFuel, R.string.obd_fuel, R.string.obd_unit_pct)
            cardFuel.bar.progressTintList = ColorStateList.valueOf(TEAL)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { ObdHub.readings.collect { render(it) } }
        }
    }

    override fun onStart() {
        super.onStart()
        applyAccent(ThemeManager.accent(this))
        ObdHub.acquire(this)
        startClock()
        startGps()
    }

    override fun onStop() {
        super.onStop()
        ObdHub.release(this)
        stopClock()
        stopGps()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    private fun setupStat(card: ItemObdStatBinding, title: Int, unit: Int) {
        card.title.setText(title)
        card.unit.setText(unit)
    }

    /** Naranja del mockup = color de cabina elegido. */
    private fun applyAccent(@ColorInt c: Int) {
        if (c == accent) return
        accent = c
        val stroke = ColorStateList.valueOf(ThemeManager.withAlpha(c, 0x8C))
        with(binding) {
            btnBack.backgroundTintList = ColorStateList.valueOf(c)
            btnBack.imageTintList = ColorStateList.valueOf(c)
            listOf(boxRpm, boxSpeed, boxCoolant, boxVolts).forEach { it.foregroundTintList = stroke }
            listOf(cardLoad, cardThrottle, cardIntake, cardFuel).forEach { it.root.foregroundTintList = stroke }
            listOf(cardLoad, cardThrottle, cardIntake).forEach { it.bar.progressTintList = ColorStateList.valueOf(c) }
            gaugeRpm.setArcColor(c)
        }
        reading?.let { reading = null; render(it) }
    }

    // ---------------------------------------------------------------- Lecturas

    private fun render(r: ObdReading) {
        if (r == reading) return
        reading = r
        renderStatus(r)
        with(binding) {
            gaugeRpm.setValue(r.rpm?.toFloat(), ObdFormat.int(r.rpm))
            gaugeSpeed.setValue(r.speedKmh?.toFloat(), ObdFormat.int(r.speedKmh))

            val c = r.coolantC
            gaugeCoolant.setValue(c?.toFloat(), c?.let { "$it°" } ?: ObdFormat.NONE)
            val hot = c != null && c > HOT_C
            gaugeCoolant.setArcColor(if (hot) GaugeView.RED else GREEN)
            when {
                c == null -> gaugeCoolant.setSub("", DIM)
                hot -> gaugeCoolant.setSub(getString(R.string.obd_temp_hot), GaugeView.RED)
                else -> gaugeCoolant.setSub(getString(R.string.obd_temp_normal), GREEN)
            }

            val v = r.volts
            gaugeVolts.setValue(v, ObdFormat.volts(v))
            val bad = v != null && (v < 12.0f || v > 15.0f)
            gaugeVolts.setArcColor(if (bad) GaugeView.RED else GREEN)
            when {
                v == null -> gaugeVolts.setSub("", DIM)
                v > 15.0f -> gaugeVolts.setSub(getString(R.string.obd_batt_high), GaugeView.RED)
                v > 13.2f -> gaugeVolts.setSub(getString(R.string.obd_batt_charging), GREEN)
                v < 12.0f -> gaugeVolts.setSub(getString(R.string.obd_batt_low), GaugeView.RED)
                else -> gaugeVolts.setSub(getString(R.string.obd_batt_rest), DIM)
            }

            renderStat(cardLoad, r.loadPct, r.loadPct)
            renderStat(cardThrottle, r.throttlePct, r.throttlePct)
            renderStat(cardIntake, r.intakeC, r.intakeC) // barra: 0-100 °C
            renderStat(cardFuel, r.fuelPct, r.fuelPct)
        }
        renderDtc(r)
    }

    private fun renderStatus(r: ObdReading) {
        val (text, color) = when (r.status) {
            ObdStatus.LIVE -> R.string.obd_pill_connected to GREEN
            ObdStatus.LOW_VOLTAGE -> R.string.obd_pill_low_voltage to AMBER
            ObdStatus.DEMO -> R.string.obd_pill_demo to AMBER
            ObdStatus.WAITING -> R.string.obd_pill_waiting to DIM
            ObdStatus.OFF -> R.string.obd_pill_off to DIM
        }
        binding.statusText.showText(getString(text))
        binding.statusDot.backgroundTintList = ColorStateList.valueOf(color)
    }

    private fun renderStat(card: ItemObdStatBinding, value: Int?, barPct: Int?) {
        card.value.showText(ObdFormat.int(value))
        val p = (barPct ?: 0).coerceIn(0, 100)
        if (card.bar.progress != p) card.bar.progress = p
    }

    private fun renderDtc(r: ObdReading) {
        val codes = r.dtc
        val (state, color) = when {
            r.dtcBusy -> getString(R.string.obd_dtc_reading) to DIM
            !r.connected || codes == null -> getString(R.string.obd_dtc_unread) to DIM
            codes.isEmpty() -> getString(R.string.obd_dtc_none) to GREEN
            else -> resources.getQuantityString(R.plurals.obd_dtc_found, codes.size, codes.size) to GaugeView.RED
        }
        with(binding) {
            dtcState.showText(state)
            dtcState.setTextColor(color)
            val showCodes = !r.dtcBusy && r.connected && !codes.isNullOrEmpty()
            dtcCodes.visibility = if (showCodes) View.VISIBLE else View.GONE
            if (showCodes) dtcCodes.showText(codes!!.joinToString("  "))
            dtcIcon.setImageResource(if (showCodes) R.drawable.ic_obd_warning else R.drawable.ic_obd_check)
            dtcIcon.imageTintList = ColorStateList.valueOf(color)
            cardDtc.foregroundTintList = ColorStateList.valueOf(ThemeManager.withAlpha(color, 0xB3))
        }
    }

    // ---------------------------------------------------------------- Reloj

    /** El sistema avisa una vez por minuto; no hay temporizadores propios. */
    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = renderClock()
    }

    private fun renderClock() {
        val now = Date()
        val is24 = DateFormat.is24HourFormat(this)
        binding.clock.text = SimpleDateFormat(if (is24) "HH:mm" else "h:mm", Locale.US).format(now)
        binding.clockAmPm.text = if (is24) "" else SimpleDateFormat("a", Locale.US).format(now)
        binding.clockAmPm.visibility = if (is24) View.GONE else View.VISIBLE
    }

    private fun startClock() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(this, timeReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        renderClock()
    }

    private fun stopClock() {
        runCatching { unregisterReceiver(timeReceiver) }
    }

    // ---------------------------------------------------------------- Velocidad GPS (comparación)

    private val handler = Handler(Looper.getMainLooper())
    private var locationManager: LocationManager? = null
    // Objeto completo (no lambda): en API < 30 los otros métodos no tienen implementación por defecto.
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            handler.removeCallbacks(gpsTimeout)
            handler.postDelayed(gpsTimeout, 4_000)
            if (location.hasSpeed()) setGpsKmh((location.speed * 3.6f).roundToInt().let { if (it < 2) 0 else it })
        }
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = setGpsKmh(null)
        @Deprecated("Requerido en API < 29")
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    private val gpsTimeout = Runnable { setGpsKmh(null) }

    @SuppressLint("MissingPermission")
    private fun startGps() {
        if (locationManager != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        if (!lm.allProviders.contains(LocationManager.GPS_PROVIDER)) return
        locationManager = lm
        runCatching {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, locationListener, Looper.getMainLooper())
        }
    }

    private fun stopGps() {
        locationManager?.removeUpdates(locationListener)
        locationManager = null
        handler.removeCallbacksAndMessages(null)
        setGpsKmh(null)
    }

    private fun setGpsKmh(kmh: Int?) {
        binding.gaugeSpeed.setSub(kmh?.let { getString(R.string.obd_gps_speed, it) } ?: "", DIM)
    }

    private companion object {
        const val RED_RPM = 6500
        const val HOT_C = 105
        val GREEN = 0xFF3DDC84.toInt()
        val TEAL = 0xFF2EC4B6.toInt()
        val AMBER = 0xFFFFAB00.toInt()
        val DIM = 0xFF8A96A3.toInt()
    }
}
