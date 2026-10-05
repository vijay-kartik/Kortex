package dev.kortex.finance.domain

import java.security.MessageDigest
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

/**
 * Document ids (docs/FINANCE_PLAN.md › Identity). Where two devices could create the same record —
 * the same SMS pasted on both, Netflix marked paid on both — the id comes from what the record is,
 * so both write one document instead of two.
 */
object FinanceIds {

    fun random(): String = UUID.randomUUID().toString()

    /** One SMS is one transaction, however often or wherever it's pasted. */
    fun smsTransaction(sender: String, body: String): String =
        "sms_" + hash(sender.trim().uppercase(Locale.ROOT) + "\n" + body.trim())

    /** The transaction that pays one occurrence of a recurring payment. */
    fun recurringOccurrence(recurringUid: String, dueOn: LocalDate): String = "rec_" + hash("$recurringUid|$dueOn")

    fun statement(cardUid: String, statementOn: LocalDate): String = "stmt_" + hash("$cardUid|$statementOn")

    fun merchant(payeeKey: String): String = "mer_" + hash(payeeKey(payeeKey))

    /**
     * How a merchant or UPI id is matched: lowercase, trimmed, inner whitespace collapsed. So
     * "WHOLE FOODS  MARKET" from an SMS and "Whole Foods Market" typed in are one merchant.
     */
    fun payeeKey(raw: String): String = raw.trim().lowercase(Locale.ROOT).replace(Whitespace, " ")

    /** First 32 hex characters of SHA-256, as link uids are. */
    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(32)

    private val Whitespace = Regex("\\s+")
}
