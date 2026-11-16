package com.iheii.veloradrive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.iheii.veloradrive.databinding.OverlayPlayerBinding
import com.iheii.veloradrive.link.LinkUiState
import com.iheii.veloradrive.link.PhoneLinkHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

/**
 * Modo dividido: Google Maps o Waze a pantalla completa y, encima, el reproductor mini de Velora
 * pegado a la derecha (35 % o 50 % del ancho). No usa Android Auto ni la pantalla dividida del
 * sistema (un launcher no puede partirse a sí mismo y muchos estéreos no la traen): es una ventana
 * flotante, que funciona en Android 8 a 14. "Mapa completo" la reduce a un botón redondo.
 */
class DriveOverlayService : Service() {

    enum class Mode(val fraction: Float) { SPLIT(0.35f), HALF(0.5f), MAP(0f) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var b: OverlayPlayerBinding
    private lateinit var audio: AudioManager
    private var mode = Mode.SPLIT
    private var remote = LinkUiState()
    private var local: MediaController? = null
    /** Todas las apps con música en el estéreo (Spotify, ZLink...), para "cambiar de fuente". */
    private var localList: List<MediaController> = emptyList()
    /** true si el usuario eligió la música local aunque OfflineTune esté conectado. */
    private var preferLocal = false
    /** El usuario está moviendo el volumen: no se le mueve la perilla por debajo. */
    private var draggingVolume = false
    private var sessions: MediaSessionManager? = null
    @androidx.annotation.ColorInt private var accent = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        accent = ThemeManager.accent(this)
        // Mismas medidas que la pantalla principal (1024x600 dp en cualquier estéreo).
        val themed = ContextThemeWrapper(designScaled(), R.style.Theme_Velora)
        b = OverlayPlayerBinding.inflate(LayoutInflater.from(themed))
        setupButtons()
        tint()
        wm.addView(b.root, params())
        PhoneLinkHub.acquire(this)
        scope.launch { PhoneLinkHub.ui(this@DriveOverlayService).collect { remote = it; render() } }
        startLocalMedia()
        render()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_MODE)?.let { name -> Mode.entries.firstOrNull { it.name == name } }?.let { setMode(it) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { wm.removeView(b.root) }
        PhoneLinkHub.release(this)
        stopLocalMedia()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ ventana

    private fun screen(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    private fun params(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                   else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val (w, _) = screen()
        val density = b.root.resources.displayMetrics.density
        val margin = (8 * density).toInt()
        return WindowManager.LayoutParams(
            if (mode == Mode.MAP) WindowManager.LayoutParams.WRAP_CONTENT else (w * mode.fraction).toInt(),
            // Alto: el que deje libre el sistema (debajo de la barra de estado del mapa), así los
            // botones de arriba nunca caen donde el sistema se queda con los toques.
            if (mode == Mode.MAP) WindowManager.LayoutParams.WRAP_CONTENT else WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            if (mode == Mode.MAP) {
                // Bolita: se arrastra con el dedo y se queda donde la dejaste.
                gravity = Gravity.TOP or Gravity.START
                val size = (PILL_DP * density).toInt()
                val (sw, sh) = screen()
                x = pillPrefs().getInt(KEY_PILL_X, sw - size - margin).coerceIn(0, (sw - size).coerceAtLeast(0))
                y = pillPrefs().getInt(KEY_PILL_Y, (sh - size) / 2).coerceIn(0, (sh - size).coerceAtLeast(0))
            } else {
                gravity = Gravity.END or Gravity.TOP
                x = margin
            }
        }
    }

    private fun setMode(m: Mode) {
        mode = m
        b.panel.visibility = if (m == Mode.MAP) View.GONE else View.VISIBLE
        b.pill.visibility = if (m == Mode.MAP) View.VISIBLE else View.GONE
        runCatching { wm.updateViewLayout(b.root, params()) }
    }

    // ------------------------------------------------------------------ controles

    private fun setupButtons() {
        b.btnMapFull.setOnClickListener { tap(it); setMode(Mode.MAP) }
        b.btnSource.setOnClickListener { tap(it); nextSource() }
        b.pill.setOnClickListener { tap(it); setMode(Mode.SPLIT) }
        setupPillDrag()
        b.btnMusicFull.setOnClickListener {
            tap(it)
            // Música completa: vuelve la pantalla principal de Velora (y este panel se cierra).
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            stopSelf()
        }
        b.play.setOnClickListener {
            tap(it)
            if (remote.connected) PhoneLinkHub.send(this, "toggle")
            else local?.let { c -> if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play() }
                ?: mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        }
        b.next.setOnClickListener {
            tap(it)
            if (remote.connected) PhoneLinkHub.send(this, "next")
            else local?.transportControls?.skipToNext() ?: mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
        }
        b.prev.setOnClickListener {
            tap(it)
            if (remote.connected) PhoneLinkHub.send(this, "previous")
            else local?.transportControls?.skipToPrevious() ?: mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        }
        b.volume.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: android.widget.SeekBar, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                b.volumePct.text = getString(R.string.percent, value)
                setVolume(value)
            }
            override fun onStartTrackingTouch(bar: android.widget.SeekBar) { draggingVolume = true }
            override fun onStopTrackingTouch(bar: android.widget.SeekBar) {
                draggingVolume = false
                setVolume(bar.progress)
                renderVolume()
            }
        })
        b.mute.setOnClickListener {
            tap(it)
            runCatching { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI) }
            renderMute()
        }
    }

    private fun pillPrefs() = getSharedPreferences("overlay", MODE_PRIVATE)

    /** Tocar abre el panel; arrastrar mueve la bolita (para que no tape el teclado ni el mapa). */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupPillDrag() {
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        b.pill.setOnTouchListener { v, e ->
            if (mode != Mode.MAP) return@setOnTouchListener false
            val lp = b.root.layoutParams as? WindowManager.LayoutParams ?: return@setOnTouchListener false
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y
                    dragging = false
                    v.isPressed = true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && kotlin.math.hypot(dx, dy) > slop) { dragging = true; v.isPressed = false }
                    if (dragging) {
                        val (sw, sh) = screen()
                        lp.x = (startX + dx).toInt().coerceIn(0, (sw - b.root.width).coerceAtLeast(0))
                        lp.y = (startY + dy).toInt().coerceIn(0, (sh - b.root.height).coerceAtLeast(0))
                        runCatching { wm.updateViewLayout(b.root, lp) }
                    }
                }
                android.view.MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    if (dragging) pillPrefs().edit().putInt(KEY_PILL_X, lp.x).putInt(KEY_PILL_Y, lp.y).apply()
                    else v.performClick()
                }
                android.view.MotionEvent.ACTION_CANCEL -> v.isPressed = false
            }
            true
        }
    }

    private fun showingRemote() = remote.connected && remote.state != null && !preferLocal

    /** Con OfflineTune: volumen del celular (0-100). Sin él: volumen de música del estéreo. */
    private fun setVolume(pct: Int) {
        if (showingRemote()) {
            PhoneLinkHub.send(this, "set_volume", JsonPrimitive(pct.coerceIn(0, 100)))
        } else {
            runCatching {
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, (pct * max + 50) / 100, 0)
            }
            renderMute()
        }
    }

    /**
     * Cambiar de fuente: OfflineTune (si está conectado) y cada app con música en el estéreo
     * (Spotify, ZLink, la radio...), en ese orden y dando la vuelta.
     */
    private fun nextSource() {
        val options = buildList<MediaController?> {
            if (remote.connected) add(null) // null = OfflineTune
            addAll(localList)
        }
        if (options.size < 2) return
        val current = if (showingRemote()) null else local
        val i = options.indexOfFirst { it?.sessionToken == current?.sessionToken }.coerceAtLeast(0)
        val next = options[(i + 1) % options.size]
        if (next == null) {
            preferLocal = false
        } else {
            preferLocal = true
            bindLocal(next)
        }
        render()
    }

    private fun tap(v: View) = v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

    private fun mediaKey(code: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    // ------------------------------------------------------------------ música local

    private val localCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = render()
        override fun onPlaybackStateChanged(state: PlaybackState?) = render()
        override fun onSessionDestroyed() = bindLocal(null)
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> onSessions(list.orEmpty()) }

    /** Si el usuario eligió una app y sigue activa, se respeta; si no, la que esté sonando. */
    private fun onSessions(list: List<MediaController>) {
        localList = list
        val kept = local?.let { cur -> list.firstOrNull { it.sessionToken == cur.sessionToken } }
        if (kept != null && preferLocal) { render(); return }
        bindLocal(list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull())
        if (list.isEmpty()) preferLocal = false
    }

    private fun startLocalMedia() {
        if (!NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)) return
        val sm = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        val component = ComponentName(this, MediaListenerService::class.java)
        runCatching {
            sm.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
            sessions = sm
            onSessions(sm.getActiveSessions(component))
        }
    }

    private fun stopLocalMedia() {
        sessions?.removeOnActiveSessionsChangedListener(sessionsListener)
        local?.unregisterCallback(localCallback)
        local = null
    }

    private fun bindLocal(c: MediaController?) {
        if (c != null && c.sessionToken == local?.sessionToken) return
        local?.unregisterCallback(localCallback)
        local = c
        c?.registerCallback(localCallback, handler)
        render()
    }

    // ------------------------------------------------------------------ pintar

    private fun tint() {
        val list = ColorStateList.valueOf(accent)
        // Todo lo que es acento sale del color elegido en Ajustes (en el coche de Juan, naranja).
        b.panel.foregroundTintList = list
        b.play.backgroundTintList = list
        b.source.backgroundTintList = list
        b.source.setTextColor(accent)
        b.pill.foregroundTintList = list
        b.progress.progressTintList = list
        b.volume.progressTintList = list
        b.volume.thumbTintList = list
    }

    private val tick = Runnable { render() }

    private fun render() {
        handler.removeCallbacks(tick)
        val st = remote.state
        if (showingRemote() && st != null) {
            b.source.text = st.source
            setText(b.title, st.title)
            setText(b.artist, st.artist)
            showArt(remote.art)
            // Solo el preset activo, en verde; los demás se ven en la música completa.
            b.preset.text = st.preset.orEmpty()
            b.preset.visibility = if (st.preset != null) View.VISIBLE else View.GONE
            b.btWarning.visibility = if (st.btAudio || st.androidAuto) View.GONE else View.VISIBLE
            val elapsed = if (st.isPlaying) (SystemClock.elapsedRealtime() - remote.stateAt) / 1000 else 0
            showProgress((st.positionSec + elapsed).coerceIn(0, st.durationSec.coerceAtLeast(0)), st.durationSec)
            showPlaying(st.isPlaying)
            if (st.isPlaying) handler.postDelayed(tick, 1_000L)
            enableControls(true)
        } else {
            val c = local
            val meta = c?.metadata
            b.source.text = c?.let { appName(it.packageName) } ?: getString(R.string.mini_stereo)
            setText(b.title, meta?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE))
            setText(b.artist, meta?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST))
            showArt(meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART))
            b.preset.visibility = View.GONE
            b.btWarning.visibility = View.GONE
            val state = c?.playbackState
            val playing = state?.state == PlaybackState.STATE_PLAYING
            val durMs = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            if (state != null && durMs > 0) {
                // Posición local: la última que dio la app más lo que ha avanzado desde entonces.
                var posMs = state.position
                if (playing) posMs += ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed).toLong()
                showProgress((posMs / 1000).coerceIn(0, durMs / 1000), durMs / 1000)
                if (playing) handler.postDelayed(tick, 1_000L)
            } else {
                b.progressRow.visibility = View.GONE
            }
            showPlaying(playing)
            enableControls(c != null)
        }
        val sources = (if (remote.connected) 1 else 0) + localList.size
        b.btnSource.isEnabled = sources > 1
        b.btnSource.alpha = if (sources > 1) 1f else 0.35f
        renderMute()
        renderVolume()
    }

    /** Texto real o nada (sin textos fijos); el título nunca se corta a media línea. */
    private fun setText(view: android.widget.TextView, text: String?) {
        if (view.text.toString() != text.orEmpty()) view.text = text.orEmpty()
    }

    private fun showArt(art: android.graphics.Bitmap?) {
        if (art != null) {
            b.art.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            b.art.imageTintList = null
            b.art.setImageBitmap(art)
        } else {
            // Sin portada: fondo negro con una nota tenue arriba.
            b.art.scaleType = android.widget.ImageView.ScaleType.CENTER
            b.art.setImageResource(R.drawable.ic_music_big)
            b.art.imageTintList = ColorStateList.valueOf(0x22FFFFFF)
        }
    }

    private fun showPlaying(playing: Boolean) {
        b.play.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private val appNames = HashMap<String, String>()

    private fun appName(pkg: String): String = appNames.getOrPut(pkg) {
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }

    /** Porcentaje: el de OfflineTune si es la fuente; si no, el volumen de música del estéreo. */
    private fun renderVolume() {
        if (draggingVolume) return
        val st = remote.state
        val pct = if (showingRemote() && st != null) {
            st.volume.coerceIn(0, 100)
        } else {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
        }
        b.volume.progress = pct
        b.volumePct.text = getString(R.string.percent, pct)
    }

    private fun showProgress(pos: Long, dur: Long) {
        if (dur <= 0) { b.progressRow.visibility = View.GONE; return }
        b.progressRow.visibility = View.VISIBLE
        b.pos.text = time(pos)
        b.dur.text = time(dur)
        b.progress.progress = (pos * 1000 / dur).toInt()
    }

    private fun enableControls(on: Boolean) = listOf(b.prev, b.play, b.next).forEach {
        it.isEnabled = on
        it.alpha = if (on) 1f else 0.4f
    }

    private fun renderMute() {
        val muted = audio.isStreamMute(AudioManager.STREAM_MUSIC) || audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
        b.mute.setImageResource(if (muted) R.drawable.ic_vol_off else R.drawable.ic_vol_up)
        b.mute.imageTintList = ColorStateList.valueOf(if (muted) 0xFFFF3B4A.toInt() else 0xFFFFFFFF.toInt())
    }

    private fun time(sec: Long) = String.format(Locale.US, "%d:%02d", sec / 60, sec % 60)

    // ------------------------------------------------------------------ primer plano

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.drive_channel), NotificationManager.IMPORTANCE_MIN))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_music)
            .setContentTitle(getString(R.string.drive_running))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        ServiceCompat.startForeground(this, 4410, n, type)
    }

    companion object {
        private const val CHANNEL = "drive_mode"
        private const val EXTRA_MODE = "mode"
        private const val KEY_PILL_X = "pill_x"
        private const val KEY_PILL_Y = "pill_y"
        private const val PILL_DP = 72

        fun canShow(context: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

        fun start(context: Context, mode: Mode = Mode.SPLIT) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, DriveOverlayService::class.java).putExtra(EXTRA_MODE, mode.name))
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DriveOverlayService::class.java))
        }
    }
}
