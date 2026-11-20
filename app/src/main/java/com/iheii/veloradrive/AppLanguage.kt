package com.iheii.veloradrive

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

/**
 * Idioma de la app: "auto" sigue al del sistema (español por omisión), o se fija "es" / "en" en
 * Ajustes › Pantalla. Se guarda en las mismas preferencias del cockpit.
 */
object AppLanguage {
    const val AUTO = "auto"
    const val ES = "es"
    const val EN = "en"

    private const val KEY = "pref_language"

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("cockpit", Context.MODE_PRIVATE)

    fun get(c: Context): String = prefs(c).getString(KEY, AUTO)?.takeIf { it == ES || it == EN } ?: AUTO

    fun set(c: Context, lang: String) {
        prefs(c).edit().putString(KEY, if (lang == ES || lang == EN) lang else AUTO).apply()
    }

    /** Contexto con el idioma elegido; con "auto" se devuelve el mismo, que sigue al sistema. */
    fun wrap(base: Context): Context {
        val lang = get(base)
        if (lang == AUTO) return base
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}

/**
 * Base de todas las pantallas: aplica el idioma elegido y, si se cambia en Ajustes, la pantalla que
 * estaba debajo se vuelve a crear al regresar a ella.
 */
open class LocaleActivity : AppCompatActivity() {
    private var shownLang: String? = null

    override fun attachBaseContext(newBase: Context) {
        shownLang = AppLanguage.get(newBase)
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onResume() {
        super.onResume()
        if (shownLang != AppLanguage.get(this)) recreate()
    }
}
