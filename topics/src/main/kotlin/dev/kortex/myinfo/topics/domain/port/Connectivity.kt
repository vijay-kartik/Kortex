package dev.kortex.myinfo.topics.domain.port

/** Whether the phone can reach the internet right now; videos stream, so the player asks first. */
fun interface Connectivity {
    fun isOnline(): Boolean
}
