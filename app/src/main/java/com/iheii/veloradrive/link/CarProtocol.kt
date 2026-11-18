package com.iheii.veloradrive.link

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Protocolo con el launcher del coche (Velora Drive), versión 1. El mismo archivo vive en las dos
 * apps. Por WiFi solo viajan órdenes y estado; el audio sale por Bluetooth A2DP.
 */
object CarProtocol {
    const val VERSION = 1
    const val PORT = 8080
    const val WS_PATH = "/ws"
    const val COVER_PATH = "/cover.jpg"
    /** El coche revisa su red preguntando aquí; el celular responde "HERE <puerto>". */
    const val HELLO_PATH = "/hello"
    const val PING_MS = 15_000L
    const val RESYNC_MS = 15_000L

    /** Descubrimiento por UDP: el coche difunde DISCOVER y el celular responde "HERE <puerto>". */
    const val DISCOVERY_PORT = 8081
    const val DISCOVER = "VD_DISCOVER_1"
    const val HERE = "VD_HERE_1"

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
}

/** Sobre común: se lee primero "type" para saber qué mensaje es. */
@Serializable
data class CarEnvelope(val v: Int = CarProtocol.VERSION, val type: String)

/**
 * Coche → celular. action: play, pause, toggle, next, previous, set_volume, seek, audio_kick,
 * set_preset (value: normal, pop, rock, hiphop, dance, jazz, classical, heavymetal, folk, flat),
 * set_bass (value: true/false, Bass Boost aparte del preset).
 * video_position (value: id del video, value_sec: segundo) cada 5 s mientras el estéreo reproduce un video,
 * video_stop cuando el video del estéreo se cierra.
 */
@Serializable
data class CarCommand(
    val v: Int = CarProtocol.VERSION,
    val type: String = "command",
    val action: String,
    val value: kotlinx.serialization.json.JsonPrimitive? = null,
    @SerialName("value_sec") val valueSec: Long? = null,
)

/** Celular → coche. Se manda al cambiar algo de verdad y cada 15 s como resincronización. */
@Serializable
data class CarState(
    val v: Int = CarProtocol.VERSION,
    val type: String = "state",
    @SerialName("track_id") val trackId: String? = null,
    val title: String? = null,
    val artist: String? = null,
    @SerialName("album_art_url") val albumArtUrl: String? = null,
    @SerialName("is_playing") val isPlaying: Boolean = false,
    @SerialName("position_sec") val positionSec: Long = 0,
    @SerialName("duration_sec") val durationSec: Long = 0,
    val volume: Int = 100,
    val preset: String? = null,
    /**
     * Calidad del archivo que suena en el celular, solo informativa. El launcher no la muestra.
     */
    val quality: Int = 1,
    val source: String = "OfflineTune",
    /** Nombre del celular, por si el coche aún no tiene uno guardado. */
    val device: String? = null,
    /** true si el audio está saliendo por Bluetooth A2DP. */
    @SerialName("bt_audio") val btAudio: Boolean = false,
    /** true con Android Auto: el audio va por el estéreo, no por Bluetooth. */
    @SerialName("android_auto") val androidAuto: Boolean = false,
    /** Hora del celular (epoch ms) al armar el mensaje: Velora la usa en su reloj. */
    @SerialName("phone_time_ms") val phoneTimeMs: Long? = null,
    /** Zona horaria del celular, p. ej. "America/Merida". */
    @SerialName("phone_tz") val phoneTz: String? = null,
    /** Batería del celular 0-100. */
    val battery: Int? = null,
    val charging: Boolean? = null,
    /** Minutos estimados para carga completa (solo si está cargando y Android lo sabe). */
    @SerialName("charge_minutes") val chargeMinutes: Int? = null,
    /** Tipo de red móvil: "5G", "4G", "3G", "2G" o null sin datos móviles. */
    val network: String? = null,
    /** Compañía, p. ej. "Telcel". */
    val carrier: String? = null,
    /** Barras de señal 0-4. */
    @SerialName("signal_level") val signalLevel: Int? = null,
    /** Datos móviles usados hoy en MB (null si no hay permiso de uso). */
    @SerialName("data_today_mb") val dataTodayMb: Long? = null,
    /** Bass Boost encendido en el celular (independiente del preset). */
    val bass: Boolean? = null,
)

@Serializable
data class CarPing(val v: Int = CarProtocol.VERSION, val type: String)

// ------------------------------------------------------------------ video (parte 2)

/**
 * Video por HTTP en el mismo puerto. TODAS las rutas piden ?token=<código de 4 dígitos> (401 sin él).
 *  GET /video/library?tab=downloaded|favorites|history|playlists|now   (tab=playlist&id=<playlist_id>)
 *  GET /video/search?q=<texto>            → VideoList
 *  GET /video/info?id=<id>                → VideoInfo (calidades que puede mandar el celular)
 *  GET /video/thumb?id=<id>               → JPEG
 *  GET /video/stream?id=&height=&codec=avc|vp9|av1  → pista de video (soporta Range). Si la calidad es
 *      "muxed" (archivo descargado con audio adentro) ya trae el audio y no se pide /video/audio.
 *  GET /video/audio?id=                   → pista de audio AAC/M4A (soporta Range)
 * El celular hace de puente: el estéreo no necesita internet.
 */
object CarVideo {
    const val LIBRARY = "/video/library"
    const val SEARCH = "/video/search"
    const val INFO = "/video/info"
    const val THUMB = "/video/thumb"
    const val STREAM = "/video/stream"
    const val AUDIO = "/video/audio"
}

@Serializable
data class VideoItem(
    val id: String,
    val title: String,
    val channel: String? = null,
    @SerialName("duration_sec") val durationSec: Long = 0,
    val downloaded: Boolean = false,
    @SerialName("downloaded_height") val downloadedHeight: Int? = null,
    /** Solo en tab=playlists: la lista se abre con tab=playlist&id=<playlist_id>. */
    @SerialName("playlist_id") val playlistId: String? = null,
    val count: Int? = null,
)

@Serializable
data class VideoList(val items: List<VideoItem> = emptyList())

@Serializable
data class VideoQuality(
    val height: Int,
    /** "avc" (H.264, lo que leen casi todos los estéreos), "vp9" o "av1". */
    val codec: String,
    val fps: Int = 30,
    /** true = archivo descargado en el celular (no gasta datos). */
    val local: Boolean = false,
    /** true = la pista de video ya trae el audio adentro. */
    val muxed: Boolean = false,
)

@Serializable
data class VideoInfo(
    val id: String,
    val title: String,
    val channel: String? = null,
    @SerialName("duration_sec") val durationSec: Long = 0,
    val qualities: List<VideoQuality> = emptyList(),
)

/** Celular → coche por el WebSocket: "Ver en el coche". */
@Serializable
data class CarVideoPlay(
    val v: Int = CarProtocol.VERSION,
    val type: String = "video_play",
    val id: String,
    val title: String,
    @SerialName("position_sec") val positionSec: Long = 0,
)

/** Celular → coche: el celular cerró el video (X del mini reproductor); Velora lo cierra también. */
@Serializable
data class CarVideoClose(
    val v: Int = CarProtocol.VERSION,
    val type: String = "video_close",
)

/** Celular → coche: pausar o reanudar el video que muestra Velora (action: play, pause, toggle). */
@Serializable
data class CarVideoControl(
    val v: Int = CarProtocol.VERSION,
    val type: String = "video_control",
    val action: String = "toggle",
)
