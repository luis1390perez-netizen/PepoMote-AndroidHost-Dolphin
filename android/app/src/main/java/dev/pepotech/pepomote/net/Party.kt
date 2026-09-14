package dev.pepotech.pepomote.net

import android.os.Build
import dev.pepotech.pepomote.control.TextInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedReader
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/**
 * La «party»: varios móviles mandando al MISMO Dolphin. Cada uno sigue
 * sirviendo su propio DSU en su IP; esto solo sirve para que el anfitrión
 * sepa las IPs de los demás y pueda escribir los dos INI con todos dentro.
 * El tráfico de juego NO pasa por aquí.
 *
 * Una línea JSON por mensaje, como el canal de control de PMP, pero montada y
 * leída a mano: `org.json` vive en `android.jar` y en los tests de JVM lanza
 * «not mocked» (lo mismo que documenta [ScreenClient]).
 */
object Party {
    /** TCP. El 26760/UDP es el DSU; aquí el móvil solo era cliente del PC. */
    const val PORT = 26761
    const val PING_MS = 3_000L

    fun joinMessage(name: String) = "{\"m\":\"join\",\"v\":1,\"name\":\"${TextInput.escape(name)}\"}"
    fun pingMessage() = "{\"m\":\"ping\"}"
    fun playerMessage(n: Int) = "{\"m\":\"player\",\"n\":$n}"
    fun fullMessage() = "{\"m\":\"err\",\"e\":\"party_full\"}"

    fun kind(line: String): String? = ScreenClient.jsonString(line, "m")
    fun nameOf(line: String): String? = ScreenClient.jsonString(line, "name")
    fun errorOf(line: String): String? = ScreenClient.jsonString(line, "e")
    fun playerOf(line: String): Int? =
        Regex("\"n\"\\s*:\\s*(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()

    fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
}

/**
 * El anfitrión (Jugador 1): acepta a quien se una y lleva el roster. La IP de
 * cada invitado se saca del PROPIO socket, nunca de lo que diga el invitado:
 * es el dato que acaba dentro del INI.
 *
 * No publica un `StateFlow`: el servicio ya sondea una vez por segundo, y un
 * flujo escrito desde varios hilos solo añadiría una carrera y una segunda
 * copia del estado. La verdad está en el roster.
 */
class PartyHost(private val port: Int = Party.PORT) {

    private val roster = PartyRoster()
    @Volatile private var server: ServerSocket? = null
    private val vivos = HashMap<Long, Socket>()
    @Volatile private var running = false
    private var nextId = 1L

    /**
     * El hilo que hace accept(). `stop()` lo espera antes de volver: cerrar
     * el socket libera esa llamada, pero el sistema no suelta el número de
     * puerto hasta que la llamada bloqueada termina de desenrollarse del
     * todo. Sin este join(), un start() inmediatamente después de un stop()
     * puede fallar con «Address already in use» aunque el socket ya esté
     * cerrado (comprobado empíricamente: sin el join, ~el 45% de los
     * reinicios fallan; con él, 0 de 300).
     */
    private var acceptThread: Thread? = null

    fun guests(): List<PartyPlayer> = roster.guests()

    fun start() {
        if (running) return
        val srv = try {
            ServerSocket(port)
        } catch (e: Exception) {
            // El puerto puede estar ocupado por una instancia anterior que aún
            // no ha muerto. Si dejáramos running = true, este objeto quedaría
            // tapiado: ningún start() posterior volvería a intentarlo.
            return
        }
        server = srv
        running = true
        acceptThread = thread(isDaemon = true, name = "party-host") {
            while (running) {
                val socket = try {
                    srv.accept()
                } catch (e: Exception) {
                    break
                }
                val id = synchronized(this) { nextId++ }
                thread(isDaemon = true, name = "party-guest-$id") { atiende(id, socket) }
            }
            // Solo si `srv` sigue siendo el socket vigente: tras un
            // stop()+start() muy seguidos este hilo puede ser de la
            // generación anterior y despertar tarde. Si tocara `running` a
            // ciegas, apagaría una generación nueva que ya estuviera
            // aceptando conexiones.
            if (server === srv) running = false
        }
        thread(isDaemon = true, name = "party-prune") {
            while (running) {
                Thread.sleep(1_000)
                roster.prune(System.currentTimeMillis())
            }
        }
    }

