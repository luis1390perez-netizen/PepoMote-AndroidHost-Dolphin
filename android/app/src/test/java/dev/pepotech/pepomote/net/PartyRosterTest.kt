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

    @Test
    fun elHuecoDelQueSeVaLoOcupaElSiguiente() {
        val roster = PartyRoster()
        roster.join(1L, "Pixel", "10.0.0.2", 0L)
        roster.join(2L, "Moto", "10.0.0.3", 0L)
        roster.leave(1L)
        assertEquals(2, roster.join(3L, "Xiaomi", "10.0.0.4", 0L))
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
        assertEquals(listOf(3), roster.guests().map { it.player })
    }
}
