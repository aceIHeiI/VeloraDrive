package com.iheii.veloradrive.video

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Outline
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.annotation.ColorInt
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.iheii.veloradrive.Profile
import com.iheii.veloradrive.ProfileStore
import com.iheii.veloradrive.R
import com.iheii.veloradrive.ThemeManager
import com.iheii.veloradrive.VeloraClock
import com.iheii.veloradrive.databinding.ActivityVideoLibraryBinding
import com.iheii.veloradrive.databinding.ItemVideoCardBinding
import com.iheii.veloradrive.designScaled
import com.iheii.veloradrive.goImmersive
import com.iheii.veloradrive.link.LinkUiState
import com.iheii.veloradrive.link.PhoneLinkHub
import com.iheii.veloradrive.link.VideoItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Biblioteca de videos de OfflineTune Car: pestañas (descargados, favoritos, listas, historial y
 * lo que suena en el celular), búsqueda en YouTube por el celular y cuadrícula de 4 columnas.
 * Se abre desde el botón VIDEO cuando el celular está conectado.
 */
class VideoLibraryActivity : AppCompatActivity() {

    private enum class Tab(val key: String, @param:StringRes val title: Int) {
        DOWNLOADED("downloaded", R.string.video_tab_downloaded),
        FAVORITES("favorites", R.string.video_tab_favorites),
        PLAYLISTS("playlists", R.string.video_tab_playlists),
        HISTORY("history", R.string.video_tab_history),
        NOW("now", R.string.video_tab_now),
    }

    /** Lo que muestra la cuadrícula: una pestaña, una lista abierta o una búsqueda. */
    private sealed class Mode {
        data class OfTab(val tab: Tab) : Mode()
        data class Playlist(val id: String, val name: String) : Mode()
        data class Search(val query: String, val from: Tab) : Mode()
    }

    private lateinit var b: ActivityVideoLibraryBinding
    @ColorInt private var accent = 0
    private var mode: Mode = Mode.OfTab(Tab.DOWNLOADED)
    private var loadJob: Job? = null
    private val adapter = Adapter()
    private val tabViews = mutableMapOf<Tab, TextView>()
    private val handler = Handler(Looper.getMainLooper())

    private var profile = Profile(null, null, null)
    private var linkUi = LinkUiState()

    override fun attachBaseContext(base: Context) = super.attachBaseContext(base.designScaled())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityVideoLibraryBinding.inflate(layoutInflater)
        setContentView(b.root)
        goImmersive()
        VeloraClock.init(this)
        accent = ThemeManager.accent(this)

        savedInstanceState?.getString(KEY_TAB)?.let { k -> Tab.entries.firstOrNull { it.key == k } }
            ?.let { mode = Mode.OfTab(it) }

