package dev.kortex.sync.finance

import android.util.Base64
import android.util.Log
import com.google.firebase.functions.FirebaseFunctions
import dev.kortex.finance.domain.port.DataKey
import dev.kortex.finance.domain.port.FinanceKeySource
import dev.kortex.sync.CloudAccount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

/**
 * Fetches the signed-in user's data key from the `financeKey` function (docs/FINANCE_PLAN.md ›
 * Cloud Functions). The function makes and KMS-wraps it the first time, and only answers a signed-in
 * user calling from this app (App Check). The phone keeps it wrapped by its Keystore after that.
 */
class FinanceKeyClient(private val account: CloudAccount) : FinanceKeySource {
    private val functions by lazy { FirebaseFunctions.getInstance(REGION) }

    override fun currentUserUid(): String? = account.user.value?.uid

    override suspend fun fetch(): DataKey? {
        val user = currentUserUid() ?: return null
        return try {
            val result = withTimeout(TIMEOUT_MS) { functions.getHttpsCallable("financeKey").call().await() }
            val data = result.data as? Map<*, *> ?: return null
            val key = (data["key"] as? String)?.let { Base64.decode(it, Base64.NO_WRAP) }?.takeIf { it.size == 32 } ?: return null
            DataKey(user, key, (data["keyVersion"] as? Number)?.toInt() ?: 1)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't fetch the finance key", e)
            null
        }
    }

    private companion object {
        const val TAG = "FinanceKeyClient"
        /** Next to Firestore, as every Kortex function is. */
        const val REGION = "asia-south1"
        const val TIMEOUT_MS = 20_000L
    }
}
