package dev.kortex.finance.domain.usecase

import dev.kortex.finance.domain.FinanceIds
import dev.kortex.finance.domain.model.CategoryKind
import dev.kortex.finance.domain.model.Receipt
import dev.kortex.finance.domain.port.Clock
import dev.kortex.finance.domain.read.FinanceDecider
import dev.kortex.finance.domain.read.FinanceReader
import dev.kortex.finance.domain.read.ParsedReceipt
import dev.kortex.finance.domain.read.ParsedSms
import dev.kortex.finance.domain.read.ReceiptParser
import dev.kortex.finance.domain.read.SmsKind
import dev.kortex.finance.domain.read.SmsParser
import dev.kortex.finance.domain.read.TextReading
import dev.kortex.finance.domain.repository.FinanceRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first

sealed interface SmsResult {
    data class Read(val sms: ParsedSms) : SmsResult

    /** An OTP, an advert, or nothing a bank would send (Paste SMS 07). Nothing was stored. */
    data class NotAPayment(val kind: SmsKind) : SmsResult
}

/**
 * Paste SMS 01: patterns first; what they can't read goes to the LLM, masked. OTPs the patterns
 * catch never do. In between, the decision model gets a quick look: when it's sure an unreadable
 * SMS isn't a payment, the LLM isn't asked at all.
 */
class ReadSms(
    private val reader: FinanceReader,
    private val clock: Clock,
    private val decider: FinanceDecider = FinanceDecider.None,
) {
    suspend operator fun invoke(text: String): SmsResult {
        val today = clock.today()
        when (val kind = SmsParser.classify(text, today)) {
            SmsKind.OTP, SmsKind.PROMO -> return SmsResult.NotAPayment(kind)
            SmsKind.TRANSACTION -> return SmsResult.Read(SmsParser.parse(text, today)!!)
            SmsKind.UNREADABLE -> Unit
        }
        val masked = TextReading.maskForModel(text)
        decider.smsKind(masked)
            ?.takeIf { it.value != SmsKind.TRANSACTION && it.confidence >= NOT_A_PAYMENT_CONFIDENCE }
            ?.let { return SmsResult.NotAPayment(it.value) }
        val reading = reader.readSms(masked, today)?.takeIf { it.amountMinor > 0 }
            ?: return SmsResult.NotAPayment(SmsKind.UNREADABLE)
        return SmsResult.Read(
            ParsedSms(
                direction = reading.direction,
                amountMinor = reading.amountMinor,
                last4 = reading.last4?.takeLast(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) },
                instrument = reading.instrument,
                date = reading.date,
                time = reading.time,
                payee = reading.payee,
                payeeIsUpiId = reading.payee?.let { SmsParser.UpiId.matches(it) } ?: false,
                ref = reading.ref,
                fromModel = true,
            ),
        )
    }

    internal companion object {
        /** High: a payment wrongly turned away is worse than an LLM call spent on an advert. */
        const val NOT_A_PAYMENT_CONFIDENCE = 0.85
    }
}

/**
 * Scan receipt 02: patterns on the recognised text, then the LLM for whatever they left open —
 * the merchant, the items, or a total they couldn't be sure of.
 */
class ReadReceipt(private val reader: FinanceReader, private val clock: Clock) {
    suspend operator fun invoke(text: String): ParsedReceipt {
        val today = clock.today()
        val parsed = ReceiptParser.parse(text, today)
        if (text.isBlank() || parsed.merchant != null && parsed.total != null && parsed.items.isNotEmpty()) return parsed
        val reading = reader.readReceipt(TextReading.maskForModel(text), today) ?: return parsed
        val totals = (parsed.candidates + reading.totals).filter { it.amountMinor > 0 }.distinctBy { it.amountMinor }
        return parsed.copy(
            merchant = parsed.merchant ?: reading.merchant?.let(TextReading::tidyName),
            date = parsed.date ?: reading.date,
            time = parsed.time ?: reading.time,
            candidates = totals,
            // One total from the model settles it when the patterns found none, or found it too.
            total = parsed.total ?: reading.totals.singleOrNull()?.amountMinor
                ?.takeIf { only -> parsed.candidates.isEmpty() || parsed.candidates.any { it.amountMinor == only } },
            taxMinor = parsed.taxMinor ?: reading.taxMinor,
            items = parsed.items.ifEmpty { reading.items },
            last4 = parsed.last4 ?: reading.last4?.takeLast(4),
        )
    }
}

