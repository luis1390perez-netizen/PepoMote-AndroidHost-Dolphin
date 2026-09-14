package dev.pepotech.pepomote.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PartyRosterTest {

    @Test
    fun losInvitadosEmpiezanEnElJugadorDos() {
        val roster = PartyRoster()
        assertEquals(2, roster.join(1L, "Pixel", "10.0.0.2", 0L))
        assertEquals(3, roster.join(2L, "Moto", "10.0.0.3", 0L))
        assertEquals(listOf(2, 3), roster.guests().map { it.player })
    }

    /**
     * El INI reparte los nombres por posición, así que al irse uno los demás
     * TIENEN que correrse: si no, la pantalla diría «Jugador 3» mientras el
     * archivo lo llama PepoMote-P2.
     */
    @Test
    fun alIrseUnoLosDemasSeRenumeran() {
        val roster = PartyRoster()
        roster.join(1L, "Pixel", "10.0.0.2", 0L)
        roster.join(2L, "Moto", "10.0.0.3", 0L)
        roster.leave(1L)
        assertEquals(listOf(2), roster.guests().map { it.player })
        assertEquals("10.0.0.3", roster.guests().first().host)
        assertEquals(2, roster.playerOf(2L))
    }

    /** Una reconexión no puede ocupar dos huecos ni colar la IP dos veces. */
    @Test
    fun elQueVuelveNoOcupaDosHuecos() {
        val roster = PartyRoster()
        roster.join(1L, "Pixel", "10.0.0.2", 0L)
        roster.join(2L, "Pixel", "10.0.0.2", 5_000L)
        assertEquals(1, roster.guests().size)
        assertEquals(listOf("10.0.0.2"), roster.guests().map { it.host })
    }

    @Test
    fun elQuintoNoCabe() {
        val roster = PartyRoster()
        roster.join(1L, "a", "10.0.0.2", 0L)
        roster.join(2L, "b", "10.0.0.3", 0L)
        roster.join(3L, "c", "10.0.0.4", 0L)
        assertNull("solo caben 4 contando al anfitrion", roster.join(4L, "d", "10.0.0.5", 0L))
    }

    @Test
    fun elQueDejaDeDarSenalesSeCae() {
        val roster = PartyRoster()
        roster.join(1L, "Pixel", "10.0.0.2", 0L)
        roster.join(2L, "Moto", "10.0.0.3", 0L)
        roster.ping(2L, 9_000L)
        assertEquals(listOf(1L), roster.prune(12_000L))
        assertEquals(listOf("10.0.0.3"), roster.guests().map { it.host })
    }
}
