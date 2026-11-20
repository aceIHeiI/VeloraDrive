@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.iheii.veloradrive.video

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Iguala el volumen del video con el de la música normalizada. Mide el nivel (RMS) en bloques de 20 ms,
 * lo suaviza durante unos segundos y mueve la ganancia despacio hacia [targetDb], con tope de bajada y de
 * subida y un limitador suave para no distorsionar. Los silencios no cuentan, así no sube el ruido entre
 * canciones. Solo trabaja con PCM de 16 bits (mono o estéreo); con otro formato no hace nada.
 * Es el mismo archivo en OfflineTune y en Velora Drive.
 */
class VideoLoudness(private val targetDb: Float = TARGET_RMS_DB) : BaseAudioProcessor() {

    private var blockSamples = 0
    private var sumSq = 0.0
    private var count = 0
    private var blocks = 0
    private var levelDb = Float.NaN
    private var gainDb = 0f
    private var gain = 1f

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount !in 1..2) {
            return AudioFormat.NOT_SET // sin procesar
        }
        blockSamples = (inputAudioFormat.sampleRate * BLOCK_SECONDS).toInt() * inputAudioFormat.channelCount
        return inputAudioFormat
    }

    override fun onReset() {
        sumSq = 0.0; count = 0; blocks = 0
        levelDb = Float.NaN; gainDb = 0f; gain = 1f
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        while (inputBuffer.remaining() >= 2) {
            val s = inputBuffer.short.toInt()
            sumSq += s.toDouble() * s
            count++
            if (count >= blockSamples) endBlock()
            val y = softLimit(s / 32768f * gain)
            out.putShort((y * 32767f).roundToInt().coerceIn(-32768, 32767).toShort())
        }
        out.flip()
    }

    private fun endBlock() {
        val rms = sqrt(sumSq / count) / 32768.0
        val db = if (rms > 1e-9) (20 * log10(rms)).toFloat() else -120f
        sumSq = 0.0; count = 0
        if (db > SILENCE_DB) {
            blocks++
            // Al empezar converge rápido; después, con constante de tiempo de unos 2 s.
            val alpha = if (levelDb.isNaN()) 1f else maxOf(1f / blocks, SLOW_ALPHA)
            levelDb = if (levelDb.isNaN()) db else levelDb + (db - levelDb) * alpha
        }
        val desired = if (levelDb.isNaN()) 0f else (targetDb - levelDb).coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        val step = if (desired < gainDb) STEP_DOWN_DB else STEP_UP_DB
        gainDb += (desired - gainDb).coerceIn(-step, step)
        gain = 10f.pow(gainDb / 20f)
    }

    /** Pasa tal cual hasta 0,89 y de ahí comprime suave hacia 1,0: sin recortes duros al subir el volumen. */
    private fun softLimit(x: Float): Float {
        val a = abs(x)
        if (a <= KNEE) return x
        val y = KNEE + (1f - KNEE) * tanh((a - KNEE) / (1f - KNEE))
        return if (x < 0f) -y else y
    }

    companion object {
        /** Nivel objetivo (RMS, dBFS): equivale a unos −11 LUFS, el objetivo de la música normalizada. */
        const val TARGET_RMS_DB = -12f
        const val MIN_GAIN_DB = -12f
        const val MAX_GAIN_DB = 6f
        private const val BLOCK_SECONDS = 0.02
        private const val SILENCE_DB = -50f
        private const val SLOW_ALPHA = 0.01f
        private const val STEP_UP_DB = 0.08f   // ~4 dB por segundo
        private const val STEP_DOWN_DB = 0.24f // ~12 dB por segundo
        private const val KNEE = 0.89f
    }
}
