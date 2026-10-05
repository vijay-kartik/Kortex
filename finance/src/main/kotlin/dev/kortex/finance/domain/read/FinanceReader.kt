package dev.kortex.finance.domain.read

import dev.kortex.finance.domain.model.Category
import dev.kortex.finance.domain.model.ReceiptItem
import java.time.LocalDate
import java.time.LocalTime

/** What the LLM made of an SMS the patterns couldn't read. */
data class SmsReading(
    val direction: MoneyDirection,
    val amountMinor: Long,
    val last4: String? = null,
    val instrument: Instrument = Instrument.UNKNOWN,
    val date: LocalDate? = null,
    val time: LocalTime? = null,
    val payee: String? = null,
    val ref: String? = null,
)

/** What the LLM made of a receipt's text. */
data class ReceiptReading(
    val merchant: String? = null,
    val date: LocalDate? = null,
    val time: LocalTime? = null,
    val totals: List<TotalCandidate> = emptyList(),
    val taxMinor: Long? = null,
    val items: List<ReceiptItem> = emptyList(),
    val last4: String? = null,
)

/** A merchant's tidy name and the category it most likely belongs in. */
data class MerchantSuggestion(val name: String, val categoryUid: String?)

/**
 * The agent's model, for what patterns can't do (docs/FINANCE_PLAN.md › How entries get in): read
 * an unusual SMS, structure a receipt, tidy a merchant's name and suggest its category. Every
 * call gets text already passed through [TextReading.maskForModel]. Each returns null when no
 * model is set up, the call fails, or the answer can't be used; callers then carry on without it.
 */
interface FinanceReader {
    suspend fun readSms(maskedText: String, today: LocalDate): SmsReading?

    suspend fun readReceipt(maskedText: String, today: LocalDate): ReceiptReading?

    suspend fun suggestMerchant(rawName: String, categories: List<Category>): MerchantSuggestion?

    companion object {
        /** No model: patterns and remembered merchants only. */
        val None: FinanceReader = object : FinanceReader {
            override suspend fun readSms(maskedText: String, today: LocalDate): SmsReading? = null
            override suspend fun readReceipt(maskedText: String, today: LocalDate): ReceiptReading? = null
            override suspend fun suggestMerchant(rawName: String, categories: List<Category>): MerchantSuggestion? = null
        }
    }
}
