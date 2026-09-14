package dev.pepotech.pepomote.net

/** Un móvil de la party: su número de jugador, su nombre y su IP. */
data class PartyPlayer(val player: Int, val name: String, val host: String)

/**
 * Quién está en la party y con qué número. El anfitrión NO está aquí: es
 * siempre el Jugador 1 y quien genera los INI antepone su propia IP. Esto
 * lleva solo a los invitados.
 *
 * El número sale de la POSICIÓN en orden de llegada, no de un hueco
 * reservado, porque `dsu_client_ini` bautiza los servidores por posición: si
 * los dos criterios se separaran, la pantalla y el archivo dirían cosas
 * distintas y el usuario no tendría forma de saber cuál miente.
 *
 * No sabe de red a propósito: el transporte es de [PartyHost], y así el
 * reparto de números y la caída por silencio se prueban sin abrir un socket.
 */
class PartyRoster(private val maxPlayers: Int = MAX_PLAYERS) {

    private class Guest(val name: String, val host: String, var seen: Long)

    /** En orden de llegada: de ahí sale el número de jugador. */
    private val guests = LinkedHashMap<Long, Guest>()

    /** @return el número de jugador, o null si la party está llena. */
    @Synchronized
    fun join(id: Long, name: String, host: String, nowMs: Long): Int? {
        // El que vuelve, vuelve una vez: tras un corte de Wi-Fi la conexión
        // vieja puede seguir medio abierta, y sin esto el mismo móvil ocuparía
        // dos huecos y su IP saldría dos veces en el INI.
        guests.entries.removeIf { it.value.host == host }
        if (guests.size >= maxPlayers - 1) return null
        guests[id] = Guest(name, host, nowMs)
        return playerOf(id)
    }

    /** El número de jugador de ese invitado ahora mismo, o null si ya no está. */
    @Synchronized
    fun playerOf(id: Long): Int? {
        val i = guests.keys.indexOf(id)
        return if (i < 0) null else i + 2
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

    /** Los invitados, numerados por su posición actual. */
    @Synchronized
    fun guests(): List<PartyPlayer> =
        guests.values.mapIndexed { i, g -> PartyPlayer(i + 2, g.name, g.host) }

    companion object {
        const val MAX_PLAYERS = 4
        const val TIMEOUT_MS = 10_000L
    }
}
