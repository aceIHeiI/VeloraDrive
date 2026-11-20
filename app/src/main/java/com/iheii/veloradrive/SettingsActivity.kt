package com.iheii.veloradrive

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputFilter
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.format.DateFormat
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.iheii.veloradrive.databinding.ActivitySettingsBinding
import com.iheii.veloradrive.databinding.ItemSettingsNavBinding
import com.iheii.veloradrive.databinding.ItemSettingsRowBinding
import com.iheii.veloradrive.link.AudioWatchdog
import com.iheii.veloradrive.link.LinkConfig
import com.iheii.veloradrive.link.LinkPrefs
import com.iheii.veloradrive.link.LinkStatus
import com.iheii.veloradrive.link.LinkUiState
import com.iheii.veloradrive.link.PhoneLinkHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ajustes a pantalla completa (reemplaza al antiguo diálogo). Cada cambio se guarda al momento; al
 * volver, MainActivity recibe el resultado y repinta lo que dependa de estos ajustes.
 */
class SettingsActivity : LocaleActivity() {

    enum class Section(@param:StringRes val title: Int, @param:DrawableRes val icon: Int) {
        PHONE(R.string.aj_sec_phone, R.drawable.ic_set_phone),
        SOUND(R.string.aj_sec_sound, R.drawable.ic_vol_up),
        SCREEN(R.string.aj_sec_screen, R.drawable.ic_set_palette),
        BUTTONS(R.string.aj_sec_buttons, R.drawable.ic_apps),
        OBD(R.string.aj_sec_obd, R.drawable.ic_obd),
        VIDEO(R.string.aj_sec_video, R.drawable.ic_video),
        DIAG(R.string.aj_sec_diag, R.drawable.ic_set_bug),
    }

    private lateinit var binding: ActivitySettingsBinding
    private val d by lazy { resources.displayMetrics.density }
    private fun px(v: Int) = (v * d).toInt()

    @ColorInt private var accent = 0
    private val textColor by lazy { ContextCompat.getColor(this, R.color.text) }
    private val dim by lazy { ContextCompat.getColor(this, R.color.text_dim) }
    private val ok by lazy { ContextCompat.getColor(this, R.color.ok) }

    private var section = Section.PHONE
    private val navRows = mutableMapOf<Section, ItemSettingsNavBinding>()

    private var profile = Profile(null, null, null)
    private var linkConfig = LinkConfig()
    private var linkUi = LinkUiState()
    private var loaded = false

    /** Vistas que se actualizan en vivo mientras su sección está a la vista. */
    private var statusCard: ItemSettingsRowBinding? = null
    private var debugView: TextView? = null

    override fun attachBaseContext(base: Context) = super.attachBaseContext(base.designScaled())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        goImmersive()
        // MainActivity repinta siempre al volver (color, OBD, animación...).
        setResult(RESULT_OK)

        accent = ThemeManager.accent(this)
        section = savedInstanceState?.getString(KEY_SECTION)
            ?.let { s -> Section.entries.firstOrNull { it.name == s } } ?: Section.PHONE

