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

    companion object {
        const val DEFAULT_USER_SERVER = "s.whatsapp.net"
        const val HIDDEN_USER_SERVER = "lid"
        const val GROUP_SERVER = "g.us"
        const val BROADCAST_SERVER = "broadcast"
        const val MESSENGER_SERVER = "msgr"
        const val INTEROP_SERVER = "interop"
        const val NEWSLETTER_SERVER = "newsletter"
    }
}
