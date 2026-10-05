package dev.kortex.finance.domain.port

/** The signed-in user's data key, as the `financeKey` function hands it out. */
class DataKey(val userUid: String, val bytes: ByteArray, val version: Int)

/**
 * Where the data key comes from (docs/FINANCE_PLAN.md › Cloud Functions › financeKey): Firebase,
 * in `:sync`. The key is made and KMS-wrapped on the server the first time it's asked for; a new
 * phone fetches it after sign-in.
 */
interface FinanceKeySource {
    /** Who's signed in now; null when signed out. */
    fun currentUserUid(): String?

    /** This user's key from the server; null offline, signed out, or refused (App Check). */
    suspend fun fetch(): DataKey?

    companion object {
        val None: FinanceKeySource = object : FinanceKeySource {
            override fun currentUserUid(): String? = null
            override suspend fun fetch(): DataKey? = null
        }
    }
}
