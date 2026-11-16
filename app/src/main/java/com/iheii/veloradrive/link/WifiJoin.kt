package com.iheii.veloradrive.link

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build

/**
 * Hotspot fijo del celular como red conocida, para que el estéreo se conecte solo. Android 10+ usa
 * una sugerencia de red (el sistema la pide aprobar una vez); antes se agrega la red directamente.
 * No depende de Google Play Services, así funciona en estéreos chinos.
 */
object WifiJoin {
    private var lastSsid: String? = null

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun suggest(context: Context, config: LinkConfig) {
        val ssid = config.ssid ?: return
        val pass = config.password
        if (ssid == lastSsid) return
        lastSsid = ssid
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val s = WifiNetworkSuggestion.Builder().setSsid(ssid).apply {
                    if (pass != null) setWpa2Passphrase(pass)
                }.setIsAppInteractionRequired(false).build()
                wifi.removeNetworkSuggestions(listOf(s))
                wifi.addNetworkSuggestions(listOf(s))
            } else {
                val conf = WifiConfiguration().apply {
                    SSID = "\"$ssid\""
                    if (pass != null) preSharedKey = "\"$pass\"" else allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                }
                val id = wifi.addNetwork(conf)
                if (id != -1) wifi.enableNetwork(id, false)
            }
        }
    }
}
