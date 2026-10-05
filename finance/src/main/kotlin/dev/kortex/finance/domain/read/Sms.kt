package dev.kortex.finance.domain.read

import java.time.LocalDate
import java.time.LocalTime

enum class SmsKind {
    TRANSACTION,

    /** One-time passwords and verification codes: never read further, never stored (Paste SMS 07). */
    OTP,

    /** Offers and adverts (Paste SMS 07). */
    PROMO,

    /** Nothing a bank SMS has: no amount or no direction. The LLM gets a try. */
    UNREADABLE,
}

enum class MoneyDirection { DEBIT, CREDIT }

/** What the last 4 digits belong to, by the words around them. */
enum class Instrument { CARD, ACCOUNT, UNKNOWN }

/** The parts of an SMS the review underlines. */
enum class SmsField { AMOUNT, LAST4, PAYEE, DATE, BALANCE, LIMIT, REF }

/** A bank SMS, read (docs/FINANCE_PLAN.md › How entries get in › Paste SMS). */
data class ParsedSms(
    val direction: MoneyDirection,
    val amountMinor: Long,
    val last4: String? = null,
    val instrument: Instrument = Instrument.UNKNOWN,
    val date: LocalDate? = null,
    val time: LocalTime? = null,
    /** Who was paid or who paid, as written; a UPI id when [payeeIsUpiId]. */
    val payee: String? = null,
    val payeeIsUpiId: Boolean = false,
    /** The bank's or UPI reference, for spotting the same payment twice. */
    val ref: String? = null,
    /** "Avl Bal" / "Avl Lmt": shown for reference, never applied (balances only change through entries). */
    val availableBalanceMinor: Long? = null,
    val availableLimitMinor: Long? = null,
    /** "HDFC Bank", when the text names one. */
    val bank: String? = null,
    /** A "payment received" credit on a card: a card payment, not income. */
    val cardPaymentReceived: Boolean = false,
    val spans: Map<SmsField, IntRange> = emptyMap(),
    /** Read by the LLM rather than the patterns: nothing to underline. */
    val fromModel: Boolean = false,
) {
    /** "Avl Lmt" only appears on credit cards. */
    val isCreditCard: Boolean get() = availableLimitMinor != null || instrument == Instrument.CARD && availableBalanceMinor == null
}
