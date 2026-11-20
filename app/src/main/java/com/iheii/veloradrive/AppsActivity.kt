package com.iheii.veloradrive

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.iheii.veloradrive.databinding.ActivityAppsBinding
import com.iheii.veloradrive.databinding.ItemAppBinding

/** Cajón con todas las apps instaladas, pensado para abrir rápido APKs de prueba. */
class AppsActivity : LocaleActivity() {

    private lateinit var binding: ActivityAppsBinding
    private var allApps = emptyList<AppEntry>()
    private val adapter = AppsAdapter()

    override fun attachBaseContext(base: Context) = super.attachBaseContext(base.designScaled())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        goImmersive()

        binding.btnBack.setOnClickListener { finish() }
        binding.grid.adapter = adapter
        binding.grid.setOnItemClickListener { _, _, pos, _ ->
            hideKeyboard()
            open(adapter.getItem(pos))
        }
        // Al deslizar la cuadrícula el teclado estorba: se esconde.
        binding.grid.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, state: Int) {
                if (state != AbsListView.OnScrollListener.SCROLL_STATE_IDLE) hideKeyboard()
            }
            override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) = Unit
        })
        binding.btnSearch.setOnClickListener { if (searchOpen) closeSearch() else openSearch() }
        binding.search.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) hideKeyboard()
            false
        }
        binding.grid.setOnItemLongClickListener { _, _, pos, _ ->
            openAppInfo(adapter.getItem(pos))
            true
        }
        binding.search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = filter(s?.toString().orEmpty())
        })
    }

    override fun onResume() {
        super.onResume()
        // El teclado nunca se abre solo: la raíz se queda con el foco y la búsqueda nace cerrada.
        if (!searchOpen) {
            binding.root.requestFocus()
            hideKeyboard()
        }
        // Se recarga cada vez para ver al instante las APKs recién instaladas o borradas.
        lifecycleScope.launch {
            allApps = AppRepository.load(this@AppsActivity, forceRefresh = true)
            filter(binding.search.text?.toString().orEmpty())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    private fun filter(text: String) {
        // Sin importar mayúsculas ni acentos: normalize() quita ambos.
        val q = normalize(text.trim())
        adapter.items = if (q.isEmpty()) allApps else allApps.filter { it.key.contains(q) }
        binding.empty.visibility = if (q.isNotEmpty() && adapter.items.isEmpty()) View.VISIBLE else View.GONE
    }

    // ---------------------------------------------------------------- Búsqueda manual (lupa)

    private var searchOpen = false

    /** Solo al tocar la lupa aparece el campo y se abre el teclado. */
    private fun openSearch() {
        searchOpen = true
        binding.title.visibility = View.GONE
        binding.search.visibility = View.VISIBLE
        binding.btnSearch.setImageResource(R.drawable.ic_close)
        binding.btnSearch.contentDescription = getString(R.string.search_close)
        binding.search.requestFocus()
        binding.search.post { imm.showSoftInput(binding.search, InputMethodManager.SHOW_IMPLICIT) }
    }

    /** La X borra el texto, oculta el campo y esconde el teclado; vuelven todas las apps. */
    private fun closeSearch() {
        searchOpen = false
        hideKeyboard()
        binding.search.text?.clear()
        binding.search.visibility = View.GONE
        binding.title.visibility = View.VISIBLE
        binding.btnSearch.setImageResource(R.drawable.ic_search)
        binding.btnSearch.contentDescription = getString(R.string.search_open)
        binding.root.requestFocus()
    }

    private val imm get() = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager

    private fun hideKeyboard() {
        imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
        binding.search.clearFocus()
    }

    private fun open(app: AppEntry) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, R.string.not_found, Toast.LENGTH_SHORT).show() }
    }

    private fun openAppInfo(app: AppEntry) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", app.component.packageName, null))
        runCatching { startActivity(intent) }
    }

    private inner class AppsAdapter : BaseAdapter() {
        var items = emptyList<AppEntry>()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val b = convertView?.tag as? ItemAppBinding
                ?: ItemAppBinding.inflate(layoutInflater, parent, false).also { it.root.tag = it }
            val app = items[position]
            b.icon.setImageDrawable(app.icon)
            b.label.text = app.label
            return b.root
        }
    }
}
