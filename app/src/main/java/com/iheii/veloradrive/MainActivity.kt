package com.iheii.veloradrive

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.iheii.veloradrive.databinding.ActivityMainBinding
import com.iheii.veloradrive.databinding.ItemQuickBinding
import com.iheii.veloradrive.databinding.ScreensaverBinding
import androidx.lifecycle.repeatOnLifecycle
import androidx.activity.viewModels
import com.iheii.veloradrive.link.LinkConfig
import com.iheii.veloradrive.link.LinkPrefs
import com.iheii.veloradrive.link.LinkUiState
import com.iheii.veloradrive.link.PhoneLinkViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Pantalla principal "reproductor". Todo lo que consume (GPS, OBD2, sesión de medios, Wi-Fi, Bluetooth,
 * reloj) se registra en onResume y se suelta en onPause: con Waze o la música al frente, 0% de CPU.
 *
 * El launcher NO pide foco de audio a propósito: pedir AUDIOFOCUS_GAIN haría que Spotify o
 * Android Auto pausen la música cada vez que se vuelve al inicio.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var audio: AudioManager
    private val handler = Handler(Looper.getMainLooper())

    @ColorInt private var accent = 0
    private val spanish = Locale.forLanguageTag("es")

    private val quickSlots = listOf(Slot.APPS, Slot.VIDEO, Slot.RADIO)
    private val quickViews = mutableMapOf<Slot, ItemQuickBinding>()
    private var projectionTarget: SlotTarget? = null

    /** Espectro real para la onda de audio; solo corre con música y la pantalla visible. */
    private val spectrum = AudioSpectrum { binding.mountains.setBands(it) }
    private var playing = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                startGps()
                renderInfo()
                refreshBluetooth()
                startObd()
                updateSpectrum()
            }
        }

    override fun attachBaseContext(base: Context) = super.attachBaseContext(base.designScaled())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        goImmersive()

        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        VeloraClock.init(this)

        // Es la pantalla de inicio: "atrás" no la cierra, abre el screensaver.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (screensaver.isShowing) screensaver.hide() else showScreensaver()
            }
        })

        setupScreensaver()
        setupQuickList()
        setupMedia()
        setupPresets()
        setupBottomBar()
        setupStatusToggles()
        setupInfoTile()
        setupObdCard()

        applyAccent(ThemeManager.accent(this))
        binding.brandBox.setOnHoldListener { openSettings() }
        observeProfile()
        observeLink()
        renderTelemetryMode()
        binding.mountains.setIdleAnimation(CockpitPrefs.idleAnimation(this))
        BassController.init(this)
        EqPresets.init(this)
        requestMissingPermissions()
    }

    override fun onResume() {
        super.onResume()
        com.iheii.veloradrive.link.AudioWatchdog.veloraVisible = true
        goImmersive()
        refreshSlots()
        renderMute()
        loadInfoPages()
        renderPresets()
        startClock()
        startGps()
        renderInfo()
        startMedia()
        startConnectivity()
        startObd()
        renderClock()
        binding.mountains.setRunning(true)
        updateSpectrum()
        resetIdleTimer()
        link.start()
        // De vuelta en la pantalla completa: el reproductor mini ya no hace falta.
        DriveOverlayService.stop(this)
    }

    /**
     * Con el switch de Ajustes encendido, el reproductor mini queda encima de Android Auto (ZLink,
     * TLink...). Empieza como botón redondo para no tapar el mapa; al volver a Velora se quita solo.
     */
    private fun showPlayerOverProjection() {
        if (!CockpitPrefs.overlayOnProjection(this)) return
        if (!DriveOverlayService.canShow(this)) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setMessage(R.string.overlay_permission)
                .setPositiveButton(R.string.overlay_permission_go) { _, _ ->
                    launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")))
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        DriveOverlayService.start(this, DriveOverlayService.Mode.MAP)
    }

    /**
     * Navegación en modo dividido: abre Maps o Waze y pone el reproductor mini a la derecha
     * (65/35). La primera vez pide permiso para mostrarse sobre otras apps.
     */
    private fun openNavSplit() {
        if (!DriveOverlayService.canShow(this)) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setMessage(R.string.overlay_permission)
                .setPositiveButton(R.string.overlay_permission_go) { _, _ ->
                    launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")))
                }
                .setNegativeButton(R.string.cancel) { _, _ -> openSlot(Slot.NAV) }
                .show()
            return
        }
        // La ventana flotante queda siempre encima, aunque el mapa se abra después.
        DriveOverlayService.start(this, DriveOverlayService.Mode.SPLIT)
        openSlot(Slot.NAV)
    }

    override fun onPause() {
        super.onPause()
        com.iheii.veloradrive.link.AudioWatchdog.veloraVisible = false
        stopClock()
        stopGps()
        stopMedia()
        stopConnectivity()
        stopObd()
        link.stop()
        handler.removeCallbacksAndMessages(null)
        binding.mountains.setRunning(false)
        spectrum.stop()
        setMarquee(false)
        // Libera el Visualizer y la animación del fuego si estaban activos.
        screensaver.hide()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) AppRepository.clear()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    private fun color(@ColorRes res: Int) = ContextCompat.getColor(this, res)

    private fun tint(view: ImageView, @ColorInt c: Int) {
        view.imageTintList = ColorStateList.valueOf(c)
    }

    private fun requestMissingPermissions() {
        val wanted = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) wanted += Manifest.permission.BLUETOOTH_CONNECT
        // Para que las montañas de sonido y el fuego del screensaver sigan la música (Visualizer).
        wanted += Manifest.permission.RECORD_AUDIO
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    // ---------------------------------------------------------------- Ajustes

    /**
     * Ajustes a pantalla completa. Al volver se repinta lo que pudo cambiar; el resto (botones,
     * OBD, screensaver, perfil, conexión) ya se relee solo en onResume o desde sus flujos.
     */
    private val settingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            applyAccent(ThemeManager.accent(this)) // el fuego se lee al abrir el screensaver
            renderTelemetryMode()
            binding.mountains.setIdleAnimation(CockpitPrefs.idleAnimation(this))
            // OBD (activado, escáner o modo demo) pudo cambiar: reconectar con lo nuevo.
            ObdHub.refresh(this, restart = true)
            resetIdleTimer()
        }

    private fun openSettings() {
        settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
    }

    // ---------------------------------------------------------------- Tema en caliente

    /** Repinta todos los acentos con el color elegido, sin recrear la actividad. */
    private fun applyAccent(@ColorInt c: Int) {
        accent = c
        val list = ColorStateList.valueOf(c)
        with(binding) {
            // Rellenos con degradado fijos; solo los bordes neón (blancos) toman el color de cabina.
            listOf(artFrame, btnPrev, btnNext).forEach { it.backgroundTintList = list }
            listOf(mediaCard, infoTile, presetBox, bassBox, brandBox, btnPlay).forEach { it.foregroundTintList = list }
            quickViews.values.forEach { it.root.foregroundTintList = list }
            listOf(bottomFrame, infoPrev, infoNext, presetChevron).forEach { it.imageTintList = list }
            presetName.setTextColor(c)
            infoFuelBtn.setTextColor(c)
            infoFuelBtn.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(ThemeManager.withAlpha(c, 0x1A))
                setStroke((1.5f * resources.displayMetrics.density).toInt(), c)
                cornerRadius = 20 * resources.displayMetrics.density
            }
            mediaProgress.progressTintList = list
            brandLogo.setAccent(c)
            brandText.setShadowLayer(10f, 0f, 0f, ThemeManager.withAlpha(c, 0x99))
            mountains.setAccent(c)
        }
        refreshQuickIcons()
        renderWifi()
        refreshBluetooth()
        renderMute()
        renderPresets()
        rebuildInfoDots()
        renderInfo()
    }

    // ---------------------------------------------------------------- Perfil (celular y coche)

    /** Último perfil guardado en DataStore; Ajustes lo usa para precargar sus campos. */
    private var profile = Profile(null, null, null)

    /** La barra superior solo muestra lo guardado en Ajustes: nada de modelos ni marcas fijas. */
    private fun observeProfile() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ProfileStore.flow(this@MainActivity).collect { p ->
                    profile = p
                    renderTopStatus()
                    renderBrand(p.carBrand ?: getString(R.string.brand_default))
                }
            }
        }
    }

    // ---------------------------------------------------------------- Conexión con OfflineTune

    private val link: PhoneLinkViewModel by viewModels()
    private var remote = LinkUiState()
    private var linkConfig = LinkConfig()

    /** Toda la parte del celular se pinta desde el StateFlow del ViewModel. */
    private fun observeLink() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { LinkPrefs.flow(this@MainActivity).collect { linkConfig = it } }
                // El sonido del estéreo cambia sin que llegue un estado nuevo: se revisa el aviso.
                launch { com.iheii.veloradrive.link.AudioWatchdog.sound.collect { renderBtWarning() } }
                link.ui.collect { renderLink(it) }
            }
        }
    }

    private fun renderLink(s: LinkUiState) {
        val was = remote.connected
        remote = s
        renderTopStatus()
        if (s.connected) {
            // Con Android Auto el audio va por el estéreo: no hace falta la app de Música BT.
            if (!was && s.state?.androidAuto != true) wakeBluetoothAudio()
            renderRemote(s)
        } else {
            if (was) {
                hideProgress()
                binding.btWarning.visibility = View.GONE
            }
            if (showingLast()) {
                // Se cayó la conexión: se conserva lo último (título, artista, portada) y se avisa.
                renderLast(s)
            } else if (was) {
                // Sin estado guardado: vuelve lo que suene en el estéreo (si hay algo).
                renderMetadata(controller?.metadata)
                renderPlayback(controller?.playbackState)
            }
            if (was) renderPresets()
        }
        renderControlsEnabled()
        // La hora del celular acaba de llegar: el reloj se corrige sin esperar al siguiente minuto.
        if (s.connected != was) renderClock()
        if (currentInfoPage() == PAGE_PHONE) renderInfo()
    }

    /**
     * Muchos estéreos solo dejan sonar el Bluetooth con su app de Música BT activa; si no, mandan
     * "pausa" al celular cada segundo. Al conectarse OfflineTune se abre esa app un momento y se
     * vuelve a esta pantalla: el estéreo queda en el canal Bluetooth y la música suena.
     */
    private fun wakeBluetoothAudio() {
        if (!CockpitPrefs.btWake(this)) return
        // Una vez por rato: al volver a esta pantalla la conexión se rehace y no debe repetirse.
        val now = android.os.SystemClock.elapsedRealtime()
        if (btWokeAt != 0L && now - btWokeAt < BT_WAKE_COOLDOWN_MS) return
        btWokeAt = now
        val target = SlotStore.resolve(this, Slot.BT_MUSIC) ?: return
        launch(target.intent)
        val app = applicationContext
        // Handler propio: el de la actividad se vacía en onPause, justo cuando se abre la app BT.
        android.os.Handler(Looper.getMainLooper()).postDelayed({
            runCatching {
                app.startActivity(Intent(app, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
        }, BT_WAKE_RETURN_MS)
    }

    /** Arriba: nombre del celular y "CONEXIÓN MÓVIL DIRECTA" o "Esperando conexión...". */
    private fun renderTopStatus() {
        val st = remote.state
        binding.deviceName.text = profile.deviceName
            ?: st?.device?.takeIf { remote.connected }
            ?: getString(R.string.device_default)
        binding.mobileTab.setText(when {
            remote.connected -> R.string.mobile_direct
            remote.reconnecting -> R.string.link_reconnecting
            else -> R.string.link_waiting
        })
    }

    /** true con el celular caído y sin música local sonando: se muestra lo último guardado. */
    private fun showingLast() = remote.reconnecting && !localPlaying()

    private fun localPlaying(): Boolean {
        val st = controller?.playbackState?.state
        return st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING
    }

    /** Último estado conocido, congelado (sin progreso) y con "Reconectando...". */
    private fun renderLast(s: LinkUiState) {
        val st = s.state ?: return
        binding.mediaTitle.text = st.title.orEmpty()
        setMarquee(false)
        setMarquee(st.title != null)
        binding.mediaStatus.text = st.artist.orEmpty()
        binding.mediaSource.text = getString(R.string.source_line, st.source, getString(R.string.link_reconnecting))
        binding.mediaArt.clipToOutline = true
        if (s.art != null) binding.mediaArt.setImageBitmap(s.art) else binding.mediaArt.setImageResource(R.drawable.art_empty)
        binding.btWarning.visibility = View.GONE
        playing = false
        binding.mountains.setPlaying(false)
        hideProgress()
    }

    private fun renderRemote(s: LinkUiState) {
        val st = s.state ?: return
        binding.mediaTitle.text = st.title.orEmpty()
        setMarquee(false)
        setMarquee(st.title != null)
        binding.mediaStatus.text = st.artist.orEmpty()
        val status = getString(if (st.isPlaying) R.string.status_playing else R.string.status_paused)
        binding.mediaSource.text = getString(R.string.source_line, st.source, status)
        binding.mediaArt.clipToOutline = true
        if (s.art != null) binding.mediaArt.setImageBitmap(s.art) else binding.mediaArt.setImageResource(R.drawable.art_empty)
        // El audio sale del celular por Bluetooth: sin A2DP se avisa (salvo con Android Auto).
        renderBtWarning()
        playing = st.isPlaying
        binding.mountains.setPlaying(st.isPlaying)
        renderPresets()
        renderRemoteProgress()
    }

    /**
     * Solo se avisa si el audio de verdad no llega al estéreo: sin Bluetooth, sin Android Auto, sin
     * video sonando y sin sonido detectado en el estéreo.
     */
    private fun renderBtWarning() {
        val st = remote.state
        val show = remote.connected && st != null && !com.iheii.veloradrive.link.AudioWatchdog.audioReaches(st)
        binding.btWarning.visibility = if (show) View.VISIBLE else View.GONE
    }

    /** Progreso local: avanza cada segundo desde el último position_sec; se corrige con cada estado. */
    private val remoteTick = object : Runnable {
        override fun run() {
            renderRemoteProgress()
        }
    }

    private fun renderRemoteProgress() {
        handler.removeCallbacks(remoteTick)
        val s = remote
        val st = s.state
        if (!s.connected || st == null || st.durationSec <= 0) {
            hideProgress()
            return
        }
        val elapsed = if (st.isPlaying) (android.os.SystemClock.elapsedRealtime() - s.stateAt) / 1000 else 0
        val pos = (st.positionSec + elapsed).coerceIn(0, st.durationSec)
        listOf(binding.mediaPos, binding.mediaProgress, binding.mediaDur).forEach { it.visibility = View.VISIBLE }
        binding.mediaPos.text = formatTime(pos)
        binding.mediaDur.text = formatTime(st.durationSec)
        binding.mediaProgress.progress = (pos * 1000 / st.durationSec).toInt()
        if (st.isPlaying && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            handler.postDelayed(remoteTick, 1_000L)
        }
    }

    private fun hideProgress() {
        handler.removeCallbacks(remoteTick)
        listOf(binding.mediaPos, binding.mediaProgress, binding.mediaDur).forEach { it.visibility = View.GONE }
    }

    private fun formatTime(sec: Long): String =
        if (sec >= 3600) String.format(Locale.US, "%d:%02d:%02d", sec / 3600, sec % 3600 / 60, sec % 60)
        else String.format(Locale.US, "%d:%02d", sec / 60, sec % 60)

    /** Sin celular ni música local no hay nada que controlar: los botones se atenúan. */
    private fun renderControlsEnabled() {
        val on = remote.connected || remote.reconnecting || controller != null
        listOf(binding.btnPrev, binding.btnPlay, binding.btnNext).forEach {
            it.isEnabled = on
            it.alpha = if (on) 1f else 0.4f
        }
    }

    /** Volumen del celular (Media3, 0-100), no el del sistema del estéreo. */
    private fun showRemoteVolume() {
        val bar = SeekBar(this).apply {
            // Pista gruesa y perilla grande: fácil de arrastrar con las vibraciones del coche.
            progressDrawable = ContextCompat.getDrawable(this@MainActivity, R.drawable.seek_track)
            thumb = ContextCompat.getDrawable(this@MainActivity, R.drawable.seek_thumb)
            progressTintList = ColorStateList.valueOf(accent)
            splitTrack = false
            max = 100
            progress = remote.state?.volume ?: 100
            minimumHeight = (56 * resources.displayMetrics.density).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) link.setVolume(value)
                }
                override fun onStartTrackingTouch(sb: SeekBar) = Unit
                override fun onStopTrackingTouch(sb: SeekBar) = Unit
            })
        }
        val pad = (24 * resources.displayMetrics.density).toInt()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.link_volume_title)
            .setView(FrameLayout(this).apply { setPadding(pad, pad, pad, 0); addView(bar) })
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun renderBrand(brand: String) {
        binding.brandText.text = brand
        // Letras cromadas: degradado plateado vertical, como un emblema metálico.
        binding.brandText.post {
            val h = binding.brandText.height.toFloat()
            binding.brandText.paint.shader = android.graphics.LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(0xFFFFFFFF.toInt(), 0xFFC4CED6.toInt(), 0xFF8C98A3.toInt()),
                floatArrayOf(0f, 0.55f, 1f),
                android.graphics.Shader.TileMode.CLAMP,
            )
            binding.brandText.invalidate()
        }
        binding.brandLogo.brand = brand.uppercase()
    }

    // ---------------------------------------------------------------- Presets de audio

    private fun setupPresets() {
        binding.presetBox.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            openPresetPicker()
        }
        // Bass Boost se suma a cualquier preset: se enciende y apaga aparte.
        binding.bassBox.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            if (remote.connected) {
                link.setBass(remote.state?.bass != true)
                return@setOnClickListener
            }
            if (!EqPresets.setBass(this, !EqPresets.bassOn(this))) {
                Toast.makeText(this, R.string.preset_unsupported, Toast.LENGTH_LONG).show()
            }
            renderPresets()
        }
    }

    /** Con OfflineTune conectado manda el celular (set_preset); si no, el ecualizador del estéreo. */
    private fun openPresetPicker() {
        PresetPicker.show(
            activity = this,
            accent = accent,
            activeId = activePresetId(),
            linked = remote.connected,
            onPick = { id ->
                if (remote.connected) {
                    link.setPreset(id)
                } else if (!EqPresets.apply(this, id)) {
                    Toast.makeText(this, R.string.preset_unsupported, Toast.LENGTH_LONG).show()
                }
                renderPresets()
            },
            onStereoEq = { openSlot(Slot.EQ) },
            onPhone = { Toast.makeText(this, R.string.eq_phone_toast, Toast.LENGTH_LONG).show() },
        )
    }

    private fun activePresetId(): String? =
        if (remote.connected) remote.state?.preset else EqPresets.currentId(this)

    private fun renderPresets() {
        if (accent == 0) return
        val id = activePresetId()
        binding.presetName.text = EqPresets.byId(id)?.let { getString(it.label) } ?: when {
            id == "bass" -> getString(R.string.preset_legacy_bass) // OfflineTune viejo
            remote.connected -> getString(R.string.preset_none)
            else -> getString(R.string.eq_normal)
        }
        binding.presetBox.alpha = if (remote.connected || EqPresets.isSupported) 1f else 0.6f

        val on = if (remote.connected) remote.state?.bass == true else EqPresets.bassOn(this)
        binding.bassState.setText(if (on) R.string.bass_on else R.string.bass_off)
        binding.bassState.setTextColor(if (on) accent else DIM_TEXT)
        binding.bassTitle.setTextColor(if (on) 0xFFFFFFFF.toInt() else DIM_TEXT)
        val d = resources.displayMetrics.density
        // Interruptor: ON = pista en el color de cabina con halo y bolita blanca a la derecha;
        // OFF = pista gris con bolita gris a la izquierda.
        val knob = (20 * d).toInt()
        val pad = (3 * d).toInt()
        binding.bassLine.background = android.graphics.drawable.LayerDrawable(arrayOf(
            android.graphics.drawable.GradientDrawable().apply {
                setColor(if (on) ThemeManager.withAlpha(accent, 0x55) else 0xFF2A323A.toInt())
                cornerRadius = 13 * d
                setStroke((1.5f * d).toInt(), if (on) accent else 0xFF4A5560.toInt())
            },
            android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(if (on) 0xFFFFFFFF.toInt() else 0xFF7C8793.toInt())
                setSize(knob, knob)
            },
        )).apply {
            setLayerGravity(1, if (on) android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
                               else android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL)
            setLayerInset(1, pad, 0, pad, 0)
        }
        binding.bassBox.alpha = if (remote.connected || BassController.isSupported) 1f else 0.45f
    }

    // ---------------------------------------------------------------- Recuadro de info (viaje, gasolina, total, celular)

    private var infoPages = listOf(PAGE_TRIP)

    private fun setupInfoTile() {
        // Tocar en cualquier parte del recuadro pasa a la siguiente página (y da la vuelta).
        binding.infoTile.setOnClickListener { stepInfo(1) }
        // Mantener 1 s: la navegación en modo dividido, como hacía el recuadro de GPS.
        binding.infoTile.setOnHoldListener { openNavSplit() }
        binding.infoPrev.setOnClickListener { stepInfo(-1) }
        binding.infoNext.setOnClickListener { stepInfo(1) }
        // El botón no cambia de página: solo pregunta si se reinicia el conteo.
        binding.infoFuelBtn.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setMessage(R.string.info_fuel_confirm)
                .setPositiveButton(R.string.info_fuel_reset) { _, _ ->
                    TripTracker.resetFuel(this)
                    renderInfo()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    /** Ajustes puede cambiar qué páginas hay y en qué orden: se relee al volver. */
    private fun loadInfoPages() {
        val pages = CockpitPrefs.infoPages(this).map { it.trim() }.filter { it in KNOWN_PAGES }.ifEmpty { listOf(PAGE_TRIP) }
        if (pages != infoPages) {
            infoPages = pages
            infoIndex = infoIndex.coerceIn(0, pages.size - 1)
            rebuildInfoDots()
        }
    }

    private fun currentInfoPage() = infoPages.getOrNull(infoIndex)

    private fun stepInfo(delta: Int) {
        if (infoPages.size < 2) return
        binding.infoTile.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        infoIndex = (infoIndex + delta).mod(infoPages.size)
        renderInfo()
    }

    private fun rebuildInfoDots() {
        val box = binding.infoDots
        box.removeAllViews()
        if (infoPages.size < 2) return
        val d = resources.displayMetrics.density
        repeat(infoPages.size) { i ->
            box.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams((6 * d).toInt(), (6 * d).toInt()).apply {
                    if (i > 0) marginStart = (4 * d).toInt()
                }
            })
        }
    }

    private fun TextView.show(value: CharSequence) {
        if (text.toString() != value.toString()) text = value
    }

    private fun renderInfo() {
        if (accent == 0) return
        val page = currentInfoPage() ?: return
        val b = binding
        for (i in 0 until b.infoDots.childCount) {
            b.infoDots.getChildAt(i).background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(if (i == infoIndex) accent else 0xFF46515C.toInt())
            }
        }
        val single = infoPages.size < 2
        b.infoPrev.visibility = if (single) View.INVISIBLE else View.VISIBLE
        b.infoNext.visibility = if (single) View.INVISIBLE else View.VISIBLE

        fun body(stats: Boolean = false, phone: Boolean = false, message: String? = null) {
            b.infoStats.visibility = if (stats) View.VISIBLE else View.GONE
            b.infoPhone.visibility = if (phone) View.VISIBLE else View.GONE
            b.infoMessage.visibility = if (message != null) View.VISIBLE else View.GONE
            if (message != null) b.infoMessage.show(message)
        }
        fun cells(v1: String, l1: Int, v2: String, l2: Int, v3: String? = null, l3: Int = 0) {
            b.infoV1.show(v1); b.infoL1.show(getString(l1))
            b.infoV2.show(v2); b.infoL2.show(getString(l2))
            b.infoCell3.visibility = if (v3 != null) View.VISIBLE else View.INVISIBLE
            if (v3 != null) { b.infoV3.show(v3); b.infoL3.show(getString(l3)) }
        }
        val noGps = !TripTracker.hasPermission(this)
        b.infoFuelBtn.visibility = if (page == PAGE_FUEL) View.VISIBLE else View.GONE
        when (page) {
            PAGE_TRIP -> {
                val t = TripTracker.trip
                b.infoTitle.show(getString(R.string.info_trip_title))
                body(stats = true)
                val avg = if (t.movingMs >= 60_000L) (t.meters / 1000.0 / (t.movingMs / 3_600_000.0)).roundToInt().toString() else "—"
                cells(formatKm(t.meters), R.string.info_km, formatHm(t.movingMs), R.string.info_hours, avg, R.string.info_avg)
                b.infoFoot.show(getString(if (noGps) R.string.info_no_gps else R.string.info_trip_foot))
            }
            PAGE_FUEL -> {
                val f = TripTracker.fuel
                b.infoTitle.show(getString(R.string.info_fuel_title))
                body(stats = true)
                cells(formatKm(f.meters), R.string.info_km_driven, formatHm(f.movingMs), R.string.info_hours)
                val since = TripTracker.fuelSince(this)
                val days = ((VeloraClock.now() - since) / 86_400_000L).coerceAtLeast(0)
                b.infoFoot.show(when {
                    noGps -> getString(R.string.info_no_gps)
                    since == 0L -> getString(R.string.info_fuel_never)
                    days == 0L -> getString(R.string.info_fuel_today)
                    days == 1L -> getString(R.string.info_fuel_yesterday)
                    else -> getString(R.string.info_fuel_days, days.toInt())
                })
            }
            PAGE_TOTAL -> {
                val t = TripTracker.total
                b.infoTitle.show(getString(R.string.info_total_title))
                body(stats = true)
                cells(formatKm(t.meters), R.string.info_km, String.format(Locale.US, "%,d", t.movingMs / 3_600_000L), R.string.info_hours_driving)
                b.infoFoot.show(getString(if (noGps) R.string.info_no_gps else R.string.info_total_foot))
            }
            PAGE_PHONE -> renderPhonePage()
        }
    }

    /** Página "MI CELULAR": todo sale del CarState de OfflineTune Car. */
    private fun renderPhonePage() {
        val b = binding
        val st = remote.state
        b.infoStats.visibility = View.GONE
        if (!remote.connected || st == null) {
            b.infoTitle.show(getString(R.string.info_phone_title))
            b.infoPhone.visibility = View.GONE
            b.infoMessage.visibility = View.VISIBLE
            b.infoMessage.show(getString(R.string.info_phone_connect))
            b.infoFoot.show("")
            return
        }
        b.infoMessage.visibility = View.GONE
        b.infoPhone.visibility = View.VISIBLE
        b.infoTitle.show((st.device ?: profile.deviceName ?: getString(R.string.info_phone_title)).uppercase(spanish))
        val charging = st.charging == true
        b.infoBolt.visibility = if (charging) View.VISIBLE else View.GONE
        b.infoBattery.show(st.battery?.let { getString(R.string.info_phone_battery, it) } ?: "—")
        val net = listOfNotNull(st.network, st.carrier?.takeIf { it.isNotBlank() })
        b.infoNet.show(if (net.size == 2) getString(R.string.info_phone_net, net[0], net[1]) else net.firstOrNull().orEmpty())
        b.infoBars.visibility = if (st.signalLevel != null) View.VISIBLE else View.GONE
        b.infoBars.level = st.signalLevel ?: 0
        val data = st.dataTodayMb
        b.infoData.visibility = if (data != null) View.VISIBLE else View.GONE
        if (data != null) b.infoData.show(getString(R.string.info_phone_data, String.format(Locale.US, "%.1f", data / 1024.0)))
        b.infoFoot.show(when {
            !charging -> ""
            st.chargeMinutes != null -> getString(R.string.info_phone_charging_min, st.chargeMinutes)
            else -> getString(R.string.info_phone_charging)
        })
    }

    private fun formatKm(meters: Double): String {
        val km = meters / 1000.0
        return if (km < 100) String.format(Locale.US, "%.1f", km) else String.format(Locale.US, "%,d", km.toLong())
    }

    private fun formatHm(ms: Long): String {
        val min = ms / 60_000L
        return String.format(Locale.US, "%d:%02d", min / 60, min % 60)
    }

    // ---------------------------------------------------------------- Onda de audio

    /** El Visualizer solo se enciende mientras suena música, la pantalla se ve y hay permiso. */
    private fun updateSpectrum() {
        val allowed = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val visible = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !screensaver.isShowing
        if (playing && allowed && visible) spectrum.start() else spectrum.stop()
    }

    // ---------------------------------------------------------------- Screensaver

    private lateinit var screensaver: Screensaver

    private fun setupScreensaver() {
        val content = findViewById<FrameLayout>(android.R.id.content)
        val ss = ScreensaverBinding.inflate(layoutInflater, content, false)
        content.addView(ss.root)
        screensaver = Screensaver(ss) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun is24h() = android.text.format.DateFormat.is24HourFormat(this)

    private fun showScreensaver() {
        // Muchos estéreos solo admiten un Visualizer global: las montañas lo sueltan antes que el fuego lo pida.
        spectrum.stop()
        screensaver.setStatus(
            gpsFix = gpsFix,
            parked = shownSpeed < STOPPED_KMH,
            wifi = wifiNetworks.isNotEmpty(),
            bt = btConnected,
        )
        screensaver.show(is24h(), ThemeManager.fireColors(this))
        // El screensaver usa su propio Visualizer: las montañas sueltan el suyo.
        updateSpectrum()
    }

    /** Un solo postDelayed que se reprograma con cada toque: sin sondeos ni temporizadores repetidos. */
    private val idleTimeout = Runnable { showScreensaver() }

    private fun resetIdleTimer() {
        handler.removeCallbacks(idleTimeout)
        val minutes = CockpitPrefs.idleMinutes(this)
        if (minutes > 0 && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            handler.postDelayed(idleTimeout, minutes * 60_000L)
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            resetIdleTimer()
            // El toque cierra el screensaver: al terminar, las montañas recuperan su Visualizer.
            if (screensaver.isShowing) handler.postDelayed({ updateSpectrum() }, 300)
        }
        return super.dispatchTouchEvent(ev)
    }

    // ---------------------------------------------------------------- Accesos rápidos (izquierda)

    private fun setupQuickList() {
        val gap = (10 * resources.displayMetrics.density).toInt()
        fun add(i: Int): ItemQuickBinding {
            val b = ItemQuickBinding.inflate(layoutInflater, binding.quickList, false)
            // Los botones se reparten la altura de la columna: grandes en cualquier pantalla.
            b.root.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = if (i > 0) gap else 0
            }
            binding.quickList.addView(b.root)
            return b
        }
        quickSlots.forEachIndexed { i, slot ->
            val b = add(i)
            b.title.setText(slot.title)
            b.root.setOnClickListener { openSlot(slot) }
            b.root.setOnHoldListener { AppPicker.pickForSlot(this, slot) { refreshSlots() } }
            quickViews[slot] = b
        }
    }

    private fun refreshSlots() {
        quickViews.forEach { (slot, b) ->
            // Debajo del título, la app asignada si el usuario cambió la predeterminada.
            val custom = SlotStore.assigned(this, slot)?.let { SlotStore.resolve(this, slot) }
            b.subtitle.text = custom?.label.orEmpty()
            b.subtitle.visibility = if (custom != null) View.VISIBLE else View.GONE
        }
        refreshQuickIcons()
        projectionTarget = SlotStore.resolve(this, Slot.PROJECTION)
    }

    /**
     * Modo activo de la columna (VIDEO, MÚSICA o RADIO): fondo y borde en el color de cabina.
     * Se marca al abrirlo desde el launcher o cuando la app que suena es la de ese botón.
     */
    private fun setActiveSlot(slot: Slot?) {
        if (slot == activeSlot) return
        activeSlot = slot
        renderActiveSlot()
    }

    private fun renderActiveSlot() {
        quickViews.forEach { (slot, b) ->
            val on = slot == activeSlot
            b.root.foreground = ContextCompat.getDrawable(this, if (on) R.drawable.btn_glossy_active else R.drawable.btn_glossy_glow)
            b.root.foregroundTintList = ColorStateList.valueOf(accent)
            b.root.isSelected = on
        }
    }

    /** Si la sesión de medios pertenece a la app de un botón, ese botón queda activo. */
    private fun syncActiveWithMedia(pkg: String?) {
        if (pkg == null) return
        MODE_SLOTS.firstOrNull { SlotStore.resolve(this, it)?.intent?.let { i -> i.component?.packageName ?: i.`package` } == pkg }
            ?.let { setActiveSlot(it) }
    }

    private fun refreshQuickIcons() {
        // Íconos en el color de cabina sobre un halo del mismo color.
        quickViews.forEach { (slot, b) ->
            b.icon.setImageResource(if (slot == Slot.APPS) R.drawable.ic_apps else slot.icon)
            tint(b.icon, ThemeManager.lighten(accent, 0.35f))
        }
        // "CAR SYNC / " en blanco y "START" en el color de cabina, como en el diseño.
        val label = getString(R.string.car_sync_line)
        val start = label.lastIndexOf(' ') + 1
        binding.startLabel.text = android.text.SpannableString(label).apply {
            setSpan(android.text.style.ForegroundColorSpan(ThemeManager.lighten(accent, 0.25f)), start, label.length, 0)
        }
        renderActiveSlot()
    }

    private fun openSlot(slot: Slot) {
        // Con el celular conectado, VIDEO abre los videos de OfflineTune Car (salvo que el usuario
        // haya asignado otra app al botón). La app de video del estéreo sigue en APPS.
        if (slot == Slot.VIDEO && remote.connected && linkConfig.token != null && SlotStore.assigned(this, slot) == null) {
            setActiveSlot(slot)
            launch(com.iheii.veloradrive.video.VideoLibraryActivity.intent(this))
            return
        }
        val target = if (slot == Slot.PROJECTION) projectionTarget else SlotStore.resolve(this, slot)
        if (target == null) {
            if (slot == Slot.PROJECTION) {
                Toast.makeText(this, R.string.projection_toast, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, R.string.not_found, Toast.LENGTH_SHORT).show()
                AppPicker.pickForSlot(this, slot) { refreshSlots() }
            }
            return
        }
        if (slot in MODE_SLOTS) setActiveSlot(slot)
        // La app de enlace necesita que el MCU ya esté en el canal de Android.
        if (slot == Slot.PROJECTION) {
            showPlayerOverProjection()
            unmuteForProjection()
            // Si Android Auto ya suena, el truco del canal le quitaría el audio a la app de enlace.
            if (projectionAlreadyPlaying()) launch(target.intent)
            else AudioChannelKick.launchWithAudio(this) { launch(target.intent) }
        } else {
            launch(target.intent)
        }
    }

    private fun projectionAlreadyPlaying(): Boolean =
        remote.state?.androidAuto == true || (::audio.isInitialized && audio.isMusicActive)

    /** Android Auto suena por el volumen de música del estéreo: si está en silencio o en 0, se abre. */
    private fun unmuteForProjection() {
        if (!::audio.isInitialized) return
        runCatching {
            if (audio.isStreamMute(stream)) audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
            if (audio.getStreamVolume(stream) == 0) {
                audio.setStreamVolume(stream, (audio.getStreamMaxVolume(stream) / 3).coerceAtLeast(1), 0)
            }
        }
        renderMute()
    }

    private fun launch(intent: Intent) {
        try {
            if (intent.component?.packageName != packageName) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.not_found, Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, R.string.not_found, Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------- Reloj

    /** Cambios de hora o zona del sistema. */
    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = clockTick.run()
    }

    private var clockRunning = false

    /**
     * Un solo postDelayed al siguiente cambio de minuto de VeloraClock (que puede ir desfasado del
     * reloj del sistema si se usa la hora del celular). Nada corre con la pantalla tapada.
     */
    private val clockTick = Runnable {
        renderClock()
        if (screensaver.isShowing) screensaver.updateClock(is24h())
    }

    /** Hora grande de la barra superior (VeloraClock: hora y zona del celular si está activado). */
    private fun renderClock() {
        val nowMs = VeloraClock.now()
        val zone = VeloraClock.zone()
        val now = Date(nowMs)
        val is24 = is24h()
        binding.clock.text = SimpleDateFormat(if (is24) "HH:mm" else "h:mm", spanish).apply { timeZone = zone }.format(now)
        binding.clockAmPm.text = if (is24) "" else SimpleDateFormat("a", Locale.US).apply { timeZone = zone }.format(now)
        binding.clockAmPm.visibility = if (is24) View.GONE else View.VISIBLE
        handler.removeCallbacks(clockTick)
        if (clockRunning) handler.postDelayed(clockTick, 60_000L - nowMs % 60_000L + 50L)
    }

    private fun startClock() {
        clockRunning = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(this, timeReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun stopClock() {
        clockRunning = false
        handler.removeCallbacks(clockTick)
        runCatching { unregisterReceiver(timeReceiver) }
    }

    // ---------------------------------------------------------------- Multimedia

    private var sessionManager: MediaSessionManager? = null
    private var controller: MediaController? = null
    private val listenerComponent by lazy { ComponentName(this, MediaListenerService::class.java) }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { sessions ->
        bindController(pickController(sessions.orEmpty()))
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = renderMetadata(metadata)
        override fun onPlaybackStateChanged(state: PlaybackState?) = renderPlayback(state)
        override fun onSessionDestroyed() = bindController(null)
    }

    private fun hasMediaAccess() =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun pickController(sessions: List<MediaController>): MediaController? =
        sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull { it.metadata != null }
            ?: sessions.firstOrNull()

    private fun setupMedia() {
        binding.mediaBox.setOnClickListener {
            val c = controller
            when {
                !hasMediaAccess() -> launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                c != null -> openMediaApp(c)
                else -> openSlot(Slot.MUSIC)
            }
        }
    }

    private fun sendMediaKey(code: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun openMediaApp(c: MediaController) {
        val pending = c.sessionActivity
        if (pending != null && runCatching { pending.send() }.isSuccess) return
        packageManager.getLaunchIntentForPackage(c.packageName)?.let { launch(it) }
    }

    private fun startMedia() {
        if (!hasMediaAccess()) {
            bindController(null)
            binding.mediaTitle.setText(R.string.media_access_title)
            binding.mediaStatus.setText(R.string.media_access_artist)
            binding.mediaSource.text = ""
            return
        }
        val sm = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        sessionManager = sm
        try {
            sm.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, handler)
            bindController(pickController(sm.getActiveSessions(listenerComponent)))
        } catch (_: SecurityException) {
            bindController(null)
        }
    }

    private fun stopMedia() {
        sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
        sessionManager = null
        controller?.unregisterCallback(controllerCallback)
        controller = null
    }

    private fun bindController(next: MediaController?) {
        if (next != null && controller?.sessionToken == next.sessionToken) return
        controller?.unregisterCallback(controllerCallback)
        controller = next
        next?.registerCallback(controllerCallback, handler)
        renderMetadata(next?.metadata)
        renderPlayback(next?.playbackState)
    }

    private fun renderMetadata(meta: MediaMetadata?) {
        if (remote.connected || showingLast()) return
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        // Todo sale de la canción real; sin canción no se inventa texto.
        if (title != null || hasMediaAccess()) binding.mediaTitle.text = title.orEmpty()
        // Título largo: marquesina continua de derecha a izquierda.
        setMarquee(false)
        setMarquee(title != null)

        val art: Bitmap? = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        binding.mediaArt.clipToOutline = true
        if (art != null) binding.mediaArt.setImageBitmap(art) else binding.mediaArt.setImageResource(R.drawable.art_empty)
    }

    private fun setMarquee(on: Boolean) {
        binding.mediaTitle.isSelected = on
    }

    /** Nombre de la app que suena (OfflineTune, Spotify...), para la línea de origen. */
    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun renderPlayback(state: PlaybackState?) {
        renderControlsEnabled()
        if (remote.connected || showingLast()) return
        playing = state?.state == PlaybackState.STATE_PLAYING ||
            state?.state == PlaybackState.STATE_BUFFERING
        val artist = controller?.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
        if (hasMediaAccess()) {
            val status = when {
                playing -> getString(R.string.status_playing)
                state?.state == PlaybackState.STATE_PAUSED -> getString(R.string.status_paused)
                else -> getString(R.string.status_idle)
            }
            // Artista debajo del título; abajo, la app de origen y su estado ("OfflineTune • En reproducción").
            binding.mediaStatus.text = artist.orEmpty()
            val c = controller
            binding.mediaSource.text = if (c == null) status else getString(R.string.source_line, appLabel(c.packageName), status)
        }
        if (playing) syncActiveWithMedia(controller?.packageName)
        binding.mountains.setPlaying(playing)
        updateSpectrum()
    }

    // ---------------------------------------------------------------- Audio: volumen, bajos, DSP, Mute

    private val stream = AudioManager.STREAM_MUSIC

    /** El ícono de mute sigue a los botones físicos del volante o del estéreo. */
    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = renderMute()
    }

    private fun renderMute() {
        if (!::audio.isInitialized) return
        val muted = audio.isStreamMute(stream) || audio.getStreamVolume(stream) == 0
        // Bocina tachada en rojo = silencio; bocina con ondas en blanco = suena.
        binding.btnMute.setImageResource(if (muted) R.drawable.ic_vol_off else R.drawable.ic_vol_up)
        binding.btnMute.imageTintList = android.content.res.ColorStateList.valueOf(if (muted) MUTED_RED else 0xFFFFFFFF.toInt())
        binding.btnMute.alpha = 1f
    }

    // ---------------------------------------------------------------- Barra inferior

    private fun setupBottomBar() {
        binding.btnProjection.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            openSlot(Slot.PROJECTION)
        }
        binding.btnProjection.setOnHoldListener { AppPicker.pickForSlot(this, Slot.PROJECTION) { refreshSlots() } }
        binding.btnPlay.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            val c = controller
            if (remote.connected) {
                link.toggle()
            } else if (c == null) {
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            } else if (c.playbackState?.state == PlaybackState.STATE_PLAYING) {
                c.transportControls.pause()
            } else {
                c.transportControls.play()
            }
        }
        binding.btnNext.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            if (remote.connected) link.next()
            else controller?.transportControls?.skipToNext() ?: sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
        }
        binding.btnPrev.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            if (remote.connected) link.previous()
            else controller?.transportControls?.skipToPrevious() ?: sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        }
        binding.btnMute.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            runCatching { audio.adjustStreamVolume(stream, AudioManager.ADJUST_TOGGLE_MUTE, 0) }
            renderMute()
        }
        // Volumen: con el celular, el de OfflineTune; si no, el control de volumen del sistema.
        binding.btnVolume.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            if (remote.connected) {
                showRemoteVolume()
                return@setOnClickListener
            }
            runCatching { audio.adjustStreamVolume(stream, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI) }
        }
        binding.btnSettings.setOnClickListener { openSettings() }
    }

    private fun setupStatusToggles() {
        binding.wifiIcon.setOnClickListener {
            launch(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Intent(Settings.Panel.ACTION_WIFI)
                else Intent(Settings.ACTION_WIFI_SETTINGS),
            )
        }
        binding.btIcon.setOnClickListener { onBluetoothTapped() }
    }

    /**
     * Android 12+: sin BLUETOOTH_CONNECT cualquier llamada al adaptador lanza SecurityException, así
     * que primero se pide el permiso. Android 13+ ya no deja a las apps encender el Bluetooth por su
     * cuenta: se abre el panel de Bluetooth del sistema. En versiones viejas se pide encenderlo.
     */
    private fun onBluetoothTapped() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            if (shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT) || !btAsked) {
                btAsked = true
                permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
            } else {
                launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            return
        }
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val enabled = try { adapter?.isEnabled == true } catch (_: SecurityException) { false }
        // Apagado: pide encenderlo. Encendido: abre la lista de dispositivos.
        if (enabled || !tryStart(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))) {
            launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
    }

    private var btAsked = false

    private fun tryStart(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }

    // ---------------------------------------------------------------- Telemetría (GPS / OBD2)

    /**
     * Lectura que muestra la tarjeta. La conexión vive en [ObdHub] (compartida con ObdActivity);
     * en demo la velocidad no se usa para el screensaver ni el "estacionado".
     */
    private var obdReading = ObdReading()
    private var obdAcquired = false
    private var obdShownLive: Boolean? = null

    private fun setupObdCard() {
        binding.obdCard.setOnClickListener { launch(Intent(this, ObdActivity::class.java)) }
        binding.obdCard.setOnHoldListener { openSettings() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { ObdHub.readings.collect { onObd(it) } }
        }
    }

    private fun startObd() {
        if (!obdAcquired) {
            obdAcquired = true
            ObdHub.acquire(this)
        } else {
            // Por ejemplo: se acaba de dar el permiso de Bluetooth.
            ObdHub.refresh(this)
        }
    }

    private fun stopObd() {
        if (!obdAcquired) return
        obdAcquired = false
        ObdHub.release(this)
    }

    private fun onObd(r: ObdReading) {
        obdReading = r
        if (r.status == ObdStatus.LIVE) r.speedKmh?.let { showSpeed(it.toFloat()) }
        renderTelemetry()
    }

    /** Sin OBD2 se descarta cualquier lectura vieja: nunca se muestran datos falsos. */
    private fun renderTelemetryMode() {
        obdReading = ObdHub.readings.value
        renderTelemetry()
    }

    /** Velocidad del auto por OBD2 (null en demo o sin lector: manda el GPS). */
    private val obdSpeed get() = obdReading.speedKmh?.takeIf { obdReading.status == ObdStatus.LIVE }

    /** Tarjeta OBD2: gris "Esperando conexión..." o, con datos, borde verde y 4 valores. */
    private fun renderTelemetry() {
        val r = obdReading
        val live = r.connected
        if (live != obdShownLive) {
            obdShownLive = live
            binding.obdIdle.visibility = if (live) View.GONE else View.VISIBLE
            binding.obdLive.visibility = if (live) View.VISIBLE else View.GONE
            binding.obdCard.setBackgroundResource(if (live) R.drawable.obd_card_live else R.drawable.obd_card)
        }
        if (!live) return
        binding.obdLiveState.showText(getString(if (r.status == ObdStatus.DEMO) R.string.obd_card_demo else R.string.obd_card_connected))
        binding.obdRpm.showText(ObdFormat.int(r.rpm))
        binding.obdSpeed.showText(ObdFormat.int(r.speedKmh))
        binding.obdCoolant.showText(ObdFormat.int(r.coolantC))
        binding.obdVolts.showText(ObdFormat.volts(r.volts))
    }

    private var lastFix: Location? = null
    private var smoothedKmh = 0f
    private var shownSpeed = 0f
    private var gpsFix = false
    private var gpsActive = false

    /**
     * El GPS lo escucha solo TripTracker (también para TripService en segundo plano). Con la
     * pantalla a la vista pide fixes cada segundo y nos pasa cada uno para el velocímetro.
     */
    private val tripListener = object : TripTracker.Listener {
        override fun onFix(loc: Location) = onLocation(loc)
        override fun onProviderDisabled() = setGpsFix(false)
        override fun onStats() {
            if (currentInfoPage() in STAT_PAGES) renderInfo()
        }
    }

    /** Sin fixes durante unos segundos: la velocidad vuelve a 0 y el ícono a "buscando". */
    private val gpsTimeout = Runnable {
        smoothedKmh = 0f
        lastFix = null
        if (obdSpeed == null) showSpeed(0f)
        setGpsFix(false)
    }

    private fun setGpsFix(fix: Boolean) {
        gpsFix = fix
        val enabled = runCatching {
            (getSystemService(LOCATION_SERVICE) as LocationManager).isProviderEnabled(LocationManager.GPS_PROVIDER)
        }.getOrDefault(false)
        if (!enabled) gpsFix = false
    }

    private fun hasLocationPermission() = TripTracker.hasPermission(this)

    private fun startGps() {
        if (gpsActive || !hasLocationPermission()) return
        gpsActive = true
        setGpsFix(false)
        TripTracker.addListener(tripListener)
        TripTracker.acquire(this, fast = true)
        // Sigue contando los km cuando Android Auto u otra app tape a Velora.
        TripService.start(this)
    }

    private fun stopGps() {
        if (!gpsActive) return
        gpsActive = false
        TripTracker.removeListener(tripListener)
        TripTracker.release(this, fast = true)
        lastFix = null
        smoothedKmh = 0f
    }

    private fun onLocation(loc: Location) {
        handler.removeCallbacks(gpsTimeout)
        handler.postDelayed(gpsTimeout, 4_000)
        if (!gpsFix) setGpsFix(true)

        // Fixes imprecisos (túneles, edificios) generan picos falsos de velocidad.
        if (loc.hasAccuracy() && loc.accuracy > MAX_ACCURACY_M) return

        val prev = lastFix
        lastFix = loc
        val raw = when {
            loc.hasSpeed() -> loc.speed * 3.6f
            prev != null && loc.time > prev.time ->
                prev.distanceTo(loc) / ((loc.time - prev.time) / 1000f) * 3.6f
            else -> return
        }
        // Media móvil exponencial: suaviza el ruido sin retrasar mucho la lectura.
        smoothedKmh = if (smoothedKmh == 0f) raw else smoothedKmh + SMOOTHING * (raw - smoothedKmh)
        val moving = smoothedKmh >= STOPPED_KMH
        // Con OBD2 conectado manda la velocidad del auto; si no, la del GPS.
        if (obdSpeed == null) showSpeed(if (moving) smoothedKmh else 0f)
    }

    /** Velocidad actual (GPS u OBD2); parado (< 2 km/h) cuenta como 0. El screensaver la usa. */
    private fun showSpeed(kmh: Float) {
        shownSpeed = if (kmh < STOPPED_KMH) 0f else kmh
    }

    // ---------------------------------------------------------------- Wi-Fi / Bluetooth / GPS on-off

    private var connectivity: ConnectivityManager? = null
    private val wifiNetworks = mutableSetOf<Network>()
    private var btConnected = false

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handler.post { wifiNetworks += network; renderWifi() }
        }
        override fun onLost(network: Network) {
            handler.post { wifiNetworks -= network; renderWifi() }
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshBluetooth()
            renderWifi()
            setGpsFix(gpsFix)
        }
    }

    private fun startConnectivity() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivity = cm
        wifiNetworks.clear()
        renderWifi()
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        runCatching { cm.registerNetworkCallback(request, wifiCallback) }

        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
            // Encendido/apagado de Wi-Fi y GPS desde el sistema: atenúa o ilumina los toggles.
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(this, stateReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        ContextCompat.registerReceiver(
            this, volumeReceiver, IntentFilter(VOLUME_CHANGED_ACTION), ContextCompat.RECEIVER_EXPORTED,
        )
        refreshBluetooth()
    }

    private fun stopConnectivity() {
        connectivity?.let { cm -> runCatching { cm.unregisterNetworkCallback(wifiCallback) } }
        connectivity = null
        runCatching { unregisterReceiver(stateReceiver) }
        runCatching { unregisterReceiver(volumeReceiver) }
    }

    private fun renderWifi() {
        if (accent == 0) return
        val connected = wifiNetworks.isNotEmpty()
        val enabled = runCatching {
            (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).isWifiEnabled
        }.getOrDefault(connected)
        binding.wifiIcon.setImageResource(if (enabled) R.drawable.ic_wifi else R.drawable.ic_wifi_off)
        tint(binding.wifiIcon, when {
            connected -> accent
            enabled -> color(R.color.text_dim)
            else -> color(R.color.text_faint)
        })
    }

    @SuppressLint("MissingPermission")
    private fun refreshBluetooth() {
        if (accent == 0) return
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val (connected, enabled) = if (adapter == null) {
            false to false
        } else {
            try {
                val audioConnected = listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET).any {
                    adapter.getProfileConnectionState(it) == BluetoothProfile.STATE_CONNECTED
                }
                audioConnected to adapter.isEnabled
            } catch (_: SecurityException) {
                false to false
            }
        }
        btConnected = connected
        binding.btIcon.setImageResource(if (connected) R.drawable.ic_bt_connected else R.drawable.ic_bt)
        tint(binding.btIcon, when {
            connected -> accent
            enabled -> color(R.color.text_dim)
            else -> color(R.color.text_faint)
        })
    }

    companion object {
        private const val MUTED_RED = 0xFFFF3B4A.toInt()
        /** Botones de la columna que representan un modo (APPS es solo un cajón). */
        private val MODE_SLOTS = listOf(Slot.VIDEO, Slot.MUSIC, Slot.RADIO)
        /** Modo activo: vive con el proceso para sobrevivir a recreaciones de la actividad. */
        private var activeSlot: Slot? = null
        private const val STOPPED_KMH = 2f
        private const val BT_WAKE_RETURN_MS = 2_500L
        private const val BT_WAKE_COOLDOWN_MS = 10 * 60_000L
        /** Cuándo se abrió por última vez la Música BT (vive con el proceso). */
        private var btWokeAt = 0L
        private const val MAX_ACCURACY_M = 30f
        private const val SMOOTHING = 0.5f
        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        private const val DIM_TEXT = 0xFF8A97A4.toInt()

        private const val PAGE_TRIP = "trip"
        private const val PAGE_FUEL = "fuel"
        private const val PAGE_TOTAL = "total"
        private const val PAGE_PHONE = "phone"
        private val KNOWN_PAGES = setOf(PAGE_TRIP, PAGE_FUEL, PAGE_TOTAL, PAGE_PHONE)
        private val STAT_PAGES = setOf(PAGE_TRIP, PAGE_FUEL, PAGE_TOTAL)
        /** Página del recuadro de info: vive con el proceso. */
        private var infoIndex = 0
    }
}