/** A merchant's name and category for the review (Paste SMS 02, 06). */
data class MerchantGuess(
    val name: String?,
    val categoryUid: String?,
    /** Where the category came from; null when there's no suggestion. */
    val source: Source?,
    /** A UPI id nobody has named yet: "Who was this for?" (Paste SMS 06). */
    val needsName: Boolean = false,
) {
    enum class Source { REMEMBERED, MODEL }
}

/**
 * Remembered merchants first; then the LLM tidies the name while the decision model picks the
 * category (the LLM's pick stands in when the decision model isn't sure); else a tidy name alone.
 */
class SuggestMerchant(
    private val repository: FinanceRepository,
    private val reader: FinanceReader,
    private val decider: FinanceDecider = FinanceDecider.None,
) {
    suspend operator fun invoke(raw: String?, kind: CategoryKind, isUpiId: Boolean = false): MerchantGuess {
        val name = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return MerchantGuess(null, null, null)
        repository.findMerchant(FinanceIds.payeeKey(name))?.let { known ->
            val category = known.categoryUid?.takeIf { repository.getCategory(it)?.kind == kind }
            return MerchantGuess(known.displayName, category, category?.let { MerchantGuess.Source.REMEMBERED })
        }
        if (isUpiId) return MerchantGuess(null, null, null, needsName = true)
        val categories = repository.observeCategories().first().filter { it.kind == kind }
        val masked = TextReading.maskForModel(name)
        val (suggestion, decided) = coroutineScope {
            val picked = async { decider.pickCategory(masked, categories) }
            reader.suggestMerchant(masked, categories) to picked.await()
        }
        val category = (decided?.takeIf { it.confidence >= SuggestCategory.CONFIDENCE }?.value ?: suggestion?.categoryUid)
            ?.takeIf { uid -> categories.any { it.uid == uid } }
        return MerchantGuess(
            name = suggestion?.name?.trim()?.takeIf { it.isNotEmpty() } ?: TextReading.tidyName(name),
            categoryUid = category,
            source = category?.let { MerchantGuess.Source.MODEL },
        )
    }
}

/**
 * Add expense / Add income: the category for a merchant typed by hand. The one it was last saved
 * under, else the decision model's pick when it's sure; null when neither has one.
 */
class SuggestCategory(
    private val repository: FinanceRepository,
    private val decider: FinanceDecider = FinanceDecider.None,
) {
    suspend operator fun invoke(merchant: String, kind: CategoryKind): MerchantGuess? {
        val name = merchant.trim().takeIf { it.isNotEmpty() } ?: return null
        val categories = repository.observeCategories().first().filter { it.kind == kind }
        repository.findMerchant(FinanceIds.payeeKey(name))?.categoryUid
            ?.takeIf { uid -> categories.any { it.uid == uid } }
            ?.let { return MerchantGuess(name, it, MerchantGuess.Source.REMEMBERED) }
        return decider.pickCategory(TextReading.maskForModel(name), categories)
            ?.takeIf { decided -> decided.confidence >= CONFIDENCE && categories.any { it.uid == decided.value } }
            ?.let { MerchantGuess(name, it.value, MerchantGuess.Source.MODEL) }
    }

    internal companion object {
        /** Probability of the picked category; spread across many categories, half is a clear lead. */
        const val CONFIDENCE = 0.5
    }
}

/** Scan receipt 05: the receipt joins an expense already saved; nothing is counted twice. */
class AttachReceipt(private val repository: FinanceRepository) {
    suspend operator fun invoke(transactionUid: String, receipt: Receipt): Boolean {
        val transaction = repository.getTransaction(transactionUid) ?: return false
        repository.updateTransaction(transaction.copy(receipt = receipt))
        return true
    }
}
