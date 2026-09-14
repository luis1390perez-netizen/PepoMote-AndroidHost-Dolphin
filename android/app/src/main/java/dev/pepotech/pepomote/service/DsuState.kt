package dev.pepotech.pepomote.service

import dev.pepotech.pepomote.net.PartyPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Estado del modo «Dolphin en Android» (este móvil sirve DSU a un Dolphin de
 * otro dispositivo). El equivalente de [LinkState] para un modo que no tiene
 * enlace: no hay PC, ni emparejamiento, ni sesión — solo un puerto abierto y
 * los clientes que se hayan enganchado.
 */
object DsuState {

    sealed interface Ui {
        /** El servicio no está corriendo. */
        data object Off : Ui

        /** Puerto abierto. `clients` > 0 = Dolphin está leyendo de nosotros. */
        data class Serving(val ip: String?, val port: Int, val clients: Int) : Ui

        /** No se pudo abrir el puerto (ocupado, sin red…). */
        data class Failed(val error: String) : Ui
    }

    private val _flow = MutableStateFlow<Ui>(Ui.Off)
    val flow: StateFlow<Ui> = _flow

    fun publish(state: Ui) {
        _flow.value = state
    }

    /** Los invitados de la party, vacío si se juega solo. */
    val guests: MutableStateFlow<List<PartyPlayer>> = MutableStateFlow(emptyList())

    /** Si este móvil se unió a la party de otro: su número de jugador. */
    val joinedAs: MutableStateFlow<Int?> = MutableStateFlow(null)

    /** Se intentó entrar en una party llena. */
    val partyFull: MutableStateFlow<Boolean> = MutableStateFlow(false)
}
