package com.iheii.veloradrive

import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow

/**
 * Espectro de la música que suena (Visualizer sesión 0, la mezcla de salida del estéreo).
 * Entrega [BANDS] bandas logarítmicas de 40 Hz a 12 kHz, normalizadas 0..1.
 * Requiere RECORD_AUDIO; si el estéreo bloquea el Visualizer global, [start] devuelve false y las
 * "montañas de sonido" usan su simulación. Solo debe vivir mientras suena música y se ve la pantalla.
 */
class AudioSpectrum(private val onBands: (FloatArray) -> Unit) {

    private var visualizer: Visualizer? = null
    private val main = Handler(Looper.getMainLooper())
    private val bands = FloatArray(BANDS)

    val isRunning get() = visualizer != null

    fun start(): Boolean {
        if (visualizer != null) return true
        return try {
            val v = Visualizer(0)
            v.captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(v: Visualizer, waveform: ByteArray, samplingRate: Int) = Unit
                override fun onFftDataCapture(v: Visualizer, fft: ByteArray, samplingRate: Int) {
                    if (visualizer === v) analyze(fft, samplingRate, fft.size)
                }
            }, Visualizer.getMaxCaptureRate(), false, true)
            v.enabled = true
            visualizer = v
            true
        } catch (_: Throwable) {
            stop()
            false
        }
    }

    fun stop() {
        visualizer?.let {
            runCatching { it.enabled = false }
            runCatching { it.release() }
        }
        visualizer = null
    }

    private fun analyze(fft: ByteArray, samplingRateMilliHz: Int, captureSize: Int) {
        val binHz = samplingRateMilliHz / 1000f / captureSize
        val maxBin = captureSize / 2 - 1
        for (b in 0 until BANDS) {
            // Límites logarítmicos: más resolución en graves, como el oído.
            val f0 = LOW_HZ * (HIGH_HZ / LOW_HZ).pow(b / BANDS.toFloat())
            val f1 = LOW_HZ * (HIGH_HZ / LOW_HZ).pow((b + 1) / BANDS.toFloat())
            val k0 = (f0 / binHz).toInt().coerceIn(1, maxBin)
            val k1 = (f1 / binHz).toInt().coerceIn(k0, maxBin)
            var sum = 0f
            for (k in k0..k1) sum += hypot(fft[2 * k].toFloat(), fft[2 * k + 1].toFloat())
            val mag = sum / (k1 - k0 + 1)
            // dB aproximado y normalizado a 0..1.
            bands[b] = ((20 * log10(mag + 1f)) / 42f).coerceIn(0f, 1f)
        }
        val copy = bands.copyOf()
        main.post { onBands(copy) }
    }

    companion object {
        const val BANDS = 32
        private const val LOW_HZ = 40f
        private const val HIGH_HZ = 12_000f
    }
}
