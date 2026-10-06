package dev.kortex.finance.domain.usecase

import dev.kortex.finance.domain.FinanceIds
import dev.kortex.finance.domain.model.Account
import dev.kortex.finance.domain.model.CategoryKind
import dev.kortex.finance.domain.model.Transaction
import dev.kortex.finance.domain.model.TransactionType
import dev.kortex.finance.domain.port.Clock
import dev.kortex.finance.domain.read.EntryMatching
import dev.kortex.finance.domain.read.Instrument
import dev.kortex.finance.domain.read.MoneyDirection
import dev.kortex.finance.domain.read.ParsedSms
import java.time.LocalDate

/** What saving an SMS records. */
enum class SmsEntryType {
    EXPENSE,
    INCOME,

    /** "Payment received" on a card (docs/FINANCE_PLAN.md › Paste SMS). */
    CARD_PAYMENT,

    /** Any other credit on a card: a refund, which v1 doesn't handle. */
    CARD_REFUND,
    ;

    val transactionType: TransactionType
        get() = when (this) {
            INCOME -> TransactionType.INCOME
            CARD_PAYMENT -> TransactionType.CARD_PAYMENT
            else -> TransactionType.EXPENSE
        }
}

/** An SMS read and matched against this phone's finances: what the review shows, or what's saved on its own. */
data class SmsEntryPlan(
    val sms: ParsedSms,
    val type: SmsEntryType,
    /** The card or account the SMS's last 4 belong to; null means ask (Paste SMS 08). */
    val account: Account?,
    val merchant: MerchantGuess,
    /** The SMS's day, never after today; today when it gives none. */
    val date: LocalDate,
    val occurredAtMillis: Long,
    /** The same SMS pasted twice, received then pasted, or on two phones, is one entry. */
    val uid: String,
    /** An entry this one probably repeats (Paste SMS 05). */
    val duplicate: Transaction?,
) {
    /** The SMS's last 4 when none of your cards or accounts has them (Paste SMS 08). */
    val unknownLast4: String? get() = if (account == null) sms.last4 else null
}

/**
 * Paste SMS 02–08, and the automatic path: the account by last 4, what kind of entry it is, the
 * merchant's name and category, and whether it's already been added.
 */
class PrepareSmsEntry(
    private val suggestMerchant: SuggestMerchant,
    private val clock: Clock,
) {
    suspend operator fun invoke(text: String, sms: ParsedSms, snapshot: FinanceSnapshot): SmsEntryPlan {
        val account = EntryMatching.accountFor(sms.last4, sms.instrument, snapshot.accounts)
        val onCard = account?.kind?.isCard ?: (sms.instrument == Instrument.CARD || sms.availableLimitMinor != null)
        val type = when {
            sms.direction == MoneyDirection.DEBIT -> SmsEntryType.EXPENSE
            !onCard -> SmsEntryType.INCOME
            sms.cardPaymentReceived -> SmsEntryType.CARD_PAYMENT
            else -> SmsEntryType.CARD_REFUND
        }
        val merchant = if (type == SmsEntryType.EXPENSE || type == SmsEntryType.INCOME) {
            suggestMerchant(sms.payee, if (type == SmsEntryType.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE, sms.payeeIsUpiId)
        } else {
            MerchantGuess(null, null, null)
        }
        val date = sms.date?.let { minOf(it, clock.today()) } ?: clock.today()
        val at = clock.millisAt(date, sms.time)
        // Sender left out on purpose: Paste never knows it, and both paths must give the same id.
        val uid = FinanceIds.smsTransaction("", text)
        val duplicate = snapshot.transactions.find { it.uid == uid }
            ?: EntryMatching.duplicateOf(sms.amountMinor, account?.uid, at, sms.time != null, sms.ref, snapshot.transactions, EntryMatching.SMS_DUPLICATE_MINUTES, clock::dayOf)
        return SmsEntryPlan(sms, type, account, merchant, date, at, uid, duplicate)
    }
}
