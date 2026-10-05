package dev.kortex.finance.domain.usecase

import dev.kortex.finance.domain.FinanceIds
import dev.kortex.finance.domain.model.CategoryKind
import dev.kortex.finance.domain.model.Receipt
import dev.kortex.finance.domain.port.Clock
import dev.kortex.finance.domain.read.FinanceReader
import dev.kortex.finance.domain.read.ParsedReceipt
import dev.kortex.finance.domain.read.ParsedSms
import dev.kortex.finance.domain.read.ReceiptParser
import dev.kortex.finance.domain.read.SmsKind
import dev.kortex.finance.domain.read.SmsParser
import dev.kortex.finance.domain.read.TextReading
import dev.kortex.finance.domain.repository.FinanceRepository
import kotlinx.coroutines.flow.first

sealed interface SmsResult {
    data class Read(val sms: ParsedSms) : SmsResult

    /** An OTP, an advert, or nothing a bank would send (Paste SMS 07). Nothing was stored. */
    data class NotAPayment(val kind: SmsKind) : SmsResult
}

/** Paste SMS 01: patterns first; what they can't read goes to the LLM, masked. OTPs never do. */
class ReadSms(private val reader: FinanceReader, private val clock: Clock) {
    suspend operator fun invoke(text: String): SmsResult {
        val today = clock.today()
        when (val kind = SmsParser.classify(text, today)) {
            SmsKind.OTP, SmsKind.PROMO -> return SmsResult.NotAPayment(kind)
            SmsKind.TRANSACTION -> return SmsResult.Read(SmsParser.parse(text, today)!!)
            SmsKind.UNREADABLE -> Unit
        }
        val reading = reader.readSms(TextReading.maskForModel(text), today)?.takeIf { it.amountMinor > 0 }
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

/** Remembered merchants first; then the LLM tidies the name and suggests a category; else a tidy name alone. */
class SuggestMerchant(private val repository: FinanceRepository, private val reader: FinanceReader) {
    suspend operator fun invoke(raw: String?, kind: CategoryKind, isUpiId: Boolean = false): MerchantGuess {
        val name = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return MerchantGuess(null, null, null)
        repository.findMerchant(FinanceIds.payeeKey(name))?.let { known ->
            val category = known.categoryUid?.takeIf { repository.getCategory(it)?.kind == kind }
            return MerchantGuess(known.displayName, category, category?.let { MerchantGuess.Source.REMEMBERED })
        }
        if (isUpiId) return MerchantGuess(null, null, null, needsName = true)
        val categories = repository.observeCategories().first().filter { it.kind == kind }
        val suggestion = reader.suggestMerchant(TextReading.maskForModel(name), categories)
        val category = suggestion?.categoryUid?.takeIf { uid -> categories.any { it.uid == uid } }
        return MerchantGuess(
            name = suggestion?.name?.trim()?.takeIf { it.isNotEmpty() } ?: TextReading.tidyName(name),
            categoryUid = category,
            source = category?.let { MerchantGuess.Source.MODEL },
        )
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
