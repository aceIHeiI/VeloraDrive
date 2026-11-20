package com.iheii.veloradrive.video

import com.iheii.veloradrive.LocaleActivity
import kotlinx.serialization.json.JsonPrimitive
import android.app.PictureInPictureParams
import com.iheii.veloradrive.DriveOverlayService
import androidx.core.content.ContextCompat
import android.graphics.drawable.Icon
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.app.RemoteAction
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.location.Location
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.ColorInt
import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import com.iheii.veloradrive.ProfileStore
import com.iheii.veloradrive.R
import com.iheii.veloradrive.ThemeManager
import com.iheii.veloradrive.TripTracker
import com.iheii.veloradrive.databinding.ActivityVideoPlayerBinding
import com.iheii.veloradrive.designScaled
import com.iheii.veloradrive.goImmersive
import com.iheii.veloradrive.link.CarVideoPlay
import com.iheii.veloradrive.link.PhoneLinkHub
import com.iheii.veloradrive.link.VideoInfo
import com.iheii.veloradrive.link.VideoItem
import com.iheii.veloradrive.link.VideoQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Reproductor de video a pantalla completa con ExoPlayer. El celular manda la pista de video y la de
 * audio por separado (MergingMediaSource), salvo los archivos descargados con el audio adentro
 * ("muxed"), que son una sola fuente. Solo se ofrecen las calidades que este estéreo decodifica.
 * Vive en su propia tarea para que "Mini" (Picture-in-Picture) deje ver el launcher detrás.
 */
@OptIn(UnstableApi::class)
class VideoPlayerActivity : LocaleActivity() {

    private lateinit var b: ActivityVideoPlayerBinding
    @ColorInt private var accent = 0
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audio: AudioManager

    private var player: ExoPlayer? = null
    private var api: VideoApi? = null
    private var loadJob: Job? = null

    private var currentId: String? = null
    /** El video lo mandó el celular: anterior, siguiente y el fin del video los resuelve el celular con su cola. */
    private var fromPhone = false
    private var title = ""
    private var itemDownloaded = false
    private var info: VideoInfo? = null
    private var playable: List<VideoQuality> = emptyList()
    /** Altura elegida a mano; null = Automática. */
    private var chosenHeight: Int? = null
    /** El usuario ya eligió calidad en esta pantalla; si no, manda la guardada (por defecto, la mínima). */
    private var userChose = false
    private var current: VideoQuality? = null
    private var audioOnly = false
    private var hiddenByMotion = false
    private var phoneName: String? = null
    private var videoAspect = Rational(16, 9)

