package com.iheii.veloradrive

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Normalizer

class AppEntry(val label: String, val component: ComponentName, val icon: Drawable) {
    val key: String = normalize(label)
}

fun normalize(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace("\\p{Mn}+".toRegex(), "").lowercase()

/**
 * Lista de apps instaladas con sus iconos, cargada en Dispatchers.IO y guardada en caché.
 * El selector de slots abre al instante; la caché se invalida cuando se instala o borra algo.
 */
object AppRepository {

    @Volatile private var cache: List<AppEntry>? = null
    private var packageSequence = -1

    suspend fun load(context: Context, forceRefresh: Boolean = false): List<AppEntry> {
        val app = context.applicationContext
        invalidateIfChanged(app)
        cache?.takeIf { !forceRefresh }?.let { return it }
        return withContext(Dispatchers.IO) { query(app) }.also { cache = it }
    }

    /** Libera los iconos (por ejemplo, cuando el sistema pide memoria). */
    fun clear() {
        cache = null
    }

    private fun invalidateIfChanged(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val changed = context.packageManager.getChangedPackages(maxOf(packageSequence, 0))
        if (changed != null) {
            if (packageSequence >= 0) cache = null
            packageSequence = changed.sequenceNumber
        }
    }

    private fun query(app: Context): List<AppEntry> {
        val pm = app.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != app.packageName }
            .map {
                AppEntry(
                    it.loadLabel(pm).toString(),
                    ComponentName(it.activityInfo.packageName, it.activityInfo.name),
                    it.loadIcon(pm),
                )
            }
            .sortedBy { it.key }
    }
}
