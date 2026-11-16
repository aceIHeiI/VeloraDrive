package com.iheii.veloradrive

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.audiofx.AudioEffect
import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/** Botones del launcher que el usuario puede reasignar manteniendo presionado 1 segundo. */
enum class Slot(@param:StringRes val title: Int, @param:DrawableRes val icon: Int) {
    APPS(R.string.slot_apps, R.drawable.ic_apps_color),
    CAMERA(R.string.slot_camera, R.drawable.ic_add_app),
    VIDEO(R.string.slot_video, R.drawable.ic_video),
    MUSIC(R.string.slot_music, R.drawable.ic_music),
    RADIO(R.string.slot_radio, R.drawable.ic_radio),
    NAV(R.string.slot_nav, R.drawable.ic_nav),
    EQ(R.string.slot_eq, R.drawable.ic_eq),
    PROJECTION(R.string.projection, R.drawable.ic_power),
    /** App de música Bluetooth del estéreo: deja el MCU en el canal BT para oír OfflineTune. */
    BT_MUSIC(R.string.slot_bt_music, R.drawable.ic_bt),
}

/** Lo que abre un botón: su intent y el nombre de la app que lo atiende. */
class SlotTarget(val intent: Intent, val label: String?, val custom: Boolean)

object SlotStore {
    private const val PREFS = "slots"

    /** Apps de enlace de las radios chinas más comunes, en orden de preferencia. */
    private val PROJECTION_PACKAGES = listOf(
        "com.zjinnova.zlink",
        "com.tlink.carplay",
        "com.syu.ms",
        "com.car17.carbitlink",
        "com.suding.speedplay",
        "com.google.android.projection.gearhead",
    )

    /** Ecualizadores nativos de los estéreos chinos más comunes. */
    private val EQ_PACKAGES = listOf("com.ts.main.eq", "com.syu.eq", "com.microntek.eq", "com.android.musicfx")

    // Los nombres de paquete de cámara, video y radio cambian según el fabricante del estéreo.
    // Estos son los habituales; si no hay ninguno, el botón abre el selector para elegir la app.
    private val VIDEO_PACKAGES = listOf("com.syu.video", "com.microntek.media", "org.videolan.vlc", "com.mxtech.videoplayer.ad")
    private val RADIO_PACKAGES = listOf("com.syu.radio", "com.microntek.radio", "com.ts.radio", "com.android.fmradio", "com.android.fm")
    private val NAV_PACKAGES = listOf("com.google.android.apps.maps", "com.waze")
    /** Música Bluetooth de fábrica en los estéreos chinos más comunes (FYT, Topway, MTCD, Autochips...). */
    private val BT_MUSIC_PACKAGES = listOf(
        "com.syu.bt", "com.tw.bt", "com.ts.bt", "com.microntek.btmusic", "com.microntek.bluetooth",
        "com.autochips.bluetooth", "com.nwd.bt.music", "com.fyt.bt", "com.android.bluetooth.music",
    )
    private val MUSIC_PACKAGES = listOf(
        "com.spotify.music",
        "deezer.android.app",
        "com.google.android.apps.youtube.music",
    )

    fun assigned(context: Context, slot: Slot): ComponentName? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(slot.name, null)
            ?.let { ComponentName.unflattenFromString(it) }

    fun assign(context: Context, slot: Slot, component: ComponentName?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (component == null) remove(slot.name) else putString(slot.name, component.flattenToString())
        }.apply()
    }

    fun resolve(context: Context, slot: Slot): SlotTarget? {
        val pm = context.packageManager
        assigned(context, slot)?.let { cn ->
            val info = runCatching { pm.getActivityInfo(cn, 0) }.getOrNull()
            if (info != null) {
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(cn)
                return SlotTarget(intent, info.loadLabel(pm).toString(), custom = true)
            }
        }
        if (slot == Slot.APPS) {
            return SlotTarget(Intent(context, AppsActivity::class.java), context.getString(R.string.all_apps), custom = false)
        }
        val intent = defaultIntent(context, slot) ?: return null
        return SlotTarget(intent, labelFor(pm, intent), custom = false)
    }

    private fun defaultIntent(context: Context, slot: Slot): Intent? {
        val pm = context.packageManager
        fun Intent.ifResolves() = takeIf { pm.resolveActivity(it, 0) != null }
        fun firstInstalled(packages: List<String>) =
            packages.firstNotNullOfOrNull { pm.getLaunchIntentForPackage(it) }
        return when (slot) {
            Slot.CAMERA -> null // "Otra app": el usuario la elige al primer toque
            Slot.BT_MUSIC -> firstInstalled(BT_MUSIC_PACKAGES) ?: btMusicByName(context)
            Slot.VIDEO -> firstInstalled(VIDEO_PACKAGES)
                ?: Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY).ifResolves()
            Slot.RADIO -> firstInstalled(RADIO_PACKAGES)
            Slot.NAV -> firstInstalled(NAV_PACKAGES)
                ?: Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MAPS).ifResolves()
            Slot.MUSIC -> firstInstalled(MUSIC_PACKAGES)
                ?: Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC).ifResolves()
            Slot.EQ -> firstInstalled(EQ_PACKAGES)
                ?: Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                    .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                    .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                    .ifResolves()
                ?: Intent(Settings.ACTION_SOUND_SETTINGS).ifResolves()
            Slot.PROJECTION -> firstInstalled(PROJECTION_PACKAGES)
            Slot.APPS -> null
        }
    }

    private fun labelFor(pm: PackageManager, intent: Intent): String? {
        val target = intent.selector ?: intent
        val info = pm.resolveActivity(target, 0) ?: return null
        // El selector del sistema ("Abrir con") no es un nombre útil.
        if (info.activityInfo.packageName == "android") return null
        return info.loadLabel(pm).toString()
    }

    /** Si el paquete no es de la lista: una app cuyo nombre diga Bluetooth / BT Music (no Ajustes). */
    /**
     * App del estéreo que recibe Android Auto (en GEARELEC se llama "AndroidAuto"; CPLink solo la
     * llama). Si no se encuentra por nombre, la app de enlace de CAR SYNC.
     */
    fun androidAutoReceiver(context: Context): Intent? {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val byName = runCatching { pm.queryIntentActivities(launcher, 0) }.getOrDefault(emptyList()).firstOrNull { ri ->
            val label = ri.loadLabel(pm).toString().lowercase().replace(" ", "")
            ri.activityInfo.packageName != context.packageName && label == "androidauto"
        }
        if (byName != null) {
            return Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(byName.activityInfo.packageName, byName.activityInfo.name)
        }
        return resolve(context, Slot.PROJECTION)?.intent?.let { Intent(it) }
    }

    private fun btMusicByName(context: Context): Intent? {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = runCatching { pm.queryIntentActivities(launcher, 0) }.getOrDefault(emptyList()).firstOrNull { ri ->
            val label = ri.loadLabel(pm).toString().lowercase()
            val pkg = ri.activityInfo.packageName
            pkg != context.packageName && pkg != "com.android.settings" &&
                (label.contains("bluetooth") || label.contains("bt music") || label.contains("música bt") ||
                    label.contains("musica bt") || label == "bt")
        } ?: return null
        return Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(match.activityInfo.packageName, match.activityInfo.name)
    }
}
