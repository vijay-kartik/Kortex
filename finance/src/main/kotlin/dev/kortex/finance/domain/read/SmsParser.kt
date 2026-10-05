package dev.kortex.finance.domain.read

import java.time.LocalDate

/**
 * Reads Indian bank SMS with patterns that cover how HDFC, ICICI, SBI, Axis, Kotak and most
 * others write them (Figma: Paste SMS 01–09). Anything it can't read goes to the LLM, masked.
 */
object SmsParser {

    fun classify(text: String, today: LocalDate = LocalDate.now()): SmsKind {
        if (Otp.containsMatchIn(text)) return SmsKind.OTP
        if (parse(text, today) != null) return SmsKind.TRANSACTION
        return if (Promo.containsMatchIn(text)) SmsKind.PROMO else SmsKind.UNREADABLE
    }

    /** Null when there's no amount or no way to tell money in from money out. */
    fun parse(text: String, today: LocalDate): ParsedSms? {
        if (Otp.containsMatchIn(text)) return null
        val spans = mutableMapOf<SmsField, IntRange>()

        val balance = Balance.find(text)?.also { spans[SmsField.BALANCE] = it.groups[1]!!.range }
        val limit = Limit.find(text)?.also { spans[SmsField.LIMIT] = it.groups[1]!!.range }
        val skip = listOfNotNull(balance?.range, limit?.range)
        val amount = TextReading.CurrencyAmount.findAll(text).firstOrNull { m -> skip.none { m.range.first in it } } ?: return null
        val amountMinor = TextReading.minorOf(amount.groupValues[1])?.takeIf { it > 0 } ?: return null
        spans[SmsField.AMOUNT] = amount.range

        val debit = Debit.find(text)
        val credit = Credit.findAll(text).firstOrNull { m -> !CreditCardPhrase.matches(text.substring(m.range.first).take(12)) }
        val direction = when {
            debit == null && credit == null -> return null
            credit == null -> MoneyDirection.DEBIT
            debit == null -> MoneyDirection.CREDIT
            else -> if (debit.range.first < credit.range.first) MoneyDirection.DEBIT else MoneyDirection.CREDIT
        }

        val last4Match = Ending.find(text) ?: Masked.find(text)
        val last4 = last4Match?.groups?.get(1)
        last4?.let { spans[SmsField.LAST4] = last4Match.range }
        val instrument = last4Match?.let { instrumentNear(text, it.range.first) } ?: when {
            Regex("""\bcard\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Instrument.CARD
            Regex("""\b(a/c|acct|account)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Instrument.ACCOUNT
            else -> Instrument.UNKNOWN
        }

        val date = TextReading.date(text, today)?.also { spans[SmsField.DATE] = it.range }
        val time = TextReading.time(text)

        val payee = payee(text, direction)?.also { spans[SmsField.PAYEE] = it.range }
        val ref = Ref.find(text)?.groups?.get(1)?.also { spans[SmsField.REF] = it.range }

        return ParsedSms(
            direction = direction,
            amountMinor = amountMinor,
            last4 = last4?.value,
            instrument = instrument,
            date = date?.value,
            time = time?.value,
            payee = payee?.value,
            payeeIsUpiId = payee?.value?.let { UpiId.matches(it) } ?: false,
            ref = ref?.value,
            availableBalanceMinor = balance?.let { TextReading.minorOf(it.groupValues[1]) },
            availableLimitMinor = limit?.let { TextReading.minorOf(it.groupValues[1]) },
            bank = Banks.firstOrNull { (pattern, _) -> pattern.containsMatchIn(text) }?.second,
            cardPaymentReceived = direction == MoneyDirection.CREDIT && PaymentReceived.containsMatchIn(text) && instrument != Instrument.ACCOUNT,
            spans = spans,
        )
    }

    private fun instrumentNear(text: String, at: Int): Instrument {
        val before = text.substring((at - 28).coerceAtLeast(0), at).lowercase()
        val cardAt = before.lastIndexOf("card")
        val accountAt = maxOf(before.lastIndexOf("a/c"), before.lastIndexOf("acct"), before.lastIndexOf("account"), before.lastIndexOf(" ac "))
        return when {
            cardAt < 0 && accountAt < 0 -> Instrument.UNKNOWN
            cardAt > accountAt -> Instrument.CARD
            else -> Instrument.ACCOUNT
        }
    }

    /** "at WHOLE FOODS MARKET on", "to paytmqr2810050@paytm on", "by NEFT from ACME TECHNOLOGIES PVT LTD." */
    private fun payee(text: String, direction: MoneyDirection): Found<String>? {
        UpiId.find(text)?.let { return Found(it.value, it.range) }
        val patterns = if (direction == MoneyDirection.DEBIT) DebitPayee else CreditPayee
        for (pattern in patterns) {
            val m = pattern.find(text) ?: continue
            val group = m.groups[1] ?: continue
            val value = group.value.trim().trimEnd('.', ',', '-')
            if (value.isBlank() || NotAPayee.containsMatchIn(value)) continue
            val start = group.range.first + (group.value.length - group.value.trimStart().length)
            return Found(value, start until start + value.length)
        }
        return null
    }

    private val Otp = Regex(
        """\b(OTP|one[\s-]?time\s+pass(word|code)|verification\s+code|security\s+code|passcode)\b|\bis\s+your\s+.{0,30}\bcode\b""",
        RegexOption.IGNORE_CASE,
    )
    private val Promo = Regex(
        """\b(offer|cashback\s+offer|pre-?approved|apply\s+now|eligible\s+for|congratulations|win|discount|upto|sale|loan\s+of|limit\s+increase)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val Debit = Regex(
        """\b(debited|spent|paid|sent|withdrawn|withdrawal|purchase|charged|debit(ed)?\s+for|txn\s+of|used\s+(at|for)|payment\s+of\s+.{0,25}\s+made)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val Credit = Regex("""\b(credited|received|deposited|credit(ed)?\s+(of|to|for)?)\b""", RegexOption.IGNORE_CASE)
    private val CreditCardPhrase = Regex("""(?i)credit\s+card.*""")
    private val PaymentReceived = Regex(
        """payment\s+(of\s+.{0,25}\s+)?(has\s+been\s+)?received|received\s+(a\s+|your\s+)?payment|thank\s+you\s+for\s+(the\s+|your\s+)?payment|payment\s+.{0,30}credited\s+to\s+your\s+.{0,20}card""",
        RegexOption.IGNORE_CASE,
    )
    private val Ending = Regex("""(?:ending|ends)\s*(?:with|in)?\s*[xX*•]*\s*(\d{4})\b""", RegexOption.IGNORE_CASE)
    private val Masked = Regex("""(?<![\w])[xX*•]{1,12}(\d{4})\b""")
    private val Balance = Regex(
        """(?:avl\.?\s*bal(?:ance)?|available\s+bal(?:ance)?|a/c\s+bal(?:ance)?|bal(?:ance)?)\s*(?:is|:|-)?\s*(?:Rs\.?|INR|₹)\s?([0-9][0-9,]*(?:\.[0-9]{1,2})?)""",
        RegexOption.IGNORE_CASE,
    )
    private val Limit = Regex(
        """(?:avl\.?\s*(?:lmt|limit)|available\s+(?:credit\s+)?limit|avl\.?\s*cr\.?\s*lmt)\s*(?:is|:|-)?\s*(?:Rs\.?|INR|₹)\s?([0-9][0-9,]*(?:\.[0-9]{1,2})?)""",
        RegexOption.IGNORE_CASE,
    )
    private val Ref = Regex(
        """\b(?:UPI\s*Ref(?:erence)?\.?\s*(?:No\.?)?|Ref(?:erence)?\.?\s*(?:No\.?|#)?|RRN|Txn\s*(?:ID|No)\.?)\s*[:.-]?\s*([A-Za-z0-9]{6,})""",
        RegexOption.IGNORE_CASE,
    )
    val UpiId = Regex("""[A-Za-z0-9.\-_]{2,}@[A-Za-z][A-Za-z0-9]{1,}""")
    private const val PayeeEnd = """(?=\s+on\s|\s+via\s|\s+ref|\s+upi|\s+avl|\s+info|\s+for\s|\.\s|\.$|,|;|\s*\(|$)"""
    private val DebitPayee = listOf(
        Regex("""\bat\s+(.+?)$PayeeEnd""", RegexOption.IGNORE_CASE),
        Regex("""\bto\s+(?:VPA\s+)?(.+?)$PayeeEnd""", RegexOption.IGNORE_CASE),
        Regex("""\bInfo[:\s]+(.+?)$PayeeEnd""", RegexOption.IGNORE_CASE),
        Regex("""\btowards\s+(.+?)$PayeeEnd""", RegexOption.IGNORE_CASE),
    )
    private val CreditPayee = listOf(
        Regex("""\bfrom\s+(?:VPA\s+)?(.+?)$PayeeEnd""", RegexOption.IGNORE_CASE),
        Regex("""\bby\s+(?!NEFT|IMPS|RTGS|UPI)(.+?)$PayeeEnd""", RegexOption.IGNORE_CASE),
    )
    // "to A/c XX1234", "at 06:42 PM", "from your card": an account or a time, not someone.
    private val NotAPayee = Regex("""^(your\s+)?(a/c|ac|acct|account|card|bank|you)\b|^[xX*]+\d{3,4}$|^\d+$|^\d{1,2}[:.]\d{2}""", RegexOption.IGNORE_CASE)

    private val Banks = listOf(
        Regex("""\bHDFC\b""", RegexOption.IGNORE_CASE) to "HDFC Bank",
        Regex("""\bICICI\b""", RegexOption.IGNORE_CASE) to "ICICI Bank",
        Regex("""\bSBI\b|State Bank""", RegexOption.IGNORE_CASE) to "State Bank of India",
        Regex("""\bAxis\b""", RegexOption.IGNORE_CASE) to "Axis Bank",
        Regex("""\bKotak\b""", RegexOption.IGNORE_CASE) to "Kotak Mahindra Bank",
        Regex("""\bYes\s?Bank\b""", RegexOption.IGNORE_CASE) to "Yes Bank",
        Regex("""\bIndusInd\b""", RegexOption.IGNORE_CASE) to "IndusInd Bank",
        Regex("""\bIDFC\b""", RegexOption.IGNORE_CASE) to "IDFC First Bank",
        Regex("""\bPNB\b|Punjab National""", RegexOption.IGNORE_CASE) to "Punjab National Bank",
        Regex("""\bBank of Baroda\b|\bBOB\b""", RegexOption.IGNORE_CASE) to "Bank of Baroda",
        Regex("""\bAmex\b|American Express""", RegexOption.IGNORE_CASE) to "American Express",
    )
}
