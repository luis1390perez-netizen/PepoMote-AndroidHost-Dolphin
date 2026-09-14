package dev.pepotech.pepomote.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PartyStoreTest {

    @Test
    fun laUrlDeInvitacionSeLeeDeVuelta() {
        val url = PartyStore.inviteUrl("192.168.1.42", "Galaxy S21")
        val invite = PartyStore.parseInviteUrl(url)!!
        assertEquals("192.168.1.42", invite.host)
        assertEquals(Party.PORT, invite.port)
        assertEquals("Galaxy S21", invite.name)
    }

    @Test
    fun elQrDeEmparejarConElPcNoEsUnaParty() {
        assertNull(PartyStore.parseInviteUrl("pepomote://pair?v=1&host=10.0.0.1&port=26761&t=ab"))
    }

    @Test
    fun unaUrlSinHostNoVale() {
        assertNull(PartyStore.parseInviteUrl("pepomote://party?v=1&port=26761"))
        assertNull(PartyStore.parseInviteUrl("cualquier cosa"))
    }

    /** Un QR ajeno o corrompido se rechaza; no puede reventar el escáner. */
    @Test
    fun unaUrlMalFormadaNoRevienta() {
        assertNull(PartyStore.parseInviteUrl("pepomote://party?v=1&host=1.2.3.4&name=%zz"))
    }

    @Test
    fun otraVersionNoVale() {
        assertNull(PartyStore.parseInviteUrl("pepomote://party?v=9&host=1.2.3.4"))
    }
}
