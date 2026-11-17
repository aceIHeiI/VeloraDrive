package com.iheii.veloradrive.link

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.SystemClock
import com.iheii.veloradrive.CockpitPrefs
import com.iheii.veloradrive.MainActivity
import com.iheii.veloradrive.SlotStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/**
 * Con Android Auto, algunos estéreos (GEARELEC entre ellos) no abren el sonido si la música empezó
 * desde el celular y no desde Android Auto: el celular manda el audio, pero no sale por las bocinas.
 * Este vigilante revisa cada segundo si de verdad suena algo en el estéreo mientras OfflineTune dice
 * que reproduce. Tras [SILENCE_MS] de silencio:
 *  1. pide a OfflineTune que vuelva a pedir el foco de audio ("audio_kick" 1),
 *  2. lo mismo con una pausa corta ("audio_kick" 2),
 *  3. abre un momento la app de Android Auto del estéreo y regresa.
 * Luego se detiene hasta que se pause y se vuelva a dar play, para no molestar si el estéreo no
 * reporta bien su sonido.
 */
object AudioWatchdog {

    enum class Sound { UNKNOWN, YES, NO }

    private const val TICK_MS = 1_000L
    private const val SILENCE_MS = 3_000L
    private const val BETWEEN_STEPS_MS = 4_000L
    private const val RETURN_MS = 2_500L
    private const val OPEN_AUTO_COOLDOWN_MS = 120_000L
    /** Una pausa más corta que esto es la del propio "audio_kick" 2, no del usuario. */
    private const val USER_PAUSE_MS = 2_000L

    private val _sound = MutableStateFlow(Sound.UNKNOWN)
    /** Lo que ve el estéreo: si está saliendo sonido de música o no. */
    val sound: StateFlow<Sound> = _sound

    private val _lastAction = MutableStateFlow<String?>(null)
    /** Último intento de reparación, para la pantalla de depuración. */
    val lastAction: StateFlow<String?> = _lastAction

    /** true mientras el reproductor de video de Velora está sonando (su audio viaja por WiFi, no por Bluetooth). */
    @Volatile var videoPlaying = false

    /**
     * El audio de la música llega al estéreo: Bluetooth A2DP, Android Auto, video sonando aquí o sonido
     * real detectado en el estéreo. El aviso "Conecta el Bluetooth..." solo sale si ninguno se cumple.
     */
    fun audioReaches(st: CarState): Boolean =
        st.btAudio || st.androidAuto || videoPlaying || _sound.value == Sound.YES

    /** La pantalla principal de Velora está al frente (para saber si hay que regresar a ella). */
    @Volatile var veloraVisible = false

    private var job: Job? = null
    private var silentSince = 0L
    private var lastStepAt = 0L
    private var steps = 0
    private var wasPlaying = false
    private var pausedSince = 0L
    private var openedAutoAt = 0L

    fun start(
        context: Context,
        scope: CoroutineScope,
        ui: StateFlow<LinkUiState>,
        send: (String, JsonPrimitive?) -> Unit,
    ) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        job = scope.launch {
            while (isActive) {
                tick(app, audio, ui.value, send)
                delay(TICK_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _sound.value = Sound.UNKNOWN
        reset(clearSteps = true)
    }

    private fun tick(app: Context, audio: AudioManager, s: LinkUiState, send: (String, JsonPrimitive?) -> Unit) {
        val st = s.state
        val musicActive = runCatching { audio.isMusicActive }.getOrDefault(false)
        _sound.value = when {
            !s.connected -> Sound.UNKNOWN
            musicActive -> Sound.YES
            else -> Sound.NO
        }
        val playing = s.connected && st?.isPlaying == true
        val now = SystemClock.elapsedRealtime()
        // Pausa del usuario → play (o reconexión): una nueva oportunidad de reparar.
        if (playing && !wasPlaying && (pausedSince == 0L || now - pausedSince >= USER_PAUSE_MS)) {
            reset(clearSteps = true)
        }
        if (playing) pausedSince = 0L else if (wasPlaying || pausedSince == 0L) pausedSince = now
        wasPlaying = playing

        if (!playing || st?.androidAuto != true || !CockpitPrefs.audioFix(app)) {
            reset(clearSteps = false)
            return
        }
        if (musicActive) {
            // Suena: si después se vuelve a cortar, se puede reparar otra vez.
            if (steps > 0) _lastAction.value = "Sonido recuperado tras $steps intento(s)"
            reset(clearSteps = true)
            return
        }
        if (silentSince == 0L) silentSince = now
        if (now - silentSince < SILENCE_MS || now - lastStepAt < BETWEEN_STEPS_MS) return
        lastStepAt = now
        when (steps) {
            0, 1 -> {
                send("audio_kick", JsonPrimitive(steps + 1))
                _lastAction.value = "Sin sonido: aviso ${steps + 1} a OfflineTune"
            }
            2 -> {
                if (now - openedAutoAt >= OPEN_AUTO_COOLDOWN_MS && openAndroidAuto(app)) {
                    openedAutoAt = now
                    _lastAction.value = "Sin sonido: se abrió Android Auto del estéreo"
                } else {
                    _lastAction.value = "Sin sonido: no se pudo reparar solo"
                }
            }
            else -> return
        }
        steps++
    }

    private fun reset(clearSteps: Boolean) {
        silentSince = 0L
        if (clearSteps) {
            steps = 0
            lastStepAt = 0L
        }
    }

    /** Abre la app de Android Auto del estéreo y, si Velora estaba al frente, regresa a él. */
    private fun openAndroidAuto(app: Context): Boolean {
        val intent = SlotStore.androidAutoReceiver(app) ?: return false
        val returnToVelora = veloraVisible
        val opened = runCatching {
            app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (opened && returnToVelora) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                runCatching {
                    app.startActivity(Intent(app, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                }
            }, RETURN_MS)
        }
        return opened
    }
}
