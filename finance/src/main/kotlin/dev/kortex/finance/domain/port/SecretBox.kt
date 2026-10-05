package dev.kortex.finance.domain.port

/** A full card or account number as stored and synced: cipher text only (docs/FINANCE_PLAN.md › Decisions). */
data class SealedSecret(val cipherText: String, val keyVersion: Int)

/**
 * Encrypts full numbers on the phone with the user's data key, so only cipher text is ever saved
 * or synced, and only a phone signed in to that account can read it back. Each secret is bound to
 * its account: one can't be moved onto another.
 */
interface SecretBox {
    /** Null when there's no key to seal with: signed out, or offline before the key was ever fetched. */
    suspend fun seal(accountUid: String, plain: String): SealedSecret?

    /** Null when it can't be read here: another account's key, or the key isn't available. */
    suspend fun open(accountUid: String, sealed: SealedSecret): String?

    companion object {
        val None: SecretBox = object : SecretBox {
            override suspend fun seal(accountUid: String, plain: String): SealedSecret? = null
            override suspend fun open(accountUid: String, sealed: SealedSecret): String? = null
        }
    }
}
