package com.iheii.veloradrive.link

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

enum class LinkStatus { DISCONNECTED, CONNECTING, CONNECTED, NO_TOKEN, BAD_TOKEN, NO_WIFI }

/** Todo lo que la pantalla y la depuración necesitan saber de la conexión. */
data class LinkUiState(
    val status: LinkStatus = LinkStatus.DISCONNECTED,
    val host: String? = null,
    val port: Int = CarProtocol.PORT,
    val state: CarState? = null,
    /** Cuándo llegó [state] (elapsedRealtime), para avanzar el progreso localmente. */
    val stateAt: Long = 0L,
    val art: Bitmap? = null,
    val lastMessage: String? = null,
    val lastMessageAt: Long = 0L,
    val error: String? = null,
    /** Último error visto; se conserva entre reintentos para poder leerlo en Depuración. */
    val lastError: String? = null,
) {
    val connected get() = status == LinkStatus.CONNECTED && state != null
    /** Se cayó la conexión pero se conserva el último estado y portada: la pantalla muestra "Reconectando". */
    val reconnecting get() = !connected && state != null &&
        status != LinkStatus.NO_TOKEN && status != LinkStatus.BAD_TOKEN
}

/**
 * Cliente WebSocket hacia OfflineTune (OkHttp). La IP del celular es el servidor DHCP de la red
 * (el hotspot). Si la red se cae, reintenta cada 3 s; si pasan 30 s sin ping, lo da por caído.
 * Solo vive mientras el launcher está a la vista ([start]/[stop]).
 */
class PhoneLink(context: Context, private val scope: CoroutineScope) {

    private val app = context.applicationContext
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    /**
     * Las portadas van por su propio cliente, con límite de tiempo: si el WiFi se corta a medias,
     * la descarga se cancela en vez de quedarse esperando para siempre en una conexión muerta.
     */
    private val artClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()
    private val artCache = LruCache<String, Bitmap>(4)

    private val _ui = MutableStateFlow(LinkUiState())
    val ui: StateFlow<LinkUiState> = _ui

    private val _videoPlay = MutableSharedFlow<CarVideoPlay>(extraBufferCapacity = 1)
    /** "Ver en el coche" desde el celular (mensaje video_play). */
    val videoPlay: SharedFlow<CarVideoPlay> = _videoPlay

    private var config = LinkConfig()
    private var socket: WebSocket? = null
    private var loop: Job? = null
    @Volatile private var lastPing = 0L
    @Volatile private var open = false

    fun setConfig(c: LinkConfig) {
        val changed = c.token != config.token || c.host != config.host
        config = c
        if (changed && loop != null) {
            stop()
            start()
        }
    }

    fun start() {
        if (loop != null) return
        watchNetwork()
        loop = scope.launch {
            while (isActive) {
                connectOnce()
                // Reconexión automática cada 3 s si se cae la red o el celular.
                delay(RETRY_MS)
            }
        }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        socket?.close(1000, null)
        socket = null
        open = false
        resetArt()
        unwatchNetwork()
        _ui.update { it.copy(status = LinkStatus.DISCONNECTED) }
    }

    private var netCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var watchStartedAt = 0L

