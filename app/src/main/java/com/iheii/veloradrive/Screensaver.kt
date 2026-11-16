package com.iheii.veloradrive

import android.view.View
import com.iheii.veloradrive.databinding.ScreensaverBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Controla el screensaver de reposo: fade sobre la cabina, reloj gigante y fuego que reacciona
 * al bajo. El Visualizer y la animación solo existen mientras está visible.
 */
class Screensaver(private val binding: ScreensaverBinding, private val canListen: () -> Boolean) {

    private val beats = AudioBeatDetector { strength -> binding.flame.onBeat(strength) }
    private val spanish = Locale.forLanguageTag("es")
    private val dateFormat = SimpleDateFormat("EEEE, d 'de' MMMM, yyyy", spanish)

    var isShowing = false
        private set

    init {
        // Cualquier toque cierra el screensaver al instante.
        binding.root.setOnClickListener { hide() }
    }

    fun show(is24h: Boolean, fireColors: Pair<Int, Int>) {
        if (isShowing) return
        isShowing = true
        binding.flame.setFireColors(fireColors.first, fireColors.second)
        val glow = fireColors.first
        binding.ssClock.setShadowLayer(14f, 0f, 0f, glow)
        binding.ssAmPm.setShadowLayer(10f, 0f, 0f, glow)
        binding.ssDate.setShadowLayer(10f, 0f, 0f, ThemeManager.withAlpha(glow, 0xAA))
        binding.ssGps.imageTintList = android.content.res.ColorStateList.valueOf(fireColors.second)
        updateClock(is24h)
        binding.root.visibility = View.VISIBLE
        binding.root.animate().alpha(1f).setDuration(450).start()
        binding.flame.setRunning(true)
        listen()
    }

    /** Toma el Visualizer; si el estéreo aún no lo suelta, reintenta cada 2 s mientras se vea. */
    private val retry = Runnable { listen() }

    private fun listen() {
        binding.root.removeCallbacks(retry)
        if (!isShowing) return
        val listening = canListen() && beats.start()
        binding.flame.setReactive(listening)
        if (!listening) binding.root.postDelayed(retry, RETRY_MS)
    }

    fun hide() {
        if (!isShowing) return
        isShowing = false
        binding.root.removeCallbacks(retry)
        beats.stop()
        binding.flame.setRunning(false)
        binding.root.animate().alpha(0f).setDuration(200)
            .withEndAction { if (!isShowing) binding.root.visibility = View.GONE }
            .start()
    }

    fun updateClock(is24h: Boolean) {
        // Hora y zona de VeloraClock (la del celular si está activada).
        val now = Date(VeloraClock.now())
        val zone = VeloraClock.zone()
        dateFormat.timeZone = zone
        binding.ssClock.text = SimpleDateFormat(if (is24h) "HH:mm" else "h:mm", spanish).apply { timeZone = zone }.format(now)
        binding.ssAmPm.text = if (is24h) "" else SimpleDateFormat("a", Locale.US).apply { timeZone = zone }.format(now)
        binding.ssDate.text = dateFormat.format(now).split(" ")
            .joinToString(" ") { w -> if (w == "de") w else w.replaceFirstChar { it.titlecase(spanish) } }
    }

    fun setStatus(gpsFix: Boolean, parked: Boolean, wifi: Boolean, bt: Boolean) {
        binding.ssGps.alpha = if (gpsFix) 1f else 0.35f
        val p = if (parked) View.VISIBLE else View.INVISIBLE
        binding.ssParked.visibility = p
        binding.ssParkedCar.visibility = p
        binding.ssWifi.alpha = if (wifi) 1f else 0.3f
        binding.ssBt.alpha = if (bt) 1f else 0.3f
    }

    private companion object {
        const val RETRY_MS = 2_000L
    }
}
