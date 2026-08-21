package dev.kortex.wa.binary

/**
 * A WhatsApp JID. For ingestion we mostly care about [user] (the phone number for
 * s.whatsapp.net JIDs) and [server]; [device]/[agent]/[integrator] are kept for fidelity.
 */
data class Jid(
    val user: String,
    val server: String,
    val device: Int = 0,
    val agent: Int = 0,
    val integrator: Int = 0,
) {
    override fun toString(): String {
        if (user.isEmpty()) return server
        val devicePart = if (device != 0) ":$device" else ""
        return "$user$devicePart@$server"
    }

    /** True for WhatsApp's privacy identifiers, whose [user] is a LID and NOT a phone number. */
    val isLid: Boolean get() = server == HIDDEN_USER_SERVER

    companion object {
        const val DEFAULT_USER_SERVER = "s.whatsapp.net"
        const val HIDDEN_USER_SERVER = "lid"
        const val HOSTED_SERVER = "hosted"

        /** Parse "user[:device]@server" — the text form JIDs take inside protobuf payloads. */
        fun parse(jid: String): Jid {
            val local = jid.substringBefore('@')
            return Jid(
                user = local.substringBefore(':'),
                server = jid.substringAfter('@', DEFAULT_USER_SERVER),
                device = local.substringAfter(':', "0").toIntOrNull() ?: 0,
            )
        }

        /** whatsmeow `NewADJID`: the agent byte selects the server an AD-JID belongs to. */
        fun serverForAgent(agent: Int): String = when (agent) {
            0 -> DEFAULT_USER_SERVER
            1 -> HIDDEN_USER_SERVER
            else -> HOSTED_SERVER
        }

        /** Inverse of [serverForAgent], for encoding JIDs we construct ourselves. */
        fun agentForServer(server: String): Int = when (server) {
            HIDDEN_USER_SERVER -> 1
            HOSTED_SERVER -> 2
            else -> 0
        }
        const val GROUP_SERVER = "g.us"
        const val BROADCAST_SERVER = "broadcast"
        const val MESSENGER_SERVER = "msgr"
        const val INTEROP_SERVER = "interop"
        const val NEWSLETTER_SERVER = "newsletter"
    }
}
