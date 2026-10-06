package dev.kortex.app.data.finance

import android.util.Log
import dev.kortex.app.data.ai.AiGatewayClient
import dev.kortex.app.data.ai.choice
import dev.kortex.finance.domain.model.Category
import dev.kortex.finance.domain.model.CategoryKind
import dev.kortex.finance.domain.read.Decision
import dev.kortex.finance.domain.read.FinanceDecider
import dev.kortex.finance.domain.read.SmsKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Finance's decision model: Jev on Vercel AI Gateway, one Choice question per call. What it's sent
 * is already masked. No key, offline, slow or an unreadable answer is null, and finance carries on
 * as it did without it.
 */
class JevFinanceDecider(private val gateway: AiGatewayClient) : FinanceDecider {

    override suspend fun smsKind(maskedText: String): Decision<SmsKind>? {
        val (choice, probability) = ask(maskedText, choiceQuestion(SMS_INSTRUCTIONS, SMS_KINDS.mapValues { it.value.second })) ?: return null
        val kind = SMS_KINDS[choice]?.first ?: return null
        return Decision(kind, probability)
    }

    override suspend fun pickCategory(merchant: String, categories: List<Category>): Decision<String>? {
        // Options are keyed by name, which is what the model reads; the uid is looked up after.
        val byName = categories.associateBy { it.name.trim() }.filterKeys { it.isNotEmpty() }
        if (byName.size < 2) return null
        val instructions = when (categories.first().kind) {
            CategoryKind.INCOME -> "Which category does money received from this payer or description belong in?"
            CategoryKind.EXPENSE -> "Which spending category does a payment to this merchant or description belong in?"
        }
        val (choice, probability) = ask(merchant, choiceQuestion(instructions, byName.mapValues { it.key })) ?: return null
        val uid = byName[choice]?.uid ?: return null
        return Decision(uid, probability)
    }

    private fun choiceQuestion(instructions: String, criteria: Map<String, String>): JsonObject = buildJsonObject {
        putJsonObject(QUESTION) {
            put("type", "choice")
            put("instructions", instructions)
            putJsonObject("criteria") { criteria.forEach { (option, meaning) -> put(option, meaning) } }
        }
    }

    private suspend fun ask(state: String, questions: JsonObject): Pair<String, Double>? {
        if (!gateway.isConfigured || state.isBlank()) return null
        return try {
            withTimeoutOrNull(TIMEOUT_MS) { gateway.evaluate(JsonPrimitive(state), questions) }?.choice(QUESTION)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Jev decision failed", e)
            null
        }
    }

    private companion object {
        const val TAG = "JevFinanceDecider"
        const val QUESTION = "answer"

        /** Someone is waiting on the screen; past this the screen carries on without an answer. */
        const val TIMEOUT_MS = 5_000L

        const val SMS_INSTRUCTIONS = "What kind of message is this SMS?"

        /** Option name → the kind it means, and what the model is told it covers. */
        val SMS_KINDS: Map<String, Pair<SmsKind, String>> = mapOf(
            "transaction" to (SmsKind.TRANSACTION to "A bank, credit card, or UPI alert that money was debited, credited, spent, received, sent or refunded"),
            "otp" to (SmsKind.OTP to "A one-time password or verification code"),
            "promo" to (SmsKind.PROMO to "An offer, advert, cashback or reward pitch, or a loan or card promotion"),
            "other" to (SmsKind.UNREADABLE to "Anything else: bill or payment-due reminders, balance updates with no transaction, delivery updates, personal messages"),
        )
    }
}