    private fun atiende(id: Long, socket: Socket) {
        socket.use { s ->
            try {
                // Sin esto, un móvil que desaparece sin cerrar (Wi-Fi apagado)
                // deja este hilo bloqueado en readLine() para siempre: justo el
                // caso que el latido dice cubrir.
                s.soTimeout = PartyRoster.TIMEOUT_MS.toInt()
                synchronized(this) { vivos[id] = s }
                val reader = s.getInputStream().bufferedReader()
                val writer = s.getOutputStream().bufferedWriter()
                val primera = reader.readLine() ?: return
                if (Party.kind(primera) != "join") return
                // IPv4 obligatorio: un ':' de IPv6 dentro de una entrada
                // `nombre:direccion:puerto` rompería el parseo de Dolphin sin
                // decir nada.
                val addr = s.inetAddress
                if (addr !is Inet4Address) return
                val host = addr.hostAddress ?: return
                val player = roster.join(id, Party.nameOf(primera) ?: "?", host, System.currentTimeMillis())
                if (player == null) {
                    writer.write(Party.fullMessage())
                    writer.write("\n")
                    writer.flush()
                    return
                }
                writer.write(Party.playerMessage(player))
                writer.write("\n")
                writer.flush()
                bucle(id, reader, writer)
            } catch (e: Exception) {
                // se cayó: sale del roster igual que si se hubiera ido
            } finally {
                roster.leave(id)
                synchronized(this) { vivos.remove(id) }
            }
        }
    }

    /**
     * Cada latido se contesta con el número de jugador de ese momento. Dos
     * pájaros de un tiro: el invitado se entera si se ha corrido de sitio
     * porque otro se fue, y tiene tráfico que leer para notar que el anfitrión
     * ha muerto.
     */
    private fun bucle(id: Long, reader: BufferedReader, writer: java.io.BufferedWriter) {
        while (running) {
            reader.readLine() ?: return
            val ahora = System.currentTimeMillis()
            roster.ping(id, ahora)
            val player = roster.playerOf(id) ?: return
            writer.write(Party.playerMessage(player))
            writer.write("\n")
            writer.flush()
        }
    }

    fun stop() {
        running = false
        try {
            server?.close()
        } catch (e: Exception) {
        }
        server = null
        // Ver el comentario de [acceptThread]: hay que esperar a que muera de
        // verdad, si no el próximo start() puede pisar un puerto que el
        // sistema aún no ha soltado.
        try {
            acceptThread?.join(2_000)
        } catch (e: InterruptedException) {
        }
        acceptThread = null
        // Cerrar los aceptados: cerrar el ServerSocket solo mata el accept(),
        // no las conexiones ya establecidas.
        synchronized(this) {
            vivos.values.forEach { runCatching { it.close() } }
            vivos.clear()
        }
    }
}

/** El invitado: se une al anfitrión, le da señales de vida y lo vigila. */
class PartyGuest(
    private val host: String,
    private val port: Int = Party.PORT,
    private val name: String = Party.deviceName(),
) {

    private val _player = MutableStateFlow<Int?>(null)
    val player: StateFlow<Int?> = _player

    /** La party está llena: es distinto de «no hay anfitrión» y se dice. */
    private val _full = MutableStateFlow(false)
    val full: StateFlow<Boolean> = _full

    private var socket: Socket? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        _full.value = false
        thread(isDaemon = true, name = "party-guest") {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 4_000)
                // Tres latidos de margen: si el anfitrión no contesta en ese
                // tiempo, es que ya no está.
                s.soTimeout = (Party.PING_MS * 3).toInt()
                socket = s
                val reader = s.getInputStream().bufferedReader()
                val writer = s.getOutputStream().bufferedWriter()
                writer.write(Party.joinMessage(name))
                writer.write("\n")
                writer.flush()
                val respuesta = reader.readLine() ?: return@thread
                if (Party.errorOf(respuesta) == "party_full") {
                    _full.value = true
                    return@thread
                }
                if (Party.kind(respuesta) != "player") return@thread
                _player.value = Party.playerOf(respuesta)
                while (running) {
                    Thread.sleep(Party.PING_MS)
                    if (!running) break
                    writer.write(Party.pingMessage())
                    writer.write("\n")
                    writer.flush()
                    // Un readLine() que devuelve null o revienta = anfitrión
                    // caído. Sin esta lectura, el write no fallaría hasta que
                    // TCP agotara sus reintentos (minutos) y la pantalla
                    // seguiría diciendo que estamos en una party que ya no
                    // existe, escondiendo los INI.
                    val linea = reader.readLine() ?: break
                    if (Party.kind(linea) == "player") {
                        Party.playerOf(linea)?.let { _player.value = it }
                    }
                }
            } catch (e: SocketTimeoutException) {
                // el anfitrión dejó de contestar
            } catch (e: Exception) {
                // sin anfitrión: la pantalla ofrece escanear otra vez
            } finally {
                _player.value = null
                runCatching { socket?.close() }
            }
        }
    }

    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (e: Exception) {
        }
        socket = null
        _player.value = null
        _full.value = false
    }
}