    override fun attachBaseContext(base: Context) = super.attachBaseContext(base.designScaled())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = java.lang.ref.WeakReference(this)
        b = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)
        goImmersive()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        accent = ThemeManager.accent(this)

        styleControls()
        setupControls()
        createPlayer()
        ContextCompat.registerReceiver(
            this, pipReceiver,
            IntentFilter().apply { addAction(ACTION_PIP_PREV); addAction(ACTION_PIP_PLAY); addAction(ACTION_PIP_NEXT) },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        lifecycleScope.launch {
            val p = runCatching { ProfileStore.flow(this@VideoPlayerActivity).first() }.getOrNull()
            val ui = PhoneLinkHub.ui(this@VideoPlayerActivity).value
            phoneName = p?.deviceName ?: ui.state?.device
            renderSubtitle()
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        // La burbuja se oculta mientras el video está a la vista (pantalla completa o ventana mini).
        DriveOverlayService.setVideoShown(true)
        PhoneLinkHub.acquire(this)
        if (VideoPrefs.hideWhileMoving(this) && TripTracker.hasPermission(this)) {
            TripTracker.addListener(tripListener)
            TripTracker.acquire(this, fast = true)
            tripActive = true
        }
        renderVolume()
        handler.post(progressTick)
        handler.post(positionTick)
    }

    override fun onResume() {
        super.onResume()
        goImmersive()
    }

    override fun onStop() {
        super.onStop()
        // Fuera de la vista (y no en ventana mini): el video se pausa.
        player?.pause()
        com.iheii.veloradrive.link.AudioWatchdog.videoPlaying = false
        handler.removeCallbacks(progressTick)
        handler.removeCallbacks(positionTick)
        // El video se cerró a propósito: el celular ya no debe ofrecer "Seguir viendo en el celular".
        PhoneLinkHub.send(this, "video_stop")
        DriveOverlayService.setVideoShown(false)
        if (tripActive) {
            tripActive = false
            TripTracker.removeListener(tripListener)
            TripTracker.release(this, fast = true)
        }
        PhoneLinkHub.release(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance?.get() === this) instance = null
        DriveOverlayService.setVideoShown(false)
        runCatching { unregisterReceiver(pipReceiver) }
        handler.removeCallbacksAndMessages(null)
        loadJob?.cancel()
        player?.release()
        player = null
    }

    // ---------------------------------------------------------------- abrir un video

    private fun handleIntent(intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: run { finish(); return }
        val t = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val posMs = intent.getLongExtra(EXTRA_POS_SEC, 0L).coerceAtLeast(0L) * 1000L
        val downloaded = intent.getBooleanExtra(EXTRA_DOWNLOADED, false)
        fromPhone = intent.getBooleanExtra(EXTRA_FROM_PHONE, false)
        open(id, t, downloaded, posMs)
    }

    private fun open(id: String, t: String, downloaded: Boolean, posMs: Long) {
        currentId = id
        title = t
        itemDownloaded = downloaded
        info = null
        current = null
        playable = emptyList()
        b.title.text = t
        renderSubtitle()
        renderNav()
        renderQualityChip()
        hideQualityMenu()
        if (!isInPictureInPictureMode) showControls()
        if (!VideoPrefs.warningAccepted(this)) {
            player?.stop()
            showWarning { start(posMs) }
        } else start(posMs)
    }

    /** Aviso de seguridad, una sola vez (Ajustes › Video lo vuelve a activar). */
    private fun showWarning(onOk: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(R.string.video_warning_title)
            .setMessage(R.string.video_warning_msg)
            .setCancelable(false)
            .setPositiveButton(R.string.video_warning_ok) { _, _ ->
                VideoPrefs.setWarningAccepted(this, true)
                onOk()
            }
            .setOnDismissListener { goImmersive() }
            .show()
    }

    /** Pide /video/info, filtra calidades y arranca. */
    private fun start(posMs: Long) {
        val id = currentId ?: return
        loadJob?.cancel()
        hideError()
        b.buffering.visibility = View.VISIBLE
        loadJob = lifecycleScope.launch {
            try {
                val a = VideoApi.current(this@VideoPlayerActivity)
                if (a == null) {
                    showError(R.string.video_error_disconnected) { start(posMs) }
                    return@launch
                }
                api = a
                val inf = a.info(id)
                info = inf
                if (title.isBlank()) {
                    title = inf.title
                    b.title.text = inf.title
                }
                playable = withContext(Dispatchers.Default) { VideoCodecs.playable(inf.qualities) }
                renderQualityChip()
                play(posMs)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                showError(R.string.video_error_play) { start(posMs) }
            }
        }
    }

    private fun pickQuality(): VideoQuality? {
        val h = effHeight() ?: return VideoCodecs.auto(playable, VideoPrefs.autoMaxHeight(this))
        return playable.firstOrNull { it.height == h } ?: playable.firstOrNull { it.height <= h } ?: playable.lastOrNull()
    }

    /** Altura pedida: la elegida ahora, o la guardada; null = Automática. Por defecto arranca en la mínima. */
    private fun effHeight(): Int? {
        if (userChose) return chosenHeight
        return when (val saved = VideoPrefs.savedHeight(this)) {
            VideoPrefs.HEIGHT_AUTO -> null
            VideoPrefs.HEIGHT_MIN -> playable.lastOrNull()?.height
            else -> saved
        }
    }

    /** Arma la fuente según la calidad y el modo y empieza en [posMs]. */
    private fun play(posMs: Long) {
        val a = api ?: return
        val id = currentId ?: return
        val p = player ?: return
        val q = pickQuality()
        if (q == null && !audioOnly) {
            b.buffering.visibility = View.GONE
            showError(R.string.video_error_no_quality) { start(posMs) }
            return
        }
        current = q
        val source: MediaSource = when {
            audioOnly -> progressive(a.audioUrl(id))
            q!!.muxed -> progressive(a.streamUrl(id, q.height, q.codec))
            else -> MergingMediaSource(
                true, true,
                progressive(a.streamUrl(id, q.height, q.codec)),
                progressive(a.audioUrl(id)),
            )
        }
        hideError()
        // Que no suenen dos cosas: la música del celular se pausa antes de empezar. Si el video lo mandó el
        // celular, ya la calló él; mandar "pause" ahora pausaría el propio video del coche.
        if (!fromPhone) PhoneLinkHub.send(this, "pause")
        p.setMediaSource(source, posMs)
        p.prepare()
        p.playWhenReady = true
        applyVideoVisibility()
        renderQualityChip()
        renderSubtitle()
    }

    private fun progressive(url: String): MediaSource = sourceFactory.createMediaSource(MediaItem.fromUri(url))

    /** Vuelve a armar la fuente en la posición actual (cambio de calidad, solo audio, reintento). */
    private fun rebuild() {
        val pos = player?.currentPosition ?: 0L
        if (info == null) start(pos) else play(pos)
    }

    // ---------------------------------------------------------------- ExoPlayer

    private val sourceFactory: ProgressiveMediaSource.Factory by lazy {
        val client = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        // Solo los contenedores que manda el celular: MP4 (también fragmentado) y WebM/MKV.
        val extractors = ExtractorsFactory { arrayOf(FragmentedMp4Extractor(), Mp4Extractor(), MatroskaExtractor()) }
        ProgressiveMediaSource.Factory(OkHttpDataSource.Factory(client), extractors)
    }

    private fun createPlayer() {
        // El audio pasa por VideoLoudness: mismo nivel que la música normalizada de OfflineTune.
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean,
            ): androidx.media3.exoplayer.audio.AudioSink =
                androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(VideoLoudness()))
                    .build()
        }.setEnableDecoderFallback(true)
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 40_000, 1_500, 3_000)
            .build()
        val p = ExoPlayer.Builder(this, renderers, sourceFactory)
            .setLoadControl(load)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(SEEK_MS)
            .setSeekForwardIncrementMs(SEEK_MS)
            .build()
        p.addListener(playerListener)
        b.playerView.player = p
        player = p
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            b.buffering.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            if (state == Player.STATE_ENDED) {
                val id = currentId ?: return
                if (fromPhone) { PhoneLinkHub.send(this@VideoPlayerActivity, "video_ended", JsonPrimitive(id)); showControls(); return }
                VideoQueue.next(id)?.let { playItem(it) } ?: showControls()
            }
            renderProgress()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            com.iheii.veloradrive.link.AudioWatchdog.videoPlaying = isPlaying
            b.btnPlay.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            if (isInPictureInPictureMode) runCatching { setPictureInPictureParams(pipParams()) }
            if (isPlaying) scheduleHide() else showControls()
        }

        override fun onPlayerError(error: PlaybackException) {
            b.buffering.visibility = View.GONE
            showError(R.string.video_error_play) { rebuild() }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                val r = videoSize.width.toFloat() * videoSize.pixelWidthHeightRatio / videoSize.height
                videoAspect = Rational((r.coerceIn(0.42f, 2.38f) * 1000).toInt(), 1000)
            }
        }
    }

    // ---------------------------------------------------------------- controles

    private fun styleControls() {
        val white = ColorStateList.valueOf(0xFFFFFFFF.toInt())
        b.btnBack.backgroundTintList = ColorStateList.valueOf(accent)
        b.btnBack.imageTintList = ColorStateList.valueOf(accent)
        listOf(b.btnPrev, b.btnRewind, b.btnForward, b.btnNext).forEach {
            it.background = VideoUi.outline(this, accent, 0x66000000, 0f, oval = true)
            it.imageTintList = ColorStateList.valueOf(accent)
        }
        b.btnPlay.backgroundTintList = ColorStateList.valueOf(accent)
        b.btnPlay.imageTintList = ColorStateList.valueOf(0xFF0A0B0E.toInt())
        b.volIcon.imageTintList = ColorStateList.valueOf(accent)
        listOf(b.seek, b.volume).forEach {
            it.progressTintList = ColorStateList.valueOf(accent)
            it.thumbTintList = ColorStateList.valueOf(accent)
            it.progressBackgroundTintList = ColorStateList.valueOf(0x66FFFFFF)
        }
        b.qualityMenu.background = VideoUi.outline(this, accent, 0xF2121519.toInt(), 10f, strokeDp = 1f)
        b.btnRetry.background = VideoUi.outline(this, accent, ThemeManager.withAlpha(accent, 0x22), 12f)
        b.btnRetry.setTextColor(accent)
        b.centerIcon.imageTintList = ColorStateList.valueOf(accent)
        b.chipQualityText.setTextColor(accent)
        b.chipQualityIcon.imageTintList = ColorStateList.valueOf(accent)
        b.chipQuality.background = VideoUi.outline(this, accent, 0x99000000.toInt(), 10f)
        b.chipMini.background = VideoUi.outline(this, CHIP_STROKE, 0x99000000.toInt(), 10f, strokeDp = 1f)
        b.chipMiniText.setTextColor(white)
        b.chipMiniIcon.imageTintList = white
        renderAudioChip()
    }

    private fun renderAudioChip() {
        val c = if (audioOnly) accent else 0xFFFFFFFF.toInt()
        b.chipAudio.background = if (audioOnly) VideoUi.outline(this, accent, ThemeManager.withAlpha(accent, 0x33), 10f)
        else VideoUi.outline(this, CHIP_STROKE, 0x99000000.toInt(), 10f, strokeDp = 1f)
        b.chipAudioText.setTextColor(c)
        b.chipAudioIcon.imageTintList = ColorStateList.valueOf(c)
    }

    private fun setupControls() {
        // Tocar el video muestra los controles; tocar un hueco con los controles a la vista los oculta.
        b.root.setOnClickListener { if (b.controls.visibility == View.VISIBLE) hideControls() else showControls() }
        b.controls.setOnClickListener {
            if (b.qualityMenu.visibility == View.VISIBLE) hideQualityMenu() else hideControls()
        }
        b.btnBack.setOnClickListener { finish() }
        b.btnPlay.setOnClickListener {
            val p = player ?: return@setOnClickListener
            if (p.isPlaying) p.pause() else {
                // Calla la música del celular; si el video lo mandó el celular, ya está callada (y "pause" iría al video).
                if (!fromPhone) PhoneLinkHub.send(this, "pause")
                if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
                if (p.playbackState == Player.STATE_IDLE) rebuild() else p.play()
            }
            showControls()
        }
        b.btnRewind.setOnClickListener { player?.seekBack(); showControls() }
        b.btnForward.setOnClickListener { player?.seekForward(); showControls() }
        b.btnPrev.setOnClickListener {
            val p = player
            val id = currentId ?: return@setOnClickListener
            // Como en cualquier reproductor: pasados unos segundos, "anterior" vuelve al inicio.
            if (p != null && p.currentPosition > 5_000) p.seekTo(0)
            else if (fromPhone) PhoneLinkHub.send(this, "video_prev", JsonPrimitive(id))
            else VideoQueue.previous(id)?.let { playItem(it) }
            showControls()
        }
        b.btnNext.setOnClickListener {
            currentId?.let { id ->
                if (fromPhone) PhoneLinkHub.send(this, "video_next", JsonPrimitive(id))
                else VideoQueue.next(id)?.let { playItem(it) }
            }
            showControls()
        }
        b.seek.max = SEEK_MAX
        b.seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val dur = player?.duration?.takeIf { it > 0 } ?: return
                b.position.text = VideoUi.duration(dur * progress / SEEK_MAX / 1000)
            }
            override fun onStartTrackingTouch(sb: SeekBar) { seeking = true; handler.removeCallbacks(hideControlsRunnable) }
            override fun onStopTrackingTouch(sb: SeekBar) {
                seeking = false
                val p = player
                val dur = p?.duration?.takeIf { it > 0 }
                if (p != null && dur != null) p.seekTo(dur * sb.progress / SEEK_MAX)
                showControls()
            }
        })
        b.volume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0) }
            }
            override fun onStartTrackingTouch(sb: SeekBar) = handler.removeCallbacks(hideControlsRunnable)
            override fun onStopTrackingTouch(sb: SeekBar) = showControls()
        })
        b.chipQuality.setOnClickListener {
            if (b.qualityMenu.visibility == View.VISIBLE) hideQualityMenu() else showQualityMenu()
        }
        b.chipAudio.setOnClickListener {
            audioOnly = !audioOnly
            renderAudioChip()
            hideQualityMenu()
            rebuild()
            showControls()
        }
        b.chipMini.setOnClickListener { enterMini() }
    }

    private var seeking = false

    private fun playItem(item: VideoItem) = open(item.id, item.title, item.downloaded, 0L)

    private fun renderNav() {
        val id = currentId
        val hasPrev = id != null && (fromPhone || VideoQueue.previous(id) != null)
        val hasNext = id != null && (fromPhone || VideoQueue.next(id) != null)
        b.btnNext.isEnabled = hasNext
        b.btnNext.alpha = if (hasNext) 1f else 0.35f
        // "Anterior" siempre sirve: vuelve al inicio del video si no hay uno antes.
        b.btnPrev.alpha = if (hasPrev) 1f else 0.6f
    }

    private fun renderSubtitle() {
        val local = current?.local ?: itemDownloaded
        val state = getString(if (local) R.string.video_downloaded else R.string.video_online)
        val phone = (phoneName ?: getString(R.string.video_phone_default)).uppercase(SPANISH)
        b.subtitle.text = getString(R.string.video_from, phone, state)
    }

    private fun renderQualityChip() {
        val label = when {
            effHeight() != null -> pickQuality()?.let { getString(R.string.video_height, it.height) }
                ?: getString(R.string.video_quality_auto_plain)
            else -> VideoCodecs.auto(playable, VideoPrefs.autoMaxHeight(this))
                ?.let { getString(R.string.video_quality_auto_short, it.height) }
                ?: getString(R.string.video_quality_auto_plain)
        }
        b.chipQualityText.text = getString(R.string.video_quality_chip, label)
    }

    private fun showQualityMenu() {
        val menu = b.qualityMenu
        menu.removeAllViews()
        val d = resources.displayMetrics.density
        fun add(text: String, selected: Boolean, onClick: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((12 * d).toInt(), 0, (12 * d).toInt(), 0)
                background = if (selected) VideoUi.outline(this@VideoPlayerActivity, 0, ThemeManager.withAlpha(accent, 0x26), 8f, strokeDp = 0f)
                else VideoUi.outline(this@VideoPlayerActivity, 0, 0, 8f, strokeDp = 0f)
                setOnClickListener { onClick() }
            }
            row.addView(TextView(this).apply {
                this.text = text
                textSize = 17f
                setTextColor(if (selected) accent else 0xFFF2F6FA.toInt())
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (selected) row.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_v_check)
                imageTintList = ColorStateList.valueOf(accent)
            }, LinearLayout.LayoutParams((18 * d).toInt(), (18 * d).toInt()))
            menu.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (46 * d).toInt()))
        }
        val manual = if (effHeight() != null) pickQuality()?.height else null
        add(getString(R.string.video_quality_auto), manual == null) { choose(null) }
        playable.forEach { q -> add(getString(R.string.video_height, q.height), manual == q.height) { choose(q.height) } }
        (menu.layoutParams as FrameLayout.LayoutParams).apply {
            marginStart = b.chipQuality.left
            width = maxOf((190 * d).toInt(), b.chipQuality.width)
            topMargin = b.chipQuality.bottom + b.topBar.top + (8 * d).toInt()
        }
        menu.requestLayout()
        menu.visibility = View.VISIBLE
        handler.removeCallbacks(hideControlsRunnable)
    }

    private fun hideQualityMenu() {
        if (b.qualityMenu.visibility == View.VISIBLE) {
            b.qualityMenu.visibility = View.GONE
            scheduleHide()
        }
    }

    private fun choose(height: Int?) {
        hideQualityMenu()
        val before = pickQuality()
        userChose = true
        chosenHeight = height
        VideoPrefs.setSavedHeight(this, height ?: VideoPrefs.HEIGHT_AUTO)
        renderQualityChip()
        // En solo audio la calidad se guarda para cuando vuelva la imagen.
        if (!audioOnly && pickQuality() != before) rebuild()
        showControls()
    }

    private fun showControls() {
        if (isInPictureInPictureMode) return
        b.controls.visibility = View.VISIBLE
        renderProgress()
        scheduleHide()
    }

    private fun hideControls() {
        hideQualityMenu()
        b.controls.visibility = View.GONE
        handler.removeCallbacks(hideControlsRunnable)
        goImmersive()
    }

    private val hideControlsRunnable = Runnable {
        if (b.qualityMenu.visibility != View.VISIBLE && !seeking && player?.isPlaying == true) hideControls()
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, HIDE_MS)
    }

    /**
     * Cada 5 s le dice al celular qué video va y en qué segundo. Si el coche se apaga de golpe (no hay
     * onStop), el celular ofrece seguirlo desde ahí.
     */
    private val positionTick = object : Runnable {
        override fun run() {
            val p = player
            val id = currentId
            if (p != null && id != null && p.isPlaying) {
                PhoneLinkHub.send(this@VideoPlayerActivity, "video_position", JsonPrimitive(id), p.currentPosition / 1000)
            }
            handler.postDelayed(this, POSITION_EVERY_MS)
        }
    }

    private val progressTick = object : Runnable {
        override fun run() {
            renderProgress()
            handler.postDelayed(this, 500)
        }
    }

    private fun renderProgress() {
        if (b.controls.visibility != View.VISIBLE || seeking) return
        val p = player ?: return
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 }
            ?: info?.durationSec?.times(1000)?.takeIf { it > 0 } ?: 0L
        val pos = p.currentPosition.coerceAtLeast(0L)
        b.position.text = VideoUi.duration(pos / 1000)
        b.duration.text = VideoUi.duration(dur / 1000)
        b.seek.progress = if (dur > 0) (pos * SEEK_MAX / dur).toInt().coerceIn(0, SEEK_MAX) else 0
        b.seek.secondaryProgress = if (dur > 0) (p.bufferedPosition * SEEK_MAX / dur).toInt().coerceIn(0, SEEK_MAX) else 0
    }

    private fun renderVolume() {
        runCatching {
            b.volume.max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            b.volume.progress = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        }
    }

    // ---------------------------------------------------------------- errores

    private var retryAction: (() -> Unit)? = null

    private fun showError(@StringRes text: Int, retry: () -> Unit) {
        b.buffering.visibility = View.GONE
        b.errorText.setText(text)
        b.errorBox.visibility = View.VISIBLE
        retryAction = retry
        b.btnRetry.setOnClickListener {
            hideError()
            retryAction?.invoke()
        }
        showControls()
    }

    private fun hideError() {
        b.errorBox.visibility = View.GONE
    }

    // ---------------------------------------------------------------- solo audio / en movimiento

    /** Sin imagen: "Solo audio" (no se pide el video) u oculto por ir a más de 10 km/h (pista apagada). */
    private fun applyVideoVisibility() {
        val off = audioOnly || hiddenByMotion
        b.playerView.visibility = if (off) View.INVISIBLE else View.VISIBLE
        b.centerInfo.visibility = if (off) View.VISIBLE else View.GONE
        b.centerText.setText(if (audioOnly) R.string.video_audio_only_label else R.string.video_hidden_moving)
        val p = player ?: return
        val disable = hiddenByMotion && !audioOnly
        if (p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO) != disable) {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, disable)
                .build()
        }
    }

    private var tripActive = false

    private val tripListener = object : TripTracker.Listener {
        override fun onFix(loc: Location) {
            if (!loc.hasSpeed()) return
            val kmh = loc.speed * 3.6f
            // Con margen para que no parpadee alrededor de 10 km/h.
            val hide = if (hiddenByMotion) kmh > SHOW_BELOW_KMH else kmh > HIDE_ABOVE_KMH
            if (hide != hiddenByMotion) {
                hiddenByMotion = hide
                applyVideoVisibility()
            }
        }
    }

    // ---------------------------------------------------------------- Mini (Picture-in-Picture)

    /** La ventana mini lleva sus propios botones: anterior, pausa/reproducir y siguiente. */
    private fun pipParams(): PictureInPictureParams {
        fun action(icon: Int, title: String, code: Int, name: String) = RemoteAction(
            Icon.createWithResource(this, icon), title, title,
            PendingIntent.getBroadcast(this, code, Intent(name).setPackage(packageName), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
        )
        val playing = player?.isPlaying == true
        return PictureInPictureParams.Builder()
            .setAspectRatio(videoAspect)
            .setActions(listOf(
                action(R.drawable.ic_prev, "Anterior", 1, ACTION_PIP_PREV),
                action(if (playing) R.drawable.ic_pause else R.drawable.ic_play, if (playing) "Pausa" else "Reproducir", 2, ACTION_PIP_PLAY),
                action(R.drawable.ic_next, "Siguiente", 3, ACTION_PIP_NEXT),
            ))
            .build()
    }

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PIP_PREV -> b.btnPrev.performClick()
                ACTION_PIP_PLAY -> b.btnPlay.performClick()
                ACTION_PIP_NEXT -> b.btnNext.performClick()
            }
        }
    }

    private fun enterMini() {
        hideQualityMenu()
        val ok = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) && runCatching {
            enterPictureInPictureMode(pipParams())
        }.getOrDefault(false)
        if (!ok) Toast.makeText(this, R.string.video_no_pip, Toast.LENGTH_SHORT).show()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            hideQualityMenu()
            b.controls.visibility = View.GONE
            b.errorBox.visibility = View.GONE
            handler.removeCallbacks(hideControlsRunnable)
        } else if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            // Se cerró la ventana mini con la X: se termina el video.
            finish()
        } else {
            showControls()
        }
    }

    companion object {
        private const val POSITION_EVERY_MS = 1_000L
        private const val EXTRA_ID = "video_id"
        private const val EXTRA_TITLE = "video_title"
        private const val EXTRA_POS_SEC = "video_pos_sec"
        private const val EXTRA_DOWNLOADED = "video_downloaded"
        private const val EXTRA_FROM_PHONE = "video_from_phone"
        private var instance: java.lang.ref.WeakReference<VideoPlayerActivity>? = null

        /** El celular cerró el video: se cierra la pantalla (y la ventana mini si la hay). */
        fun closeFromPhone() { instance?.get()?.finish() }

        /** El celular pausa o reanuda el video desde su mini reproductor. */
        fun controlFromPhone(action: String) {
            val a = instance?.get() ?: return
            a.handler.post {
                val p = a.player ?: return@post
                when (action) {
                    "play" -> p.play()
                    "pause" -> p.pause()
                    else -> if (p.isPlaying) p.pause() else p.play()
                }
            }
        }
        private const val ACTION_PIP_PREV = "com.iheii.veloradrive.PIP_PREV"
        private const val ACTION_PIP_PLAY = "com.iheii.veloradrive.PIP_PLAY"
        private const val ACTION_PIP_NEXT = "com.iheii.veloradrive.PIP_NEXT"
        private const val SEEK_MS = 10_000L
        private const val SEEK_MAX = 1000
        private const val HIDE_MS = 4_000L
        private const val HIDE_ABOVE_KMH = 10f
        private const val SHOW_BELOW_KMH = 6f
        private const val CHIP_STROKE = 0xFFB4BFCA.toInt()
        private val SPANISH = Locale.forLanguageTag("es")

        private fun intent(context: Context, id: String, title: String, downloaded: Boolean, posSec: Long) =
            Intent(context, VideoPlayerActivity::class.java)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_DOWNLOADED, downloaded)
                .putExtra(EXTRA_POS_SEC, posSec)

        /** Desde la biblioteca: anterior/siguiente salen de [VideoQueue]. */
        fun open(context: Context, item: VideoItem, positionSec: Long = 0L) {
            context.startActivity(intent(context, item.id, item.title, item.downloaded, positionSec))
        }

        /** "Ver en el coche" desde el celular (mensaje video_play por el WebSocket). */
        fun openFromPhone(context: Context, vp: CarVideoPlay) {
            val item = VideoQueue.get(vp.id) ?: VideoItem(id = vp.id, title = vp.title)
            VideoQueue.ensure(item)
            // Si la pantalla ya está a la vista (a pantalla completa o en la ventana mini), el video nuevo se carga ahí mismo:
            // lanzar la actividad otra vez la sacaría de la ventana mini y taparía lo que el usuario estaba viendo (mapas).
            val live = instance?.get()
            if (live != null && !live.isFinishing && (live.isInPictureInPictureMode || live.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))) {
                val i = intent(context, vp.id, vp.title, item.downloaded, vp.positionSec).putExtra(EXTRA_FROM_PHONE, true)
                live.handler.post { live.setIntent(i); live.handleIntent(i) }
                return
            }
            runCatching {
                context.startActivity(
                    intent(context, vp.id, vp.title, item.downloaded, vp.positionSec)
                        .putExtra(EXTRA_FROM_PHONE, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
}
