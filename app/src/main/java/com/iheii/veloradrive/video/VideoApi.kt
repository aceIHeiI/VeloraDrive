package com.iheii.veloradrive.video

import android.content.Context
import com.iheii.veloradrive.link.CarProtocol
import com.iheii.veloradrive.link.CarVideo
import com.iheii.veloradrive.link.LinkPrefs
import com.iheii.veloradrive.link.PhoneLinkHub
import com.iheii.veloradrive.link.VideoInfo
import com.iheii.veloradrive.link.VideoList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Rutas HTTP de video de OfflineTune Car (mismo host y puerto que el WebSocket). Todas llevan
 * ?token=<código de 4 dígitos>. Se arma con la IP de la conexión activa.
 */
class VideoApi(val host: String, val port: Int, private val token: String) {

    private fun url(path: String, vararg params: Pair<String, String?>): HttpUrl =
        HttpUrl.Builder().scheme("http").host(host).port(port).encodedPath(path).apply {
            params.forEach { (k, v) -> if (v != null) addQueryParameter(k, v) }
            addQueryParameter("token", token)
        }.build()

    suspend fun library(tab: String, playlistId: String? = null): VideoList =
        get(url(CarVideo.LIBRARY, "tab" to tab, "id" to playlistId)) { CarProtocol.json.decodeFromString<VideoList>(it) }

    suspend fun search(q: String): VideoList =
        get(url(CarVideo.SEARCH, "q" to q)) { CarProtocol.json.decodeFromString<VideoList>(it) }

    suspend fun info(id: String): VideoInfo =
        get(url(CarVideo.INFO, "id" to id)) { CarProtocol.json.decodeFromString<VideoInfo>(it) }

    fun thumbUrl(id: String) = url(CarVideo.THUMB, "id" to id).toString()
    fun streamUrl(id: String, height: Int, codec: String) =
        url(CarVideo.STREAM, "id" to id, "height" to height.toString(), "codec" to codec).toString()
    fun audioUrl(id: String) = url(CarVideo.AUDIO, "id" to id).toString()

    private suspend fun <T> get(url: HttpUrl, parse: (String) -> T): T = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (r.code == 401) throw IOException("401")
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            parse(r.body?.string().orEmpty())
        }
    }

    companion object {
        /** Cliente para JSON y miniaturas (el video usa el suyo, con tiempos más largos). */
        val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(25, TimeUnit.SECONDS)
                .build()
        }

        /** null si aún no hay celular encontrado o falta el código. */
        suspend fun current(context: Context): VideoApi? {
            val ui = PhoneLinkHub.ui(context).value
            val host = ui.host ?: return null
            val token = LinkPrefs.flow(context.applicationContext).first().token ?: return null
            return VideoApi(host, ui.port, token)
        }
    }
}
