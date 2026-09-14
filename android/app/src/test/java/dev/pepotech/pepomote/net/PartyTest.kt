package dev.pepotech.pepomote.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

class PartyTest {

    private fun puertoLibre(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun elInvitadoSeUneYElAnfitrionLoVe() {
        val port = puertoLibre()
        val anfitrion = PartyHost(port)
        anfitrion.start()
        try {
            // el nombre se inyecta: Party.deviceName() lee android.os.Build y
            // en un test de JVM esos campos llegan vacios
            val invitado = PartyGuest("127.0.0.1", port, "Pixel de prueba")
            invitado.start()
            try {
                esperaA { invitado.player.value == 2 }
                esperaA { anfitrion.guests().size == 1 }
                assertEquals(2, anfitrion.guests().first().player)
                assertEquals("127.0.0.1", anfitrion.guests().first().host)
                assertEquals("Pixel de prueba", anfitrion.guests().first().name)
            } finally {
                invitado.stop()
            }
            esperaA { anfitrion.guests().isEmpty() }
        } finally {
            anfitrion.stop()
        }
    }

    /** Si el anfitrión desaparece, el invitado tiene que enterarse. */
    @Test
    fun siElAnfitrionMuereElInvitadoSeEntera() {
        val port = puertoLibre()
        val anfitrion = PartyHost(port)
        anfitrion.start()
        val invitado = PartyGuest("127.0.0.1", port, "Pixel de prueba")
        invitado.start()
        try {
            esperaA { invitado.player.value == 2 }
            anfitrion.stop()
            esperaA(10_000) { invitado.player.value == null }
        } finally {
            invitado.stop()
        }
    }

    /** Un stop() tiene que poder seguirse de un start(): el servicio reentra. */
    @Test
    fun sePuedeParaYArrancarDeNuevo() {
        val port = puertoLibre()
        val anfitrion = PartyHost(port)
        anfitrion.start()
        anfitrion.stop()
        anfitrion.start()
        try {
            val invitado = PartyGuest("127.0.0.1", port, "Pixel de prueba")
            invitado.start()
            try {
                esperaA { invitado.player.value == 2 }
            } finally {
                invitado.stop()
            }
        } finally {
            anfitrion.stop()
        }
    }

    private fun esperaA(limiteMs: Long = 5_000, cond: () -> Boolean) {
        val fin = System.currentTimeMillis() + limiteMs
        while (System.currentTimeMillis() < fin) {
            if (cond()) return
            Thread.sleep(20)
        }
        throw AssertionError("no se cumplio en $limiteMs ms")
    }
}
