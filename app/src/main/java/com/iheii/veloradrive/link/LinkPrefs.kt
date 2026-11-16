package com.iheii.veloradrive.link

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Datos para conectarse a OfflineTune en el celular. */
data class LinkConfig(
    /** Código de 4 dígitos que muestra el celular; se escribe una vez. */
    val token: String? = null,
    /** Hotspot fijo del celular, para conectarse solo a esa red conocida. */
    val ssid: String? = null,
    val password: String? = null,
    /** IP manual (solo para diagnóstico); vacío = se toma del DHCP. */
    val host: String? = null,
)

private val Context.linkStore by preferencesDataStore(name = "phone_link")

object LinkPrefs {
    private val TOKEN = stringPreferencesKey("link_token")
    private val SSID = stringPreferencesKey("link_ssid")
    private val PASSWORD = stringPreferencesKey("link_password")
    private val HOST = stringPreferencesKey("link_host")

    fun flow(context: Context): Flow<LinkConfig> = context.linkStore.data.map { p ->
        LinkConfig(
            token = p[TOKEN]?.takeIf { it.isNotBlank() },
            ssid = p[SSID]?.takeIf { it.isNotBlank() },
            password = p[PASSWORD]?.takeIf { it.isNotBlank() },
            host = p[HOST]?.takeIf { it.isNotBlank() },
        )
    }

    suspend fun save(context: Context, config: LinkConfig) {
        context.linkStore.edit { p ->
            p[TOKEN] = config.token.orEmpty().trim()
            p[SSID] = config.ssid.orEmpty().trim()
            p[PASSWORD] = config.password.orEmpty()
            p[HOST] = config.host.orEmpty().trim()
        }
    }
}