        b.btnBack.backgroundTintList = ColorStateList.valueOf(accent)
        b.btnBack.imageTintList = ColorStateList.valueOf(accent)
        b.btnBack.setOnClickListener { goBack() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })
        b.phoneIcon.imageTintList = ColorStateList.valueOf(ACCENT2)
        b.btnRetry.background = VideoUi.outline(this, accent, ThemeManager.withAlpha(accent, 0x22), 12f)
        b.btnRetry.setTextColor(accent)
        b.btnRetry.setOnClickListener { load() }

        buildTabs()
        setupGrid()
        setupSearch()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { ProfileStore.flow(this@VideoLibraryActivity).collect { profile = it; renderPhone() } }
                PhoneLinkHub.ui(this@VideoLibraryActivity).collect { linkUi = it; renderPhone() }
            }
        }
        load()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        (mode as? Mode.OfTab)?.let { outState.putString(KEY_TAB, it.tab.key) }
    }

    override fun onStart() {
        super.onStart()
        PhoneLinkHub.acquire(this)
    }

    override fun onResume() {
        super.onResume()
        goImmersive()
        clockTick.run()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(clockTick)
    }

    override fun onStop() {
        super.onStop()
        PhoneLinkHub.release(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN) ThumbLoader.clear()
    }

    private val clockTick = object : Runnable {
        override fun run() {
            b.clock.text = VideoUi.clock(this@VideoLibraryActivity)
            handler.removeCallbacks(this)
            handler.postDelayed(this, VideoUi.msToNextMinute())
        }
    }

    private fun renderPhone() {
        val name = profile.deviceName ?: linkUi.state?.device?.takeIf { linkUi.connected }
            ?: getString(R.string.video_phone_default)
        b.phoneName.text = name.uppercase(SPANISH)
        b.phoneIcon.imageTintList = ColorStateList.valueOf(if (linkUi.connected) ACCENT2 else DIM)
    }

    private fun goBack() {
        when (val m = mode) {
            is Mode.Playlist -> select(Mode.OfTab(Tab.PLAYLISTS))
            is Mode.Search -> {
                b.search.setText("")
                select(Mode.OfTab(m.from))
            }
            is Mode.OfTab -> finish()
        }
    }

    // ---------------------------------------------------------------- pestañas

    private fun buildTabs() {
        val d = resources.displayMetrics.density
        Tab.entries.forEachIndexed { i, tab ->
            val tv = TextView(this).apply {
                setText(tab.title)
                textSize = 16f
                typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)
                letterSpacing = 0.03f
                gravity = android.view.Gravity.CENTER
                setPadding((18 * d).toInt(), 0, (18 * d).toInt(), 0)
                if (tab == Tab.NOW) {
                    val icon = ContextCompat.getDrawable(context, R.drawable.ic_music)?.mutate()
                    icon?.setBounds(0, 0, (16 * d).toInt(), (16 * d).toInt())
                    setCompoundDrawablesRelative(icon, null, null, null)
                    compoundDrawablePadding = (8 * d).toInt()
                }
                setOnClickListener {
                    b.search.setText("")
                    select(Mode.OfTab(tab))
                }
            }
            b.tabs.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                if (i > 0) marginStart = (12 * d).toInt()
            })
            tabViews[tab] = tv
        }
        renderTabs()
    }

    private fun renderTabs() {
        val active = when (val m = mode) {
            is Mode.OfTab -> m.tab
            is Mode.Playlist -> Tab.PLAYLISTS
            is Mode.Search -> null
        }
        tabViews.forEach { (tab, tv) ->
            val on = tab == active
            tv.background = if (on) VideoUi.outline(this, accent, ThemeManager.withAlpha(accent, 0x22), 19f)
            else VideoUi.outline(this, TAB_STROKE, TAB_FILL, 19f, strokeDp = 1f)
            val color = if (on) accent else NAV_TEXT
            tv.setTextColor(color)
            tv.compoundDrawableTintList = ColorStateList.valueOf(color)
            tv.text = if (tab == Tab.PLAYLISTS && mode is Mode.Playlist) {
                getString(R.string.video_tab_back) + " " + (mode as Mode.Playlist).name.uppercase(SPANISH)
            } else getString(tab.title)
        }
    }

    private fun select(m: Mode) {
        mode = m
        renderTabs()
        load()
    }

    // ---------------------------------------------------------------- búsqueda

    private fun setupSearch() {
        b.search.setOnEditorActionListener { v, action, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE || enter) {
                val q = v.text.toString().trim()
                hideKeyboard()
                if (q.isNotEmpty()) {
                    val from = when (val cur = mode) {
                        is Mode.OfTab -> cur.tab
                        is Mode.Playlist -> Tab.PLAYLISTS
                        is Mode.Search -> cur.from
                    }
                    select(Mode.Search(q, from))
                }
                true
            } else false
        }
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(b.search.windowToken, 0)
        b.search.clearFocus()
        b.root.requestFocus()
        goImmersive()
    }

    // ---------------------------------------------------------------- carga

    private fun load() {
        loadJob?.cancel()
        val m = mode
        adapter.submit(emptyList())
        showMessage(null)
        b.loading.visibility = View.VISIBLE
        loadJob = lifecycleScope.launch {
            try {
                val api = VideoApi.current(this@VideoLibraryActivity)
                if (api == null) {
                    b.loading.visibility = View.GONE
                    showMessage(getString(R.string.video_error_disconnected), retry = true)
                    return@launch
                }
                adapter.api = api
                val list = when (m) {
                    is Mode.OfTab -> api.library(m.tab.key)
                    is Mode.Playlist -> api.library("playlist", m.id)
                    is Mode.Search -> api.search(m.query)
                }
                b.loading.visibility = View.GONE
                adapter.submit(list.items)
                b.grid.scrollToPosition(0)
                if (list.items.isEmpty()) {
                    showMessage(getString(if (m is Mode.Search) R.string.video_empty_search else R.string.video_empty))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                b.loading.visibility = View.GONE
                showMessage(getString(R.string.video_error_load), retry = true)
            }
        }
    }

    private fun showMessage(text: String?, retry: Boolean = false) {
        b.messageBox.visibility = if (text == null) View.GONE else View.VISIBLE
        b.message.text = text
        b.btnRetry.visibility = if (retry) View.VISIBLE else View.GONE
    }

    private fun open(item: VideoItem) {
        val pid = item.playlistId
        if (pid != null) {
            select(Mode.Playlist(pid, item.title))
            return
        }
        VideoQueue.set(adapter.items)
        VideoPlayerActivity.open(this, item)
    }

    // ---------------------------------------------------------------- cuadrícula

    private fun setupGrid() {
        b.grid.layoutManager = GridLayoutManager(this, COLUMNS)
        b.grid.setHasFixedSize(true)
        b.grid.setItemViewCacheSize(8)
        b.grid.adapter = adapter
    }

    private inner class Holder(val v: ItemVideoCardBinding) : RecyclerView.ViewHolder(v.root)

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        var items: List<VideoItem> = emptyList()
            private set
        var api: VideoApi? = null
        private val corner by lazy { 14 * resources.displayMetrics.density }

        init { setHasStableIds(true) }

        @android.annotation.SuppressLint("NotifyDataSetChanged")
        fun submit(list: List<VideoItem>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size
        override fun getItemId(position: Int) = items[position].let { (it.playlistId ?: it.id).hashCode().toLong() }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = ItemVideoCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            // La miniatura toma las esquinas de arriba de la tarjeta (las de abajo quedan fuera).
            v.thumbBox.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, (view.height + corner).toInt(), corner)
                }
            }
            v.thumbBox.clipToOutline = true
            v.quality.backgroundTintList = ColorStateList.valueOf(accent)
            v.root.setOnClickListener {
                val pos = (v.root.tag as? Holder)?.bindingAdapterPosition ?: RecyclerView.NO_POSITION
                if (pos != RecyclerView.NO_POSITION && pos < items.size) open(items[pos])
            }
            return Holder(v).also { v.root.tag = it }
        }

        override fun onBindViewHolder(h: Holder, position: Int) {
            val item = items[position]
            val v = h.v
            v.title.text = item.title
            val isList = item.playlistId != null
            if (isList) {
                v.playIcon.setImageResource(R.drawable.ic_v_playlist)
                v.duration.text = resources.getQuantityString(R.plurals.video_playlist_count, item.count ?: 0, item.count ?: 0)
                v.quality.visibility = View.GONE
                v.statusIcon.setImageResource(R.drawable.ic_v_playlist)
                v.statusIcon.imageTintList = ColorStateList.valueOf(DIM)
                v.status.text = getString(R.string.video_playlist)
            } else {
                v.playIcon.setImageResource(R.drawable.ic_v_play_outline)
                v.duration.text = VideoUi.duration(item.durationSec)
                v.duration.visibility = if (item.durationSec > 0) View.VISIBLE else View.GONE
                val hgt = item.downloadedHeight
                if (item.downloaded && hgt != null && hgt > 0) {
                    v.quality.text = getString(R.string.video_height, hgt)
                    v.quality.visibility = View.VISIBLE
                } else v.quality.visibility = View.GONE
                if (item.downloaded) {
                    v.statusIcon.setImageResource(R.drawable.ic_v_check_circle)
                    v.statusIcon.imageTintList = ColorStateList.valueOf(OK)
                    v.status.setText(R.string.video_downloaded)
                } else {
                    v.statusIcon.setImageResource(R.drawable.ic_wifi)
                    v.statusIcon.imageTintList = ColorStateList.valueOf(DIM)
                    v.status.setText(R.string.video_online)
                }
            }
            if (isList) v.duration.visibility = View.VISIBLE
            ThumbLoader.load(lifecycleScope, v.thumb, api?.thumbUrl(item.id))
        }

        override fun onViewRecycled(h: Holder) {
            ThumbLoader.load(lifecycleScope, h.v.thumb, null)
        }
    }

    companion object {
        private const val KEY_TAB = "tab"
        private const val COLUMNS = 4
        private val SPANISH = Locale.forLanguageTag("es")
        private const val ACCENT2 = 0xFFFF3D6E.toInt()
        private const val OK = 0xFF3DDC84.toInt()
        private const val DIM = 0xFF7D8B99.toInt()
        private const val NAV_TEXT = 0xFFB4BFCA.toInt()
        private const val TAB_STROKE = 0xFF2A303A.toInt()
        private const val TAB_FILL = 0xFF141820.toInt()

        fun intent(context: Context) = Intent(context, VideoLibraryActivity::class.java)
    }
}
