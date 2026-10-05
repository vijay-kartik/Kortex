package dev.kortex.finance.domain.read

import dev.kortex.finance.domain.model.Category

/** A decision and how sure the model is of it, from 0 to 1. */
data class Decision<T>(val value: T, val confidence: Double)

/**
 * A fast decision model (typed choices with probabilities, no free text) for finance's pick-one
 * questions: what kind of message an SMS is, and which category a merchant belongs in. Every
 * call gets text already passed through [TextReading.maskForModel]. Each returns null when no
 * model is set up or the call fails; callers then carry on without it, and decide for
 * themselves how sure is sure enough.
 */
interface FinanceDecider {
    /** Which kind of message this is. Only asked about SMS the patterns couldn't place. */
    suspend fun smsKind(maskedText: String): Decision<SmsKind>?

    /** The uid of the category in [categories] that fits [merchant] best. */
    suspend fun pickCategory(merchant: String, categories: List<Category>): Decision<String>?

    companion object {
        /** No model: patterns, remembered merchants and the [FinanceReader] only. */
        val None: FinanceDecider = object : FinanceDecider {
            override suspend fun smsKind(maskedText: String): Decision<SmsKind>? = null
            override suspend fun pickCategory(merchant: String, categories: List<Category>): Decision<String>? = null
        }
    }
}
