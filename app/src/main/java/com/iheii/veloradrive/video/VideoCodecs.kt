package com.iheii.veloradrive.video

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import com.iheii.veloradrive.link.VideoQuality

/**
 * Qué calidades puede decodificar ESTE estéreo. Se pregunta a MediaCodecList por el tamaño 16:9 de
 * cada altura (y los fps). Los decodificadores por software (OMX.google.*, c2.android.*) existen en
 * todos los Android y dicen aguantar 1080p, pero en un Allwinner barato se trabarían: solo cuentan
 * hasta 480p. AVC (H.264) es el preferido porque casi todos los estéreos lo tienen por hardware.
 */
object VideoCodecs {

    const val MAX_HEIGHT = 1080
    private const val SOFTWARE_MAX_HEIGHT = 480

    private class Decoder(val caps: MediaCodecInfo.VideoCapabilities, val hardware: Boolean)

    private val decoders = HashMap<String, List<Decoder>>()
    private val cache = HashMap<String, Boolean>()

    fun mimeOf(codec: String): String? = when (codec.lowercase()) {
        "avc", "h264" -> "video/avc"
        "vp9" -> "video/x-vnd.on2.vp9"
        "av1" -> "video/av01"
        "hevc", "h265" -> "video/hevc"
        else -> null
    }

    @Synchronized
    private fun decodersFor(mime: String): List<Decoder> = decoders.getOrPut(mime) {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) } }
                .mapNotNull { info ->
                    val caps = runCatching { info.getCapabilitiesForType(mime).videoCapabilities }.getOrNull()
                        ?: return@mapNotNull null
                    Decoder(caps, isHardware(info))
                }
        }.getOrDefault(emptyList())
    }

    private fun isHardware(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return info.isHardwareAccelerated && !info.isSoftwareOnly
        val n = info.name.lowercase()
        return !(n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.startsWith("omx.ffmpeg.") || n.contains(".sw."))
    }

    /** ¿Se puede decodificar [height]p en [codec] a [fps]? */
    @Synchronized
    fun canDecode(codec: String, height: Int, fps: Int = 30): Boolean {
        if (height > MAX_HEIGHT) return false
        val key = "$codec:$height:$fps"
        cache[key]?.let { return it }
        val mime = mimeOf(codec)
        val ok = mime != null && decodersFor(mime).any { d ->
            if (!d.hardware && height > SOFTWARE_MAX_HEIGHT) return@any false
            val w = ((height * 16 / 9) + 1) and 1.inv() // par, como piden los decodificadores
            runCatching {
                if (fps > 0) d.caps.areSizeAndRateSupported(w, height, fps.toDouble())
                else d.caps.isSizeSupported(w, height)
            }.getOrDefault(false)
        }
        cache[key] = ok
        return ok
    }

    fun canDecode(q: VideoQuality) = canDecode(q.codec, q.height, q.fps)

    /** Altura máxima en AVC a 30 fps (para ofrecer o no 1080p en Ajustes). */
    fun maxAvcHeight(): Int = listOf(1080, 720, 480, 360).firstOrNull { canDecode("avc", it, 30) } ?: 0

    /**
     * Una calidad por altura (de mayor a menor), solo las que este estéreo decodifica. Por altura:
     * primero AVC, luego la que ya está descargada en el celular, luego la que trae el audio adentro.
     */
    fun playable(qualities: List<VideoQuality>): List<VideoQuality> =
        qualities.filter { it.height in 1..MAX_HEIGHT && canDecode(it) }
            .groupBy { it.height }
            .map { (_, list) ->
                list.sortedWith(
                    compareBy<VideoQuality>({ it.codec.lowercase() != "avc" }, { !it.local }, { !it.muxed }, { it.fps })
                ).first()
            }
            .sortedByDescending { it.height }

    /** "Automática": la más alta ≤ [cap]; si ninguna cabe, la más baja que haya. */
    fun auto(playable: List<VideoQuality>, cap: Int): VideoQuality? =
        playable.firstOrNull { it.height <= cap } ?: playable.lastOrNull()
}
