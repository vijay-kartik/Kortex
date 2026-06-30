package dev.kortex.wa.store

/**
 * A blocking namespaced byte-store SPI backing the Signal protocol state (sessions, prekeys,
 * identities, sender keys). libsignal's store interfaces are synchronous, so this is too —
 * call it off the main thread. The app provides a Room-backed implementation; `:wa` stays
 * storage-agnostic and unit-testable with an in-memory fake.
 */
interface KeyValueStore {
    fun get(namespace: String, key: String): ByteArray?
    fun put(namespace: String, key: String, value: ByteArray)
    fun delete(namespace: String, key: String)
    /** All keys in a namespace (used to enumerate sessions/signed pre-keys). */
    fun keys(namespace: String): List<String>
    /** All values in a namespace. */
    fun values(namespace: String): List<ByteArray>

    companion object {
        const val NS_IDENTITY = "signal_identity"
        const val NS_PREKEY = "signal_prekey"
        const val NS_SIGNED_PREKEY = "signal_signed_prekey"
        const val NS_SESSION = "signal_session"
        const val NS_SENDER_KEY = "signal_sender_key"
    }
}
