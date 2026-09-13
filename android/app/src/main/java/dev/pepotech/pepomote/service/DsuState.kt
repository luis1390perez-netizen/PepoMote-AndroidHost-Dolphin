package dev.pepotech.pepomote.service

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
}
