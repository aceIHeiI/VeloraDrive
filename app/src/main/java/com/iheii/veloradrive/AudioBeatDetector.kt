package com.iheii.veloradrive

import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.hypot

/**
 * Detecta golpes de bombo/subwoofer en la mezcla de salida (Visualizer sesión 0) mirando la
 * energía de 20-120 Hz del FFT. Requiere RECORD_AUDIO. Si el estéreo o el emulador no permiten
 * el Visualizer global, [start] devuelve false y el fuego queda en modo tenue.
 *
 * Solo debe vivir mientras se ve el screensaver: [stop] libera el Visualizer al instante.
 */
class AudioBeatDetector(private val onBeat: (strength: Float) -> Unit) {

    private var visualizer: Visualizer? = null
    private val main = Handler(Looper.getMainLooper())

    private var average = 0f
    private var lastBeatAt = 0L

    fun start(): Boolean {
        stop()
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
            // Sin permiso, sin soporte del HAL o sesión 0 bloqueada.
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
        average = 0f
    }

    private fun analyze(fft: ByteArray, samplingRateMilliHz: Int, captureSize: Int) {
        // Bin k = k * fs / N. fft[2k], fft[2k+1] = parte real e imaginaria del bin k.
        val binHz = samplingRateMilliHz / 1000f / captureSize
        val first = (BASS_LOW_HZ / binHz).toInt().coerceAtLeast(1)
        val last = (BASS_HIGH_HZ / binHz).toInt().coerceAtMost(captureSize / 2 - 1)
        var energy = 0f
        for (k in first..last) energy += hypot(fft[2 * k].toFloat(), fft[2 * k + 1].toFloat())
        energy /= (last - first + 1)

        average = if (average == 0f) energy else average * 0.92f + energy * 0.08f
        val now = SystemClock.uptimeMillis()
        if (energy > average * BEAT_RATIO && energy > MIN_ENERGY && now - lastBeatAt > MIN_GAP_MS) {
            lastBeatAt = now
            val strength = ((energy / average - BEAT_RATIO) / 1.5f).coerceIn(0f, 1f)
            main.post { onBeat(strength) }
        }
    }

    private companion object {
        const val BASS_LOW_HZ = 20f
        const val BASS_HIGH_HZ = 120f
        const val BEAT_RATIO = 1.35f
        const val MIN_ENERGY = 6f
        const val MIN_GAP_MS = 140L
    }
}
