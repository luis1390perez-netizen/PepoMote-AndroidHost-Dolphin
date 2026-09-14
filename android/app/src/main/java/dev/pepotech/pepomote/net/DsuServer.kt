package dev.pepotech.pepomote.net

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * El servidor DSU (cemuhook) que corre DENTRO de este móvil, en Rust
 * (`libpepomote_dsu.so`, crate `dsu-android`). Es el mismo servidor que usa
 * el receptor de PC: mismo códec, mismo PadData y la misma matriz de signos.
 *
 * Aquí no hay receptor ni protocolo PMP por la red: Dolphin corre en OTRO
 * dispositivo Android y se conecta directamente a `<ip de este móvil>:26760`.
 * Lo que cruza al lado nativo son los mismos bytes del paquete INPUT que
 * `MotionEngine` construye para el PC, así que no hay un segundo códec que
 * pueda desviarse del primero.
 *
 * El servidor se arranca una vez y se queda mientras viva el proceso; lo que
 * se enciende y se apaga es el envío de muestras.
 */
object DsuServer {

    /** Puerto del protocolo DSU. Dolphin lo trae por defecto. */
    const val PORT = 26760

    /**
     * Si el APK se compiló sin la toolchain de Rust no hay `.so`. En vez de
     * reventar al entrar en la pantalla, el modo se queda inutilizable y lo
     * dice: el resto de la app no tiene nada que ver con esto.
     */
    private val available: Boolean = try {
        System.loadLibrary("pepomote_dsu")
        true
    } catch (e: UnsatisfiedLinkError) {
        false
    }

    private external fun nativeStart(bind: String, port: Int): String?
    private external fun nativePush(packet: ByteArray)
    private external fun nativeClients(): Int
    private external fun nativeDsuClientIni(hosts: String, port: Int): String?
    private external fun nativeWiimoteIni(count: Int): String?

    /**
     * Abre el puerto si no estaba abierto. `0.0.0.0` y no el loopback: quien
     * tiene que llegar es un Dolphin de otro dispositivo.
     *
     * @return null si está sirviendo; si no, el error, ya en texto.
     */
    fun start(port: Int = PORT): String? =
        if (available) nativeStart("0.0.0.0", port) else MISSING_LIB

    /** Un paquete INPUT de PmpCodec, tal cual, al pad DSU del Jugador 1. */
    fun push(packet: ByteArray) {
        if (available) nativePush(packet)
    }

    /**
     * Clientes DSU vivos. Dolphin abre uno por mando emulado: en cuanto pasa
     * de 0, la configuración del lado de Dolphin está bien hecha.
     */
    fun clients(): Int = if (available) nativeClients() else 0

    /**
     * El DSUClient.ini con un servidor por móvil de la party, en orden de
     * jugador: el primero es el anfitrión. Los nombres (`PepoMote-P1`…) los
     * pone el lado Rust, que es también quien escribe el WiimoteNew.ini.
     */
    fun dsuClientIni(hosts: List<String>): String =
        if (available && hosts.isNotEmpty()) {
            nativeDsuClientIni(hosts.joinToString(","), PORT) ?: MISSING_LIB
        } else {
            MISSING_LIB
        }

    /** El WiimoteNew.ini con un mando por jugador de la party. */
    fun wiimoteIni(players: Int): String =
        if (available) nativeWiimoteIni(players.coerceAtLeast(1)) ?: MISSING_LIB else MISSING_LIB

    private const val MISSING_LIB =
        "Esta compilación no lleva el servidor DSU nativo (falta libpepomote_dsu.so)"

    /**
     * La IPv4 de este móvil en la red local: la que hay que escribir en el
     * DSUClient.ini de Dolphin. Se prefiere la Wi-Fi, y después la del punto
     * de acceso (el móvil compartiendo Internet, que es la manera de que la
     * IP no cambie nunca). Sin permisos: son las interfaces propias.
     */
    fun localIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy {
                when {
                    it.name.startsWith("wlan") -> 0
                    it.name.startsWith("ap") || it.name.startsWith("swlan") -> 1
                    else -> 2
                }
            }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLinkLocalAddress }
            ?.hostAddress
    }.getOrNull()
}
