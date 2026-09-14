package dev.pepotech.pepomote.net

/** Un móvil de la party: su número de jugador, su nombre y su IP. */
data class PartyPlayer(val player: Int, val name: String, val host: String)

/**
 * Quién está en la party y con qué número. El anfitrión NO está aquí: es
 * siempre el Jugador 1 y quien genera los INI antepone su propia IP. Esto
 * lleva solo a los invitados (2..maxPlayers).
 *
 * No sabe de red a propósito: el transporte es de [PartyHost], y así el
 * reparto de números y la caída por silencio se prueban sin abrir un socket.
 */
class PartyRoster(private val maxPlayers: Int = MAX_PLAYERS) {

    private class Guest(val player: Int, val name: String, val host: String, var seen: Long)

    private val guests = LinkedHashMap<Long, Guest>()

    /** @return el número de jugador, o null si la party está llena. */
    @Synchronized
    fun join(id: Long, name: String, host: String, nowMs: Long): Int? {
        guests.remove(id)
        val taken = guests.values.map { it.player }.toSet()
        val player = (2..maxPlayers).firstOrNull { it !in taken } ?: return null
        guests[id] = Guest(player, name, host, nowMs)
        return player
    }

    @Synchronized
    fun ping(id: Long, nowMs: Long) {
        guests[id]?.seen = nowMs
    }

    @Synchronized
    fun leave(id: Long) {
        guests.remove(id)
    }

    /**
     * Saca a los que llevan demasiado sin dar señales y devuelve sus ids.
     * Un móvil que se va sin cerrar (Wi-Fi apagado, batería) dejaría si no su
     * IP en el archivo, y Dolphin tendría un mando que nunca responde.
     */
    @Synchronized
    fun prune(nowMs: Long, timeoutMs: Long = TIMEOUT_MS): List<Long> {
        val muertos = guests.filterValues { nowMs - it.seen > timeoutMs }.keys.toList()
        muertos.forEach { guests.remove(it) }
        return muertos
    }

    /** Los invitados, ordenados por número de jugador. */
    @Synchronized
    fun guests(): List<PartyPlayer> =
        guests.values.sortedBy { it.player }.map { PartyPlayer(it.player, it.name, it.host) }

    companion object {
        const val MAX_PLAYERS = 4
        const val TIMEOUT_MS = 10_000L
    }
}