    /**
     * Si cambia la red WiFi (p. ej. al abrir Android Auto) se descarta la IP vieja y se corta el socket
     * para redescubrir por UDP 8081 y /hello, en vez de esperar los 30 s del ping.
     */
    private fun watchNetwork() {
        if (netCallback != null) return
        watchStartedAt = SystemClock.elapsedRealtime()
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            private fun changed() {
                // Al registrarse, Android avisa de las redes que ya existen: se ignora ese primer aviso.
                if (SystemClock.elapsedRealtime() - watchStartedAt < NET_SETTLE_MS) return
                lastGood = null
                if (_ui.value.status == LinkStatus.CONNECTED) {
                    socket?.cancel()
                    open = false
                }
            }
            override fun onAvailable(network: Network) = changed()
            override fun onLost(network: Network) = changed()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = changed()
        }
        runCatching {
            cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), cb)
            netCallback = cb
        }
    }

    private fun unwatchNetwork() {
        val cb = netCallback ?: return
        netCallback = null
        runCatching { (app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(cb) }
    }

    /** Tras un corte: fuera las conexiones viejas y las portadas a medias, para pedirlas de nuevo. */
    private fun resetArt() {
        artClient.dispatcher.cancelAll()
        artClient.connectionPool.evictAll()
        artLoading = null
        shownArtUrl = null
    }

    /** Bass Boost al instante en la pantalla; el siguiente estado del celular lo confirma o lo corrige. */
    fun setBassLocal(on: Boolean) {
        _ui.update { s -> s.state?.let { s.copy(state = it.copy(bass = on)) } ?: s }
    }

    fun send(action: String, value: JsonPrimitive? = null, valueSec: Long? = null) {
        val ws = socket ?: return
        ws.send(CarProtocol.json.encodeToString(CarCommand(action = action, value = value, valueSec = valueSec)))
    }

    /** Abre el WebSocket y espera mientras siga vivo (con vigilancia de ping de 30 s). */
    private suspend fun connectOnce() {
        val token = config.token
        if (token == null) {
            _ui.update { it.copy(status = LinkStatus.NO_TOKEN, state = null) }
            return
        }
        val (host, port) = target() ?: run {
            _ui.update { it.copy(status = LinkStatus.NO_WIFI, host = null) }
            // Nadie respondió en la red: se espera un poco más antes de volver a revisarla.
            delay(NOT_FOUND_EXTRA_MS)
            return
        }
        _ui.update { it.copy(status = LinkStatus.CONNECTING, host = host, port = port) }
        val url = "ws://$host:$port${CarProtocol.WS_PATH}?token=$token"
        open = true
        lastPing = SystemClock.elapsedRealtime()
        socket = client.newWebSocket(Request.Builder().url(url).build(), Listener(host, port))
        while (open && scope.isActive) {
            delay(1_000)
            if (_ui.value.status == LinkStatus.CONNECTED &&
                SystemClock.elapsedRealtime() - lastPing > PING_TIMEOUT_MS
            ) {
                // 30 s sin ping: se considera desconectado y se reintenta.
                socket?.cancel()
                open = false
            }
        }
        socket = null
    }

    /**
     * Dónde está el celular: IP manual de Ajustes, si no, se pregunta por UDP en la red (sirve si el
     * WiFi lo pone el celular, el estéreo o un router) y, como último recurso, el servidor DHCP.
     */
    private suspend fun target(): Pair<String, Int>? = withContext(Dispatchers.IO) {
        val manual = config.host?.let { m ->
            m.substringBefore(':') to (m.substringAfter(':', "").toIntOrNull() ?: CarProtocol.PORT)
        }
        // IP manual: si no contesta (la IP cambió u otra red), se busca solo igual.
        if (manual != null && reachable(manual)) return@withContext manual
        // La última IP buena solo vale si de verdad contesta /hello (otra red puede reusar la misma IP).
        lastGood?.let { if (isPhone(it.first, it.second)) return@withContext it else lastGood = null }
        discover()?.let { return@withContext it }
        // El servidor DHCP solo vale si el estéreo de verdad está en esa red: cuando el estéreo pone
        // el WiFi (Android Auto inalámbrico), Android deja ahí la IP vieja del último hotspot.
        dhcpCandidates().firstOrNull { sameSubnet(it.first) && isPhone(it.first, it.second) }?.let { return@withContext it }
        scan()?.let { return@withContext it }
        manual
    }

    /** Último lugar donde sí respondió el celular: se prueba primero al reconectar. */
    @Volatile private var lastGood: Pair<String, Int>? = null

    private fun reachable(target: Pair<String, Int>): Boolean = runCatching {
        java.net.Socket().use { it.connect(java.net.InetSocketAddress(target.first, target.second), REACH_TIMEOUT_MS) }
        true
    }.getOrDefault(false)

    /** IPv4 propias del estéreo en redes locales (su hotspot, o la red a la que está conectado). */
    private fun localIpv4(): List<String> = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<java.net.Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList())

    private fun sameSubnet(ip: String) = localIpv4().any { it.substringBeforeLast('.') == ip.substringBeforeLast('.') }

    /**
     * Si nadie respondió por UDP: se revisa cada dirección de la red del estéreo (de 64 en 64) en el
     * puerto 8080, preguntando por /hello. Solo OfflineTune Car responde "HERE"; tarda 1 o 2 s.
     */
    private suspend fun scan(): Pair<String, Int>? = kotlinx.coroutines.coroutineScope {
        val own = localIpv4()
        val hosts = own.map { it.substringBeforeLast('.') }.distinct()
            .flatMap { base -> (1..254).map { "$base.$it" } }
            .filter { it !in own }
        for (chunk in hosts.chunked(SCAN_PARALLEL)) {
            val found = chunk.map { ip ->
                async(Dispatchers.IO) { ip.takeIf { isPhone(it, CarProtocol.PORT) } }
            }.awaitAll().firstOrNull { it != null }
            if (found != null) return@coroutineScope found to CarProtocol.PORT
        }
        null
    }

    private fun isPhone(host: String, port: Int): Boolean = runCatching {
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress(host, port), SCAN_CONNECT_MS)
            s.soTimeout = SCAN_READ_MS
            s.getOutputStream().write("GET ${CarProtocol.HELLO_PATH} HTTP/1.0\r\nHost: $host\r\n\r\n".toByteArray())
            val input = s.getInputStream()
            val buf = ByteArray(1024)
            var n = 0
            while (n < buf.size) {
                val r = input.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
                if (String(buf, 0, n).contains(CarProtocol.HERE)) return@use true
            }
            false
        }
    }.getOrDefault(false)

    private suspend fun discover(): Pair<String, Int>? = withContext(Dispatchers.IO) {
        runCatching {
            java.net.DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = DISCOVERY_WAIT_MS
                val msg = CarProtocol.DISCOVER.toByteArray()
                broadcastAddresses().forEach { addr ->
                    runCatching { socket.send(java.net.DatagramPacket(msg, msg.size, addr, CarProtocol.DISCOVERY_PORT)) }
                }
                val buf = ByteArray(64)
                val reply = java.net.DatagramPacket(buf, buf.size)
                socket.receive(reply)
                val text = String(reply.data, 0, reply.length)
                if (!text.startsWith(CarProtocol.HERE)) return@use null
                val port = text.substringAfter(' ', "").trim().toIntOrNull() ?: CarProtocol.PORT
                reply.address.hostAddress?.let { it to port }
            }
        }.getOrNull()
    }

    /** Difusión general y la de cada red local (algunos estéreos solo dejan pasar la segunda). */
    private fun broadcastAddresses(): List<java.net.InetAddress> {
        val list = mutableListOf<java.net.InetAddress>(java.net.InetAddress.getByName("255.255.255.255"))
        runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses }
                .mapNotNull { it.broadcast }
                .forEach { if (it !in list) list += it }
        }
        return list
    }

    /**
     * Servidor DHCP y puerta de enlace de la red actual. Algunos estéreos chinos ponen el celular en
     * uno y no en el otro, por eso se prueban los dos (cada uno se valida con /hello antes de usarse).
     */
    @Suppress("DEPRECATION")
    @SuppressLint("WifiManagerLeak")
    private fun dhcpCandidates(): List<Pair<String, Int>> {
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val dhcp = runCatching { wifi.dhcpInfo }.getOrNull() ?: return emptyList()
        fun ip(addr: Int) = "${addr and 0xFF}.${addr shr 8 and 0xFF}.${addr shr 16 and 0xFF}.${addr shr 24 and 0xFF}"
        return listOf(dhcp.serverAddress, dhcp.gateway).filter { it != 0 }.distinct().map { ip(it) to CarProtocol.PORT }
    }

    private inner class Listener(private val host: String, private val port: Int) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            lastPing = SystemClock.elapsedRealtime()
            lastGood = host to port
            _ui.update { it.copy(status = LinkStatus.CONNECTED, error = null, lastError = null) }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val now = SystemClock.elapsedRealtime()
            val type = runCatching { CarProtocol.json.decodeFromString<CarEnvelope>(text).type }.getOrNull()
            _ui.update { it.copy(lastMessage = text.take(300), lastMessageAt = now) }
            when (type) {
                "ping" -> {
                    lastPing = now
                    webSocket.send(CarProtocol.json.encodeToString(CarPing(type = "pong")))
                }
                "state" -> {
                    lastPing = now
                    val st = runCatching { CarProtocol.json.decodeFromString<CarState>(text) }.getOrNull() ?: return
                    _ui.update { it.copy(state = st, stateAt = now, status = LinkStatus.CONNECTED) }
                    st.bass?.let { com.iheii.veloradrive.CockpitPrefs.setBassLast(app, it) }
                    loadArt(st.albumArtUrl, host, port)
                }
                "video_play" -> {
                    val vp = runCatching { CarProtocol.json.decodeFromString<CarVideoPlay>(text) }.getOrNull() ?: return
                    _videoPlay.tryEmit(vp)
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
            finish(if (code == 1008) LinkStatus.BAD_TOKEN else LinkStatus.DISCONNECTED, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            finish(if (code == 1008) LinkStatus.BAD_TOKEN else LinkStatus.DISCONNECTED, reason)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            finish(LinkStatus.DISCONNECTED, t.message ?: t.javaClass.simpleName)
        }

        private fun finish(status: LinkStatus, reason: String?) {
            open = false
            resetArt()
            val why = reason?.takeIf { r -> r.isNotBlank() }
            // Se conserva el último estado y la portada (salvo código malo): la pantalla muestra "Reconectando".
            _ui.update {
                it.copy(
                    status = status,
                    state = if (status == LinkStatus.BAD_TOKEN) null else it.state,
                    error = why,
                    lastError = why ?: it.lastError,
                )
            }
        }
    }

    /**
     * Portada por HTTP. La URL lleva ?id=<canción>, así cambia con cada pista y no se queda la
     * vieja. Si la descarga falla, la resincronización de 15 s la vuelve a pedir; si ya está en
     * caché, no se descarga otra vez.
     */
    private fun loadArt(url: String?, host: String, port: Int) {
        if (url == null) {
            _ui.update { it.copy(art = null) }
            return
        }
        // Se usa la IP con la que nos conectamos (sirve también con redirecciones de puertos).
        val fixed = url.toHttpUrlOrNull()?.newBuilder()?.host(host)?.port(port)?.build()?.toString() ?: url
        artCache.get(fixed)?.let { bmp -> _ui.update { it.copy(art = bmp) }; return }
        // Canción nueva: fuera la portada anterior mientras llega la suya (nunca se queda la vieja).
        if (shownArtUrl != fixed) {
            shownArtUrl = fixed
            _ui.update { it.copy(art = null) }
        }
        if (artLoading == fixed) return
        artLoading = fixed
        scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    artClient.newCall(Request.Builder().url(fixed).build()).execute().use { r ->
                        if (!r.isSuccessful) null else r.body?.bytes()?.let { decode(it) }
                    }
                }.getOrNull()
            }
            if (artLoading == fixed) artLoading = null
            if (bmp != null) {
                artCache.put(fixed, bmp)
                if (_ui.value.state?.albumArtUrl == url) _ui.update { it.copy(art = bmp) }
            }
        }
    }

    @Volatile private var artLoading: String? = null
    @Volatile private var shownArtUrl: String? = null

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 320) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private companion object {
        private const val NOT_FOUND_EXTRA_MS = 3_000L
        private const val REACH_TIMEOUT_MS = 1_200
        private const val SCAN_PARALLEL = 64
        private const val SCAN_CONNECT_MS = 350
        private const val SCAN_READ_MS = 1_200
        const val RETRY_MS = 3_000L
        const val PING_TIMEOUT_MS = 30_000L
        const val DISCOVERY_WAIT_MS = 1_500
        private const val NET_SETTLE_MS = 3_000L
    }
}
