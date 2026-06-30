package dev.kortex.wa.auth

/**
 * Persists the device credentials so the companion survives restarts without re-scanning the
 * QR. The concrete Android implementation (encrypted storage backed by the Keystore) is wired
 * in the app layer; keeping this an interface lets the protocol code stay storage-agnostic and
 * unit-testable with an in-memory fake.
 */
interface CredentialStore {
    suspend fun load(): DeviceCredentials?
    suspend fun save(credentials: DeviceCredentials)
    suspend fun clear()
}
