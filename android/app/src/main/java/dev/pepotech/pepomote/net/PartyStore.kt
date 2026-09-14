package dev.pepotech.pepomote.net

import android.content.Context
import java.net.URLDecoder
import java.net.URLEncoder

/** Una party a la que unirse: dónde está el anfitrión y cómo se llama. */
data class PartyInvite(val host: String, val port: Int, val name: String)

/**
 * El QR del anfitrión y la party recordada. El esquema es el mismo que el de
 * emparejar con el PC (`PROTOCOL.md` §1), cambiando `pair` por `party`:
 *
 *     pepomote://party?v=1&host=<ip>&port=26761&name=<url-encoded>
 *
 * Se parsea a mano y no con `Uri.parse` para que el test de JVM pueda
 * probarlo sin emulador.
 */
object PartyStore {

    private const val PREFS = "party"
    private const val KEY_HOST = "host"
    private const val KEY_PORT = "port"
    private const val KEY_NAME = "name"
    private const val PREFIX = "pepomote://party?"

    fun inviteUrl(host: String, name: String): String =
        "${PREFIX}v=1&host=$host&port=${Party.PORT}&name=${URLEncoder.encode(name, "UTF-8")}"

    fun parseInviteUrl(url: String): PartyInvite? {
        if (!url.startsWith(PREFIX)) return null
        return try {
            val params = url.removePrefix(PREFIX).split('&').mapNotNull {
                val p = it.split('=', limit = 2)
                if (p.size == 2) p[0] to p[1] else null
            }.toMap()
            if (params["v"] != "1") return null
            val host = params["host"]?.takeIf { it.isNotBlank() } ?: return null
            val port = params["port"]?.toIntOrNull() ?: Party.PORT
            val name = params["name"]?.let { URLDecoder.decode(it, "UTF-8") } ?: ""
            PartyInvite(host, port, name)
        } catch (e: Exception) {
            // URLDecoder revienta con un % mal escapado: un QR ajeno no puede
            // tumbar el escáner, solo no ser una party
            null
        }
    }

    fun remember(context: Context, invite: PartyInvite) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_HOST, invite.host)
            .putInt(KEY_PORT, invite.port)
            .putString(KEY_NAME, invite.name)
            .apply()
    }

    /** La última party, para reconectar sin volver a escanear. */
    fun last(context: Context): PartyInvite? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val host = p.getString(KEY_HOST, null) ?: return null
        return PartyInvite(host, p.getInt(KEY_PORT, Party.PORT), p.getString(KEY_NAME, "") ?: "")
    }

    /** Salir de la party: sin esto, el móvil se reuniría en cada arranque. */
    fun forget(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
