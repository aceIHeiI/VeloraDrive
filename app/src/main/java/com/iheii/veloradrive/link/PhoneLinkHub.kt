package com.iheii.veloradrive.link

import android.content.Context
import com.iheii.veloradrive.VeloraClock
import com.iheii.veloradrive.video.VideoPlayerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/**
 * Una sola conexión con OfflineTune para todo el launcher: la pantalla principal y el reproductor
 * mini del modo dividido la comparten. Vive mientras alguien la use ([acquire]/[release]); sin
 * nadie a la vista se cierra, para no gastar batería ni red. El cierre espera unos segundos: al
 * pasar de una pantalla de Velora a otra (inicio → videos) la conexión no se corta.
 */
object PhoneLinkHub {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var link: PhoneLink? = null
    private var users = 0
    private var pendingStop: Job? = null

    fun ui(context: Context): StateFlow<LinkUiState> = get(context).ui

    /** "Ver en el coche" que manda el celular. */
    fun videoPlays(context: Context): SharedFlow<CarVideoPlay> = get(context).videoPlay

    private fun get(context: Context): PhoneLink = link ?: PhoneLink(context.applicationContext, scope).also { l ->
        link = l
        val app = context.applicationContext
        scope.launch {
            LinkPrefs.flow(app).collect { config ->
                l.setConfig(config)
                WifiJoin.suggest(app, config)
            }
        }
        // Hora y zona del celular para el reloj de Velora (con o sin pantalla a la vista).
        scope.launch {
            l.ui.collect { s ->
                val st = s.state ?: return@collect
                if (s.connected) VeloraClock.onPhoneState(app, st.phoneTimeMs, st.phoneTz, s.stateAt)
            }
        }
        // El celular pide ver un video en el coche: se abre el reproductor (Velora está a la vista,
        // si no, la conexión ni siquiera estaría abierta).
        scope.launch {
            l.videoPlay.collect { vp -> VideoPlayerActivity.openFromPhone(app, vp) }
        }
    }

    fun acquire(context: Context) {
        users++
        pendingStop?.cancel()
        pendingStop = null
        val l = get(context)
        l.start()
        AudioWatchdog.start(context, scope, l.ui) { action, value -> l.send(action, value) }
    }

    fun release(context: Context) {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) {
            pendingStop?.cancel()
            pendingStop = scope.launch {
                delay(STOP_DELAY_MS)
                if (users == 0) {
                    get(context).stop()
                    AudioWatchdog.stop()
                }
                pendingStop = null
            }
        }
    }

    fun setBassLocal(context: Context, on: Boolean) = get(context).setBassLocal(on)

    fun send(context: Context, action: String, value: JsonPrimitive? = null, valueSec: Long? = null) =
        get(context).send(action, value, valueSec)

    private const val STOP_DELAY_MS = 3_000L
}
