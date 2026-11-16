package com.iheii.veloradrive

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Estado de la conexión OBD2 que ve la UI. */
enum class ObdStatus {
    /** OBD2 apagado en Ajustes (o sin escáner elegido / sin permiso). */
    OFF,
    /** Buscando el escáner o reintentando. */
    WAITING,
    /** ELM327 conectado y mandando datos reales. */
    LIVE,
    /** Conectado, pero con el motor apagado (batería < 11.8 V): solo se lee el voltaje. */
    LOW_VOLTAGE,
    /** Datos de demostración (sin Bluetooth). */
    DEMO,
}

/**
 * Última lectura del escáner. null = sin dato (la UI muestra "--", nunca un valor inventado):
 * todavía no llegó, o el auto no soporta ese PID.
 */
data class ObdReading(
    val status: ObdStatus = ObdStatus.OFF,
    val rpm: Int? = null,
    val speedKmh: Int? = null,
    val coolantC: Int? = null,
    val volts: Float? = null,
    val loadPct: Int? = null,
    val throttlePct: Int? = null,
    val intakeC: Int? = null,
    val fuelPct: Int? = null,
    /** Códigos de falla (P0300, C1234...). null = todavía no se leyeron. */
    val dtc: List<String>? = null,
    /** Leyendo los códigos de falla en este momento. */
    val dtcBusy: Boolean = false,
) {
    /** Hay escáner (o demo) mandando datos. */
    val connected get() = status == ObdStatus.LIVE || status == ObdStatus.LOW_VOLTAGE || status == ObdStatus.DEMO
    val lowVoltagePause get() = status == ObdStatus.LOW_VOLTAGE
}

/**
 * Lector OBD2 para escáneres ELM327 Bluetooth clásicos (SPP/RFCOMM).
 * Secuencia: ATZ, ATE0, ATL0, ATS0, ATSP0 y luego un ciclo cada 1 s con ATRV (voltaje),
 * 010C (RPM), 010D (velocidad), 0105 (refrigerante), 0104 (carga), 0111 (acelerador),
 * 010F (aire de admisión) y 012F (combustible).
 *
 * Muchos autos no soportan todos los PIDs: si uno responde NO DATA varias veces seguidas se deja
 * de preguntar por 2 minutos y su valor queda en null.
 *
 * Códigos de falla (modo 03): se leen al conectar y cuando se piden con [requestDtc]. Nunca se
 * borran (modo 04 no se usa).
 *
 * Protección de batería: si el voltaje baja de 11.8 V (motor apagado) deja de consultar la ECU y
 * solo revisa ATRV cada 30 s, para no mantener despierta la computadora del auto.
 */
class ObdManager(private val scope: CoroutineScope, private val onReading: (ObdReading) -> Unit) {

    private var job: Job? = null
    @Volatile private var socket: BluetoothSocket? = null
    private val dtcWanted = AtomicBoolean(false)

    val isRunning get() = job?.isActive == true

    /** Un PID con su decodificación y su contador de NO DATA (por conexión). */
    private class Pid(
        val cmd: String,
        val bytes: Int,
        val decode: (IntArray) -> Int,
        val put: (ObdReading, Int?) -> ObdReading,
    ) {
        val header = "4" + cmd.substring(1)
        var misses = 0
        var skipUntil = 0L
    }

    private fun newPids() = listOf(
        Pid("010C", 2, { (it[0] * 256 + it[1]) / 4 }) { r, v -> r.copy(rpm = v) },
        Pid("010D", 1, { it[0] }) { r, v -> r.copy(speedKmh = v) },
        Pid("0105", 1, { it[0] - 40 }) { r, v -> r.copy(coolantC = v) },
        Pid("0104", 1, { it[0] * 100 / 255 }) { r, v -> r.copy(loadPct = v) },
        Pid("0111", 1, { it[0] * 100 / 255 }) { r, v -> r.copy(throttlePct = v) },
        Pid("010F", 1, { it[0] - 40 }) { r, v -> r.copy(intakeC = v) },
        Pid("012F", 1, { it[0] * 100 / 255 }) { r, v -> r.copy(fuelPct = v) },
    )

