package com.iheii.veloradrive

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent

/**
 * "Wakeup" de audio para estéreos chinos: el MCU ignora el foco de audio si no hay un flujo de
 * medios real abriendo los decodificadores (lo que hace YouTube). Antes de abrir la app de enlace
 * (ZLink, TLink…):
 *  1. MediaPlayer con AudioAttributes USAGE_MEDIA / CONTENT_TYPE_MUSIC.
 *  2. Reproduce res/raw/silent.mp3 (1,5 s de silencio real decodificado).
 *  3. Al hacer start() inyecta KEYCODE_MEDIA_PLAY global.
 *  4. Espera [LAUNCH_DELAY_MS] para que el MCU abra el relé, lanza la app y libera el reproductor
 *     cuando ya sonó al menos [MIN_PLAY_MS].
 * Todo vive en un Handler propio: no se corta cuando el launcher pasa a segundo plano.
 */
object AudioChannelKick {

    private const val LAUNCH_DELAY_MS = 800L
    private const val MIN_PLAY_MS = 1_200L

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    private val focusListener = AudioManager.OnAudioFocusChangeListener { }

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    fun launchWithAudio(context: Context, launch: () -> Unit) {
        val app = context.applicationContext
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        handler.removeCallbacksAndMessages(null)
        release(audio)

        requestFocus(audio)

        // 1-2. Flujo multimedia real con atributos de música
        val started = runCatching {
            val p = MediaPlayer()
            p.setAudioAttributes(attributes)
            app.resources.openRawResourceFd(R.raw.silent).use { fd ->
                p.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            }
            p.setVolume(1f, 1f)
            p.prepare() // archivo local de 12 KB: es instantáneo
            p.start()
            player = p
        }.isSuccess

        // 3. Estado global de reproducción
        if (started) sendMediaPlay(audio)

        // 4. Dar tiempo al MCU, lanzar la app de enlace y liberar
        handler.postDelayed({
            launch()
            handler.postDelayed({ release(audio) }, MIN_PLAY_MS - LAUNCH_DELAY_MS)
        }, if (started) LAUNCH_DELAY_MS else 0L)
    }

    private fun sendMediaPlay(audio: AudioManager) {
        runCatching {
            val now = SystemClock.uptimeMillis()
            audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0))
            audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0))
        }
    }

    private fun requestFocus(audio: AudioManager) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener(focusListener, handler)
                    .build()
                focusRequest = request
                audio.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            }
        }
    }

    private fun release(audio: AudioManager) {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { audio.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audio.abandonAudioFocus(focusListener)
            }
        }
        focusRequest = null
    }
}