        binding.btnBack.setOnClickListener { finish() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finish()
        })
        buildNav()
        applyChrome()

        lifecycleScope.launch {
            profile = ProfileStore.flow(this@SettingsActivity).first()
            linkConfig = LinkPrefs.flow(this@SettingsActivity).first()
            loaded = true
            showSection(section)
        }
        // Estado del celular y reloj: solo mientras la pantalla está a la vista.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    PhoneLinkHub.ui(this@SettingsActivity).collect {
                        linkUi = it
                        renderStatusCard()
                    }
                }
                while (isActive) {
                    renderClock()
                    debugView?.let { v ->
                        val text = debugText(linkUi)
                        if (v.text.toString() != text) v.text = text
                    }
                    delay(1_000)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // La pantalla principal suelta la conexión al pausarse; aquí se mantiene viva para ver el estado.
        PhoneLinkHub.acquire(this)
    }

    override fun onStop() {
        PhoneLinkHub.release(this)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        goImmersive()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SECTION, section.name)
    }

    // ---------------------------------------------------------------- estructura

    private fun buildNav() {
        binding.nav.removeAllViews()
        navRows.clear()
        Section.entries.forEach { s ->
            val b = ItemSettingsNavBinding.inflate(layoutInflater, binding.nav, false)
            b.icon.setImageResource(s.icon)
            b.label.setText(s.title)
            b.root.setOnClickListener { if (loaded) showSection(s) }
            binding.nav.addView(b.root)
            navRows[s] = b
        }
    }

    /** Colores que dependen del color de cabina (se repinta al cambiarlo). */
    private fun applyChrome() {
        binding.btnBack.background = panel(fill = 0x00000000, stroke = accent, radius = 12, strokeDp = 2)
        binding.btnBack.imageTintList = ColorStateList.valueOf(accent)
        binding.clock.background = panel(fill = PANEL, stroke = HAIRLINE, radius = 14)
        navRows.forEach { (s, b) ->
            val on = s == section
            b.root.background = if (on) panel(ThemeManager.withAlpha(accent, 0x26), accent, 12, 2) else null
            b.icon.imageTintList = ColorStateList.valueOf(if (on) accent else dim)
            b.label.setTextColor(if (on) accent else NAV_TEXT)
        }
    }

    private fun showSection(s: Section, keepScroll: Boolean = false) {
        val scrollY = if (keepScroll && s == section) binding.scroll.scrollY else 0
        section = s
        applyChrome()
        statusCard = null
        debugView = null
        binding.content.removeAllViews()
        when (s) {
            Section.PHONE -> buildPhone()
            Section.SOUND -> buildSound()
            Section.SCREEN -> buildScreen()
            Section.BUTTONS -> buildButtons()
            Section.OBD -> buildObd()
            Section.VIDEO -> buildVideo()
            Section.DIAG -> buildDiag()
        }
        binding.scroll.post { binding.scroll.scrollTo(0, scrollY) }
    }

    private fun refresh() = showSection(section, keepScroll = true)

    // ---------------------------------------------------------------- Celular

    private fun buildPhone() {
        statusCard = row(R.string.aj_status_disconnected, "").also { card ->
            card.icon.visibility = View.VISIBLE
            card.end.addView(TextView(this).apply {
                setText(R.string.aj_details)
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(NAV_TEXT)
            })
            card.end.addView(chevron())
            card.main.setOnClickListener { showSection(Section.DIAG) }
        }
        renderStatusCard()

        // Código del celular (4 dígitos los antiguos, 6 los nuevos): casillas con puntos (nunca se muestra el número).
        row(R.string.aj_code, getString(R.string.aj_code_desc)).apply {
            val digits = linkConfig.token.orEmpty().length
            // Con un código antiguo de 4 dígitos se dibujan 4 casillas; sin código o con uno nuevo, 6.
            val boxes = if (digits in 1..4) 4 else 6
            repeat(boxes) { i ->
                end.addView(TextView(this@SettingsActivity).apply {
                    text = if (i < digits) "•" else ""
                    textSize = 22f
                    gravity = Gravity.CENTER
                    setTextColor(textColor)
                    background = panel(PANEL_DEEP, HAIRLINE, 8)
                }, LinearLayout.LayoutParams(px(46), px(52)).apply { marginStart = px(8) })
            }
            main.setOnClickListener {
                editText(R.string.aj_code, "", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, 6) {
                    saveLink(linkConfig.copy(token = it))
                }
            }
        }
        valueRow(R.string.aj_ssid, linkConfig.ssid ?: getString(R.string.aj_not_set), getString(R.string.aj_ssid_desc)) {
            editText(R.string.link_ssid, linkConfig.ssid.orEmpty(), InputType.TYPE_CLASS_TEXT) {
                saveLink(linkConfig.copy(ssid = it))
            }
        }
        valueRow(
            R.string.aj_password,
            getString(if (linkConfig.password.isNullOrEmpty()) R.string.aj_not_set else R.string.aj_set),
            null,
        ) {
            editText(R.string.link_password, linkConfig.password.orEmpty(),
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) {
                saveLink(linkConfig.copy(password = it))
            }
        }
        valueRow(R.string.aj_host, linkConfig.host ?: "--", getString(R.string.aj_host_desc)) {
            editText(R.string.link_host, linkConfig.host.orEmpty(), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI) {
                saveLink(linkConfig.copy(host = it))
            }
        }
        switchRow(R.string.audio_fix, getString(R.string.aj_audio_fix_desc), CockpitPrefs.audioFix(this)) {
            CockpitPrefs.setAudioFix(this, it)
        }
        switchRow(R.string.aj_overlay, getString(R.string.aj_overlay_desc), CockpitPrefs.overlayOnProjection(this)) {
            CockpitPrefs.setOverlayOnProjection(this, it)
        }
        switchRow(R.string.bt_wake, getString(R.string.aj_bt_wake_desc), CockpitPrefs.btWake(this)) {
            CockpitPrefs.setBtWake(this, it)
        }
        slotRow(Slot.BT_MUSIC, R.string.bt_wake_pick)
        switchRow(R.string.aj_phone_time, getString(R.string.aj_phone_time_desc), CockpitPrefs.phoneTime(this)) {
            CockpitPrefs.setPhoneTime(this, it)
        }
    }

    private fun renderStatusCard() {
        val card = statusCard ?: return
        val s = linkUi
        val connected = s.connected
        val title = when {
            connected -> getString(R.string.aj_status_connected,
                (s.state?.device ?: profile.deviceName ?: getString(R.string.aj_status_phone)).uppercase())
            s.status == LinkStatus.CONNECTING -> getString(R.string.aj_status_connecting)
            s.status == LinkStatus.NO_TOKEN -> getString(R.string.aj_status_no_token)
            s.status == LinkStatus.BAD_TOKEN -> getString(R.string.aj_status_bad_token)
            s.status == LinkStatus.NO_WIFI -> getString(R.string.aj_status_no_wifi)
            else -> getString(R.string.aj_status_disconnected)
        }
        val sub = if (connected) getString(R.string.aj_status_sub, s.host ?: "--") else getString(R.string.aj_status_hint)
        if (card.rowTitle.text.toString() != title) card.rowTitle.text = title
        if (card.rowDesc.text.toString() != sub) card.rowDesc.text = sub
        card.rowDesc.visibility = View.VISIBLE
        val color = if (connected) ok else 0xFFE5A23B.toInt()
        card.icon.setImageResource(if (connected) R.drawable.ic_set_ok else R.drawable.ic_set_alert)
        card.icon.imageTintList = ColorStateList.valueOf(color)
        card.root.background = panel(PANEL, if (connected) ok else ThemeManager.withAlpha(color, 0x99), 14)
    }

    private fun saveLink(config: LinkConfig) {
        linkConfig = config
        lifecycleScope.launch { LinkPrefs.save(applicationContext, config) }
        refresh()
    }

    // ---------------------------------------------------------------- Sonido

    private fun buildSound() {
        slotRow(Slot.EQ, R.string.aj_eq_app)
        val presetName = EqPresets.byId(EqPresets.currentId(this))?.let { getString(it.label) } ?: "--"
        valueRow(R.string.aj_eq_preset, presetName, getString(R.string.aj_eq_preset_desc)) {
            val options = EqPresets.all
            choose(R.string.aj_eq_preset, options.map { getString(it.label) }) { i ->
                if (!EqPresets.apply(this, options[i].id)) toast(R.string.aj_eq_unsupported)
                refresh()
            }
        }
        switchRow(R.string.aj_bass, getString(R.string.aj_bass_desc), EqPresets.bassOn(this)) { on ->
            if (!EqPresets.setBass(this, on)) {
                toast(R.string.aj_bass_unsupported)
                refresh()
            }
        }
        slotRow(Slot.MUSIC, R.string.aj_music_app)
    }

    // ---------------------------------------------------------------- Pantalla

    private fun buildScreen() {
        // Color de cabina
        row(R.string.settings_color, null).apply {
            val swatches = HorizontalScrollView(this@SettingsActivity).apply { isHorizontalScrollBarEnabled = false }
            val line = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.HORIZONTAL }
            ThemeManager.presets.forEach { p ->
                val sw = FrameLayout(this@SettingsActivity).apply {
                    background = (ContextCompat.getDrawable(context, R.drawable.swatch)!!.mutate() as LayerDrawable).apply {
                        findDrawableByLayerId(R.id.swatchColor).setTint(p.color)
                    }
                    if (p.color == accent) foreground = ContextCompat.getDrawable(context, R.drawable.swatch_selected)
                    setOnClickListener {
                        ThemeManager.setAccent(this@SettingsActivity, p.color)
                        accent = p.color
                        refresh()
                    }
                }
                line.addView(labeled(sw, getString(p.name)))
            }
            swatches.addView(line)
            showExtra(swatches)
        }
        // Color del fuego del screensaver
        row(R.string.settings_fire, null).apply {
            val swatches = HorizontalScrollView(this@SettingsActivity).apply { isHorizontalScrollBarEnabled = false }
            val line = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.HORIZONTAL }
            val current = ThemeManager.fireId(this@SettingsActivity)
            (ThemeManager.fires.map { it.id to it } + (ThemeManager.FIRE_SYNC to null)).forEach { (id, fire) ->
                val colors = fire?.let { intArrayOf(it.start, it.end) } ?: intArrayOf(ThemeManager.darken(accent), accent)
                val sw = FrameLayout(this@SettingsActivity).apply {
                    background = GradientDrawable(GradientDrawable.Orientation.BL_TR, colors).apply { shape = GradientDrawable.OVAL }
                    if (id == current) foreground = ContextCompat.getDrawable(context, R.drawable.swatch_selected)
                    setOnClickListener {
                        ThemeManager.setFireId(this@SettingsActivity, id)
                        refresh()
                    }
                }
                line.addView(labeled(sw, getString(fire?.name ?: R.string.fire_sync)))
            }
            swatches.addView(line)
            showExtra(swatches)
        }
        switchRow(R.string.aj_night, getString(R.string.aj_night_desc), CockpitPrefs.nightMode(this)) {
            CockpitPrefs.setNightMode(this, it)
        }
        valueRow(R.string.aj_language, languageLabel(AppLanguage.get(this)), getString(R.string.aj_language_desc)) { pickLanguage() }
        switchRow(R.string.aj_idle_anim, getString(R.string.settings_idle_anim_hint), CockpitPrefs.idleAnimation(this)) {
            CockpitPrefs.setIdleAnimation(this, it)
        }
        val minutes = listOf(1, 2, 5, 10, 0)
        fun minutesLabel(m: Int) = if (m == 0) getString(R.string.idle_never) else getString(R.string.idle_minutes, m)
        valueRow(R.string.aj_idle_minutes, minutesLabel(CockpitPrefs.idleMinutes(this)), null) {
            choose(R.string.aj_idle_minutes, minutes.map { minutesLabel(it) }) { i ->
                CockpitPrefs.setIdleMinutes(this, minutes[i])
                refresh()
            }
        }
        buildInfoPages()
        valueRow(R.string.aj_device, profile.deviceName ?: getString(R.string.device_default), null) {
            editText(R.string.aj_device, profile.deviceName.orEmpty(), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS) {
                saveProfile(profile.copy(deviceName = it))
            }
        }
        valueRow(R.string.aj_brand, profile.carBrand ?: getString(R.string.aj_brand_none), null) { pickBrand() }
        valueRow(R.string.aj_model, profile.carModel ?: "--", getString(R.string.settings_car_model)) {
            editText(R.string.aj_model, profile.carModel.orEmpty(), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS) {
                saveProfile(profile.copy(carModel = it))
            }
        }
    }

    private fun languageLabel(lang: String) = when (lang) {
        AppLanguage.ES -> "Español"
        AppLanguage.EN -> "English"
        else -> getString(R.string.aj_language_auto)
    }

    /** Idioma de la app: automático (el del sistema), Español o English. Se aplica al instante. */
    private fun pickLanguage() {
        val values = listOf(AppLanguage.AUTO, AppLanguage.ES, AppLanguage.EN)
        choose(R.string.aj_language, values.map { languageLabel(it) }) { i ->
            if (AppLanguage.get(this) != values[i]) {
                AppLanguage.set(this, values[i])
                recreate()
            }
        }
    }

    private fun pickBrand() {
        val options = ProfileStore.BRANDS + getString(R.string.settings_brand_other) + getString(R.string.aj_brand_none)
        choose(R.string.aj_brand, options) { i ->
            when {
                i < ProfileStore.BRANDS.size -> saveProfile(profile.copy(carBrand = ProfileStore.BRANDS[i]))
                i == ProfileStore.BRANDS.size -> {
                    val current = profile.carBrand.orEmpty()
                    val other = if (ProfileStore.BRANDS.none { it.equals(current, true) }) current else ""
                    editText(R.string.settings_car_brand_other, other, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS) {
                        saveProfile(profile.copy(carBrand = it))
                    }
                }
                else -> saveProfile(profile.copy(carBrand = ""))
            }
        }
    }

    private fun saveProfile(p: Profile) {
        profile = p.copy(
            deviceName = p.deviceName?.trim()?.takeIf { it.isNotEmpty() },
            carBrand = p.carBrand?.trim()?.takeIf { it.isNotEmpty() },
            carModel = p.carModel?.trim()?.takeIf { it.isNotEmpty() },
        )
        lifecycleScope.launch {
            ProfileStore.save(applicationContext, p.deviceName.orEmpty(), p.carBrand.orEmpty(), p.carModel.orEmpty())
        }
        refresh()
    }

    /** Ventanitas del recuadro derecho: marcar cuáles se ven y su orden (al menos una). */
    private fun buildInfoPages() {
        val labels = mapOf(
            "trip" to R.string.aj_page_trip,
            "fuel" to R.string.aj_page_fuel,
            "total" to R.string.aj_page_total,
            "phone" to R.string.aj_page_phone,
        )
        val selected = CockpitPrefs.infoPages(this).distinct().toMutableList()
        val order = (selected + (pageOrder ?: emptyList()) + labels.keys).distinct().toMutableList()
        pageOrder = order
        row(R.string.aj_pages, getString(R.string.aj_pages_desc)).apply {
            val box = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.VERTICAL }
            order.forEachIndexed { i, id ->
                val on = id in selected
                val line = LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = px(60)
                    setPadding(px(12), 0, px(6), 0)
                    background = panel(PANEL_DEEP, if (on) ThemeManager.withAlpha(accent, 0x80) else HAIRLINE, 10)
                }
                val check = ImageView(this@SettingsActivity).apply {
                    setImageResource(R.drawable.ic_set_check)
                    setPadding(px(4), px(4), px(4), px(4))
                    imageTintList = ColorStateList.valueOf(if (on) 0xFF0A0B0E.toInt() else 0x00000000)
                    background = if (on) panel(accent, accent, 8) else panel(0x00000000, dim, 8, 2)
                }
                line.addView(check, LinearLayout.LayoutParams(px(34), px(34)))
                line.addView(TextView(this@SettingsActivity).apply {
                    text = labels[id]?.let { getString(it) } ?: id
                    textSize = 19f
                    setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD))
                    setTextColor(if (on) textColor else dim)
                    setPadding(px(16), 0, 0, 0)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                line.setOnClickListener {
                    val now = selected.toMutableList()
                    if (on) {
                        if (now.size <= 1) { toast(R.string.aj_pages_min); return@setOnClickListener }
                        now.remove(id)
                    } else {
                        now += id
                    }
                    CockpitPrefs.setInfoPages(this@SettingsActivity, order.filter { it in now })
                    refresh()
                }
                fun arrow(@DrawableRes icon: Int, @StringRes desc: Int, enabled: Boolean, to: Int) = ImageView(this@SettingsActivity).apply {
                    setImageResource(icon)
                    contentDescription = getString(desc)
                    setPadding(px(10), px(10), px(10), px(10))
                    imageTintList = ColorStateList.valueOf(if (enabled) textColor else TEXT_FAINT)
                    background = panel(PANEL, HAIRLINE, 10)
                    isEnabled = enabled
                    setOnClickListener {
                        val list = order.toMutableList()
                        list.add(to, list.removeAt(i))
                        pageOrder = list
                        CockpitPrefs.setInfoPages(this@SettingsActivity, list.filter { it in selected })
                        refresh()
                    }
                }
                line.addView(arrow(R.drawable.ic_set_up, R.string.aj_move_up, i > 0, i - 1),
                    LinearLayout.LayoutParams(px(52), px(48)).apply { marginStart = px(8) })
                line.addView(arrow(R.drawable.ic_set_down, R.string.aj_move_down, i < order.size - 1, i + 1),
                    LinearLayout.LayoutParams(px(52), px(48)).apply { marginStart = px(8) })
                box.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { if (i > 0) topMargin = px(8) })
            }
            showExtra(box)
        }
    }

    /** Orden completo de las ventanitas (incluye las desmarcadas) mientras la pantalla vive. */
    private var pageOrder: List<String>? = null

    // ---------------------------------------------------------------- Botones y apps

    private fun buildButtons() {
        Slot.entries.forEach { slotRow(it, it.title) }
        hint(R.string.aj_buttons_hint)
    }

    /** Fila que muestra la app de un botón y abre el selector (incluye "usar predeterminado"). */
    private fun slotRow(slot: Slot, @StringRes title: Int) {
        val custom = SlotStore.assigned(this, slot)?.let { SlotStore.resolve(this, slot) }?.takeIf { it.custom }
        val desc = when {
            custom != null -> getString(R.string.aj_slot_custom, custom.label ?: "?")
            else -> SlotStore.resolve(this, slot)?.label?.let { getString(R.string.aj_slot_default, it) }
                ?: if (slot == Slot.CAMERA) getString(R.string.aj_slot_ask) else getString(R.string.slot_default)
        }
        row(title, desc).apply {
            icon.visibility = View.VISIBLE
            icon.setImageResource(slot.icon)
            icon.imageTintList = ColorStateList.valueOf(dim)
            end.addView(chevron())
            main.setOnClickListener { AppPicker.pickForSlot(this@SettingsActivity, slot) { refresh() } }
        }
    }

    // ---------------------------------------------------------------- OBD2

    private fun buildObd() {
        switchRow(R.string.aj_obd, getString(R.string.settings_obd_hint), CockpitPrefs.obdEnabled(this)) { on ->
            CockpitPrefs.setObdEnabled(this, on)
            if (on && CockpitPrefs.obdDevice(this) == null) pickObdDevice()
        }
        val device = CockpitPrefs.obdDevice(this)
        valueRow(R.string.aj_obd_device, device?.let { obdName(it) } ?: "--",
            if (device == null) getString(R.string.aj_obd_device_none) else getString(R.string.settings_obd_device)) {
            pickObdDevice()
        }
        switchRow(R.string.aj_obd_demo, getString(R.string.aj_obd_demo_desc), CockpitPrefs.obdDemo(this)) {
            CockpitPrefs.setObdDemo(this, it)
        }
    }

    @SuppressLint("MissingPermission")
    private fun bonded() = runCatching {
        (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.bondedDevices?.toList()
    }.getOrNull().orEmpty()

    @SuppressLint("MissingPermission")
    private fun obdName(address: String): String =
        bonded().firstOrNull { it.address == address }?.let { runCatching { it.name }.getOrNull() } ?: address

    /** Lista los dispositivos Bluetooth ya emparejados (el ELM327 se empareja desde Ajustes del sistema). */
    @SuppressLint("MissingPermission")
    private fun pickObdDevice() {
        val devices = bonded()
        if (devices.isEmpty()) {
            Toast.makeText(this, R.string.settings_obd_none, Toast.LENGTH_LONG).show()
            refresh()
            return
        }
        val names = devices.map { "${runCatching { it.name }.getOrNull() ?: "?"}  (${it.address})" }
        choose(R.string.settings_obd_device, names, onCancel = { refresh() }) { i ->
            CockpitPrefs.setObdDevice(this, devices[i].address)
            refresh()
        }
    }

    // ---------------------------------------------------------------- Video

    private fun buildVideo() {
        val max1080 = com.iheii.veloradrive.video.VideoCodecs.maxAvcHeight() >= 1080
        val cur = if (max1080) com.iheii.veloradrive.video.VideoPrefs.autoMaxHeight(this) else 720
        valueRow(R.string.aj_video_auto_max, getString(R.string.video_height, cur),
            getString(if (max1080) R.string.aj_video_auto_max_desc else R.string.aj_video_auto_max_no1080)) {
            if (!max1080) return@valueRow
            val options = listOf(720, 1080)
            choose(R.string.aj_video_auto_max, options.map { getString(R.string.video_height, it) }) { i ->
                com.iheii.veloradrive.video.VideoPrefs.setAutoMaxHeight(this, options[i])
                refresh()
            }
        }
        switchRow(R.string.aj_video_hide_moving, getString(R.string.aj_video_hide_moving_desc),
            com.iheii.veloradrive.video.VideoPrefs.hideWhileMoving(this)) {
            com.iheii.veloradrive.video.VideoPrefs.setHideWhileMoving(this, it)
        }
        row(R.string.aj_video_warning_reset, getString(R.string.aj_video_warning_reset_desc)).apply {
            end.addView(chevron())
            main.setOnClickListener {
                com.iheii.veloradrive.video.VideoPrefs.setWarningAccepted(this@SettingsActivity, false)
                toast(R.string.aj_video_warning_reset_done)
            }
        }
    }

    // ---------------------------------------------------------------- Diagnóstico

    private fun buildDiag() {
        row(R.string.link_debug, getString(R.string.aj_diag_live)).apply {
            val tv = TextView(this@SettingsActivity).apply {
                textSize = 15f
                typeface = Typeface.MONOSPACE
                setTextColor(textColor)
                setLineSpacing(0f, 1.15f)
                text = debugText(linkUi)
            }
            debugView = tv
            showExtra(tv)
        }
        row(R.string.aj_export, getString(R.string.aj_export_desc)).apply {
            end.addView(ImageView(this@SettingsActivity).apply {
                setImageResource(R.drawable.ic_set_share)
                setPadding(px(12), px(12), px(12), px(12))
                imageTintList = ColorStateList.valueOf(0xFF0A0B0E.toInt())
                background = panel(accent, accent, 12)
            }, LinearLayout.LayoutParams(px(56), px(52)))
            main.setOnClickListener { exportDiagnostics() }
        }
    }

    /** IP que el estéreo tiene en la red WiFi actual (para saber si está en la misma red que el celular). */
    private fun stereoIp(): String? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    /** Datos para diagnosticar cuando algo falla con el celular. */
    private fun debugText(s: LinkUiState): String {
        val state = when (s.status) {
            LinkStatus.CONNECTED -> getString(R.string.dbg_connected)
            LinkStatus.CONNECTING -> getString(R.string.dbg_connecting)
            LinkStatus.NO_TOKEN -> getString(R.string.aj_status_no_token)
            LinkStatus.BAD_TOKEN -> getString(R.string.dbg_bad_token)
            LinkStatus.NO_WIFI -> getString(R.string.aj_status_no_wifi)
            LinkStatus.DISCONNECTED -> getString(R.string.dbg_disconnected)
        }
        val bt = when (s.state?.btAudio) {
            true -> getString(R.string.dbg_bt_yes)
            false -> getString(R.string.dbg_bt_no)
            null -> getString(R.string.dbg_nodata)
        }
        val sound = when (AudioWatchdog.sound.value) {
            AudioWatchdog.Sound.YES -> getString(R.string.dbg_yes)
            AudioWatchdog.Sound.NO -> getString(R.string.dbg_no)
            AudioWatchdog.Sound.UNKNOWN -> getString(R.string.dbg_nodata)
        }
        val ago = if (s.lastMessageAt > 0) getString(R.string.dbg_ago, (SystemClock.elapsedRealtime() - s.lastMessageAt) / 1000) else ""
        return buildString {
            appendLine(getString(R.string.dbg_phone_ip, s.host ?: "--", s.port))
            appendLine(getString(R.string.dbg_stereo_ip, stereoIp() ?: getString(R.string.dbg_no_wifi)))
            appendLine(getString(R.string.dbg_state, state))
            appendLine(getString(R.string.dbg_last_error, s.lastError ?: "--"))
            appendLine(getString(R.string.dbg_bt, bt))
            appendLine(getString(R.string.dbg_sound, sound))
            appendLine(getString(R.string.dbg_sound_fix, AudioWatchdog.lastAction.value ?: "--"))
            append(getString(R.string.dbg_last_message, ago, s.lastMessage?.take(120) ?: "--"))
        }
    }

    /**
     * Archivo .txt con versión, equipo, líneas de depuración y resumen de ajustes, enviado con el menú
     * de compartir de Android. Nunca incluye el código del celular ni la contraseña del hotspot.
     */
    private fun exportDiagnostics() {
        val text = scrub(buildDiagnostics())
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(cacheDir, "diagnostico").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                    File(dir, "velora-diagnostico-$stamp.txt").apply { writeText(text) }
                }.getOrNull()
            }
            if (file == null) { toast(R.string.aj_export_failed); return@launch }
            val uri = runCatching { FileProvider.getUriForFile(this@SettingsActivity, "$packageName.fileprovider", file) }.getOrNull()
            if (uri == null) { toast(R.string.aj_export_failed); return@launch }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching {
                startActivity(Intent.createChooser(send, getString(R.string.aj_export_chooser))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }.onFailure { toast(R.string.aj_export_failed) }
        }
    }

    private fun buildDiagnostics(): String {
        val pkg = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pkg?.longVersionCode else pkg?.versionCode?.toLong()
        val s = linkUi
        fun yes(b: Boolean) = if (b) "sí" else "no"
        return buildString {
            appendLine("Velora Drive — diagnóstico")
            appendLine("Fecha: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine("Versión de la app: ${pkg?.versionName ?: "?"} ($code)")
            appendLine("Equipo: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine()
            appendLine("== Conexión con OfflineTune ==")
            appendLine("Estado del enlace: ${s.status}  conectado=${yes(s.connected)}")
            appendLine("Celular: ${s.state?.device ?: "--"}  Android Auto=${s.state?.androidAuto ?: "--"}")
            appendLine(debugText(s))
            appendLine()
            appendLine("== Ajustes ==")
            appendLine("Código del celular: ${if (linkConfig.token.isNullOrEmpty()) "sin configurar" else "configurado"}")
            appendLine("Hotspot: ${linkConfig.ssid ?: "--"}")
            appendLine("Contraseña del hotspot: ${if (linkConfig.password.isNullOrEmpty()) "sin configurar" else "configurada"}")
            appendLine("IP manual: ${linkConfig.host ?: "--"}")
            appendLine("Reparar sonido Android Auto: ${yes(CockpitPrefs.audioFix(this@SettingsActivity))}")
            appendLine("Reproductor mini sobre Android Auto: ${yes(CockpitPrefs.overlayOnProjection(this@SettingsActivity))}")
            appendLine("Audio BT al conectar: ${yes(CockpitPrefs.btWake(this@SettingsActivity))}")
            appendLine("Hora del celular: ${yes(CockpitPrefs.phoneTime(this@SettingsActivity))}")
            appendLine("OBD2: ${yes(CockpitPrefs.obdEnabled(this@SettingsActivity))}  escáner=${CockpitPrefs.obdDevice(this@SettingsActivity) ?: "--"}  demo=${yes(CockpitPrefs.obdDemo(this@SettingsActivity))}")
            appendLine("Color de cabina: #%08X  fuego=%s".format(ThemeManager.accent(this@SettingsActivity), ThemeManager.fireId(this@SettingsActivity)))
            appendLine("Modo noche: ${yes(CockpitPrefs.nightMode(this@SettingsActivity))}  animación en reposo=${yes(CockpitPrefs.idleAnimation(this@SettingsActivity))}")
            appendLine("Screensaver: ${CockpitPrefs.idleMinutes(this@SettingsActivity)} min (0 = nunca)")
            appendLine("Ventanitas: ${CockpitPrefs.infoPages(this@SettingsActivity).joinToString(",")}")
            appendLine("Ecualizador: ${EqPresets.currentId(this@SettingsActivity) ?: "--"} (soportado=${yes(EqPresets.isSupported)})  bajos=${yes(EqPresets.bassOn(this@SettingsActivity))} (soportado=${yes(BassController.isSupported)})")
            appendLine("Perfil: celular=${profile.deviceName ?: "--"}  marca=${profile.carBrand ?: "--"}  modelo=${profile.carModel ?: "--"}")
            Slot.entries.forEach { slot ->
                val cn = SlotStore.assigned(this@SettingsActivity, slot)
                appendLine("Botón ${slot.name}: ${cn?.flattenToShortString() ?: "predeterminado"}")
            }
        }
    }

    /** Por si algún mensaje del celular repitiera el código o la contraseña: se tachan. */
    private fun scrub(text: String): String {
        var out = text
        listOfNotNull(linkConfig.token, linkConfig.password).filter { it.length >= 3 }.forEach { secret ->
            out = out.replace(secret, "****")
        }
        return out
    }

    // ---------------------------------------------------------------- piezas

    private fun row(@StringRes title: Int, desc: String?): ItemSettingsRowBinding {
        val b = ItemSettingsRowBinding.inflate(layoutInflater, binding.content, false)
        b.rowTitle.setText(title)
        if (!desc.isNullOrEmpty()) {
            b.rowDesc.text = desc
            b.rowDesc.visibility = View.VISIBLE
        }
        b.root.background = panel(PANEL, ThemeManager.withAlpha(accent, 0x55), 14)
        binding.content.addView(b.root)
        return b
    }

    private fun ItemSettingsRowBinding.showExtra(view: View) {
        extra.visibility = View.VISIBLE
        extra.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun switchRow(@StringRes title: Int, desc: String?, checked: Boolean, onChange: (Boolean) -> Unit) =
        row(title, desc).apply {
            val sw = BigSwitch(this@SettingsActivity).apply {
                accent = this@SettingsActivity.accent
                setChecked(checked)
            }
            end.addView(sw)
            main.setOnClickListener { onChange(sw.toggle()) }
        }

    private fun valueRow(@StringRes title: Int, value: String, desc: String?, onClick: () -> Unit) =
        row(title, desc).apply {
            end.addView(TextView(this@SettingsActivity).apply {
                text = value
                textSize = 17f
                maxWidth = px(260)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD))
                setTextColor(NAV_TEXT)
            })
            end.addView(chevron())
            main.setOnClickListener { onClick() }
        }

    private fun hint(@StringRes text: Int) {
        binding.content.addView(TextView(this).apply {
            setText(text)
            textSize = 15f
            setTextColor(dim)
            setPadding(px(6), px(4), px(6), px(8))
        })
    }

    private fun chevron() = ImageView(this).apply {
        setImageResource(R.drawable.ic_set_chevron)
        imageTintList = ColorStateList.valueOf(NAV_TEXT)
        layoutParams = LinearLayout.LayoutParams(px(28), px(28)).apply { marginStart = px(6) }
    }

    private fun labeled(swatch: View, name: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(swatch, LinearLayout.LayoutParams(px(54), px(54)))
        addView(TextView(this@SettingsActivity).apply {
            text = name
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(dim)
            setPadding(0, px(6), 0, 0)
        }, LinearLayout.LayoutParams(px(96), ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun panel(@ColorInt fill: Int, @ColorInt stroke: Int, radius: Int, strokeDp: Int = 1) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius * d
        setStroke(maxOf(1, (strokeDp * d).toInt()), stroke)
    }

    private fun renderClock() {
        val is24 = DateFormat.is24HourFormat(this)
        val now = Date()
        val time = SimpleDateFormat(if (is24) "H:mm" else "h:mm", Locale.US).format(now)
        if (is24) {
            if (binding.clock.text.toString() != time) binding.clock.text = time
            return
        }
        val amPm = SimpleDateFormat("a", Locale.US).format(now).uppercase()
        val full = "$time $amPm"
        if (binding.clock.text.toString() == full) return
        binding.clock.text = SpannableString(full).apply {
            setSpan(RelativeSizeSpan(0.55f), time.length + 1, full.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun toast(@StringRes text: Int) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    /** Lista simple de opciones; al cerrarse vuelve el modo inmersivo. */
    private fun choose(@StringRes title: Int, options: List<String>, onCancel: () -> Unit = {}, onPick: (Int) -> Unit) {
        var picked = false
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(options.toTypedArray()) { _, i -> picked = true; onPick(i) }
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener { if (!picked) onCancel(); goImmersive() }
            .show()
    }

    /** Campo de texto en un diálogo; guarda con "Guardar" o con la tecla Listo del teclado. */
    private fun editText(@StringRes title: Int, value: String, type: Int, maxLength: Int = 0, onSave: (String) -> Unit) {
        val field = EditText(this).apply {
            setText(value)
            setSelection(value.length)
            inputType = type
            isSingleLine = true
            textSize = 22f
            setTextColor(textColor)
            setHintTextColor(dim)
            backgroundTintList = ColorStateList.valueOf(accent)
            minHeight = px(60)
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            if (maxLength > 0) filters = arrayOf(InputFilter.LengthFilter(maxLength))
        }
        val wrap = FrameLayout(this).apply {
            setPadding(px(24), px(8), px(24), 0)
            addView(field)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(wrap)
            .setPositiveButton(R.string.aj_save) { _, _ -> onSave(field.text.toString()) }
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener { goImmersive() }
            .create()
        field.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                onSave(field.text.toString())
                dialog.dismiss()
                true
            } else false
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
        field.requestFocus()
    }

    private companion object {
        const val KEY_SECTION = "section"
        const val PANEL = 0xFF12151B.toInt()
        const val PANEL_DEEP = 0xFF0D0F13.toInt()
        const val HAIRLINE = 0xFF222B35.toInt()
        const val NAV_TEXT = 0xFFB4BFCA.toInt()
        const val TEXT_FAINT = 0xFF3A4552.toInt()
    }
}