    @SuppressLint("MissingPermission")
    fun start(deviceAddress: String) {
        stop()
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val adapter = BluetoothAdapter.getDefaultAdapter() ?: return@launch
                    val device = adapter.getRemoteDevice(deviceAddress)
                    adapter.cancelDiscovery()
                    val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
                    socket = s
                    s.connect()
                    val input = s.inputStream
                    val output = s.outputStream
                    for (cmd in listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATSP0")) send(output, input, cmd)
                    dtcWanted.set(true) // al conectar se revisan los códigos de falla
                    poll(input, output)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    // Escáner apagado o fuera de alcance: reintenta sin saturar el Bluetooth.
                    closeSocket()
                    publish(ObdReading(status = ObdStatus.WAITING))
                    delay(RETRY_MS)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        closeSocket()
    }

    /** Vuelve a leer los códigos de falla en el próximo ciclo. */
    fun requestDtc() = dtcWanted.set(true)

    private suspend fun poll(input: InputStream, output: OutputStream) {
        val pids = newPids()
        var isCan: Boolean? = null
        var r = ObdReading(status = ObdStatus.LIVE)
        while (currentCoroutineContext().isActive) {
            val t0 = SystemClock.elapsedRealtime()
            val volts = parseVolts(send(output, input, "ATRV"))
            if (volts != null && volts < LOW_VOLTAGE) {
                r = ObdReading(status = ObdStatus.LOW_VOLTAGE, volts = volts, dtc = r.dtc)
                publish(r)
                delay(LOW_VOLTAGE_RETRY_MS)
                continue
            }
            var next = r.copy(status = ObdStatus.LIVE, volts = volts ?: r.volts)
            for (p in pids) {
                val now = SystemClock.elapsedRealtime()
                if (now < p.skipUntil) continue
                val raw = send(output, input, p.cmd)
                val data = parsePid(raw, p.header, p.bytes)
                if (data != null) {
                    p.misses = 0
                    next = p.put(next, p.decode(data))
                } else if (clean(raw).contains("NODATA")) {
                    // El auto no tiene ese dato: tras varias respuestas iguales se deja de pedir.
                    if (++p.misses >= MAX_NO_DATA) {
                        p.misses = 0
                        p.skipUntil = now + SKIP_PID_MS
                        next = p.put(next, null)
                    }
                }
                // Cualquier otro error pasajero conserva el último valor (sin parpadeos a "--").
            }
            if (dtcWanted.getAndSet(false)) {
                publish(next.copy(dtcBusy = true))
                if (isCan == null) isCan = detectCan(output, input)
                val codes = readDtc(output, input, isCan)
                next = next.copy(dtc = codes ?: next.dtc ?: emptyList(), dtcBusy = false)
            }
            r = next
            publish(r)
            delay((POLL_MS - (SystemClock.elapsedRealtime() - t0)).coerceAtLeast(MIN_GAP_MS))
        }
    }

    private suspend fun publish(r: ObdReading) {
        withContext(Dispatchers.Main) { onReading(r) }
    }

    /** Envía un comando y lee hasta el prompt '>' del ELM327. */
    private fun send(output: OutputStream, input: InputStream, cmd: String): String {
        output.write("$cmd\r".toByteArray())
        output.flush()
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0 || c == '>'.code) break
            sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun clean(raw: String) = raw.replace(" ", "").replace("\r", "").replace("\n", "").uppercase()

    private fun parseVolts(raw: String): Float? =
        Regex("""(\d{1,2}\.\d)V""").find(raw.replace(" ", ""))?.groupValues?.get(1)?.toFloatOrNull()

    private fun parsePid(raw: String, header: String, bytes: Int): IntArray? {
        val clean = clean(raw)
        val i = clean.indexOf(header)
        if (i < 0 || clean.length < i + header.length + bytes * 2) return null
        val out = IntArray(bytes)
        for (k in 0 until bytes) {
            val start = i + header.length + k * 2
            out[k] = clean.substring(start, start + 2).toIntOrNull(16) ?: return null
        }
        return out
    }

    /** Protocolos 6-9 (y A-C, J1939/usuario) son CAN: cambian el formato de la respuesta del modo 03. */
    private fun detectCan(output: OutputStream, input: InputStream): Boolean {
        val p = clean(send(output, input, "ATDPN")).removePrefix("A").firstOrNull() ?: return true
        return p in '6'..'9' || p in 'A'..'C'
    }

    /** Modo 03: códigos guardados. null = respuesta ilegible (se conserva lo anterior). */
    private fun readDtc(output: OutputStream, input: InputStream, isCan: Boolean): List<String>? {
        val raw = send(output, input, "03").uppercase()
        if (clean(raw).contains("NODATA")) return emptyList()
        val lines = raw.split('\r', '\n')
            .map { it.replace(" ", "").trim() }
            .filter { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == ':' } }
        val codes = LinkedHashSet<String>()
        var sawResponse = false
        if (isCan) {
            // Una ECU = un mensaje: empieza con "43" (o "0:43" si es multitrama), sigue con "1:", "2:".
            val messages = mutableListOf<StringBuilder>()
            for (line in lines) {
                if (line.length == 3 && !line.contains(':')) continue // largo total de la multitrama
                val frame = Regex("""^[0-9A-F]:""").find(line)
                val body = if (frame != null) line.substring(2) else line
                when {
                    body.startsWith("43") && (frame == null || line.startsWith("0:")) -> messages += StringBuilder(body)
                    frame != null && messages.isNotEmpty() -> messages.last().append(body)
                }
            }
            for (m in messages) {
                sawResponse = true
                val hex = m.toString()
                val count = hex.substring(2).take(2).toIntOrNull(16) ?: continue
                for (k in 0 until count) {
                    val start = 4 + k * 4
                    if (hex.length < start + 4) break
                    decodeDtc(hex.substring(start, start + 4))?.let { codes += it }
                }
            }
        } else {
            // ISO 9141 / KWP / J1850: cada línea "43" + 3 códigos de 2 bytes, rellenos con 0000.
            for (line in lines) {
                if (!line.startsWith("43")) continue
                sawResponse = true
                for (k in 0 until 3) {
                    val start = 2 + k * 4
                    if (line.length < start + 4) break
                    decodeDtc(line.substring(start, start + 4))?.let { codes += it }
                }
            }
        }
        return if (sawResponse) codes.toList() else null
    }

    /** "0133" → P0133. Los dos bits altos dan la letra: P (motor), C (chasis), B (carrocería), U (red). */
    private fun decodeDtc(hex4: String): String? {
        val v = hex4.toIntOrNull(16) ?: return null
        if (v == 0) return null
        val letter = "PCBU"[(v shr 14) and 0x3]
        val d1 = (v shr 12) and 0x3
        return "%c%d%03X".format(letter, d1, v and 0xFFF)
    }

    private fun closeSocket() {
        runCatching { socket?.close() }
        socket = null
    }

    private companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val POLL_MS = 1_000L
        const val MIN_GAP_MS = 100L
        const val RETRY_MS = 5_000L
        const val LOW_VOLTAGE = 11.8f
        const val LOW_VOLTAGE_RETRY_MS = 30_000L
        const val MAX_NO_DATA = 3
        const val SKIP_PID_MS = 120_000L
    }
}
