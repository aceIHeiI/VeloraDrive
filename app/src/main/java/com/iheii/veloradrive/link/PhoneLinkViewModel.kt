package com.iheii.veloradrive.link

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonPrimitive

/** La pantalla del coche observa [ui] (StateFlow) y manda órdenes por aquí. */
class PhoneLinkViewModel(private val app: Application) : AndroidViewModel(app) {

    val ui: StateFlow<LinkUiState> = PhoneLinkHub.ui(app)
    private var started = false

    fun start() {
        if (started) return
        started = true
        PhoneLinkHub.acquire(app)
    }

    fun stop() {
        if (!started) return
        started = false
        PhoneLinkHub.release(app)
    }

    override fun onCleared() = stop()

    fun play() = PhoneLinkHub.send(app, "play")
    fun pause() = PhoneLinkHub.send(app, "pause")
    fun toggle() = PhoneLinkHub.send(app, "toggle")
    fun next() = PhoneLinkHub.send(app, "next")
    fun previous() = PhoneLinkHub.send(app, "previous")
    fun setVolume(v: Int) = PhoneLinkHub.send(app, "set_volume", JsonPrimitive(v.coerceIn(0, 100)))
    fun setPreset(p: String) = PhoneLinkHub.send(app, "set_preset", JsonPrimitive(p))
    fun setBass(on: Boolean) {
        // Al tocarlo: se manda set_bass, la pantalla cambia al instante y se guarda; el estado lo confirma.
        PhoneLinkHub.setBassLocal(app, on)
        com.iheii.veloradrive.CockpitPrefs.setBassLast(app, on)
        PhoneLinkHub.send(app, "set_bass", JsonPrimitive(on))
    }
}
