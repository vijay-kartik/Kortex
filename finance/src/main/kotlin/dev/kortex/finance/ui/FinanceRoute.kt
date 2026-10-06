package dev.kortex.finance.ui

import dev.kortex.finance.domain.model.AccountKind
import dev.kortex.finance.domain.model.CategoryKind
import java.time.LocalDate
import java.time.YearMonth

/**
 * A full-screen Finance screen the host shows over the tabs. They stack — Add expense › Manage
 * categories › New category — and Back closes the top one. [encode] / [decode] let the host save
 * the stack across process death.
 */
sealed interface FinanceRoute {
    /** Add expense or Add income (Figma: finances-add-expense, finances-add-income). */
    data class AddEntry(val income: Boolean = false) : FinanceRoute

    /** [prefill] carries what an SMS said about a card the phone doesn't know (Figma: Paste SMS 09). */
    data class AddAccount(val kind: AccountKind = AccountKind.BANK, val prefill: AccountPrefill? = null) : FinanceRoute

    data class EditAccount(val uid: String) : FinanceRoute

    data class MonthlyReport(val month: YearMonth) : FinanceRoute

    data object Categories : FinanceRoute

    /** New category when [uid] is null, otherwise edit (and delete) that one. */
    data class CategoryForm(val uid: String? = null, val kind: CategoryKind = CategoryKind.EXPENSE) : FinanceRoute

    /** Pending payments: card bills and recurring payments due in the next 30 days. */
    data object Pending : FinanceRoute

    /** Recurring payments (Figma: Recurring 01). */
    data object Recurring : FinanceRoute

    /** Add recurring payment when [uid] is null, otherwise edit that one (Figma: Recurring 02). */
    data class RecurringForm(val uid: String? = null) : FinanceRoute

    /** Mark as paid for one occurrence (Figma: Recurring 03). */
    data class MarkPaid(val recurringUid: String, val dueOn: LocalDate) : FinanceRoute

    /** Pay card bill against a statement (Figma: Recurring 04). */
    data class PayBill(val statementUid: String) : FinanceRoute

    /**
     * Paste SMS (Figma: Paste SMS 01–09): [text] when shared from Messages or opened from To review,
     * else the clipboard is read once. [inboxId] is the received SMS it came from, which saving
     * marks done.
     */
    data class PasteSms(val text: String? = null, val inboxId: String? = null) : FinanceRoute

    /** Received bank SMS waiting to be checked (docs/SMS_AUTO_PLAN.md, phase 6). */
    data object SmsReview : FinanceRoute

    /** Scan receipt (Figma: Scan receipt 01–06): opens Android's document scanner straight away. */
    data object ScanReceipt : FinanceRoute

    fun encode(): String = when (this) {
        is AddEntry -> "entry:${if (income) "income" else "expense"}"
        is AddAccount -> "add-account:${kind.name}" + prefill?.let { ":" + Text.encode(it.fields()) }.orEmpty()
        is EditAccount -> "edit-account:$uid"
        is MonthlyReport -> "report:$month"
        Categories -> "categories"
        is CategoryForm -> "category:${kind.name}:${uid.orEmpty()}"
        Pending -> "pending"
        Recurring -> "recurring"
        is RecurringForm -> "recurring-form:${uid.orEmpty()}"
        is MarkPaid -> "mark-paid:$recurringUid:$dueOn"
        is PayBill -> "pay-bill:$statementUid"
        is PasteSms -> "paste-sms:" + text?.let(Text::encode).orEmpty() + inboxId?.let { ":$it" }.orEmpty()
        SmsReview -> "sms-review"
        ScanReceipt -> "scan-receipt"
    }

    companion object {
        fun decode(text: String): FinanceRoute? {
            val parts = text.split(':')
            return runCatching {
                when (parts[0]) {
                    "entry" -> AddEntry(income = parts[1] == "income")
                    "add-account" -> AddAccount(
                        AccountKind.valueOf(parts[1]),
                        parts.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let { AccountPrefill.of(Text.decode(it)) },
                    )
                    "edit-account" -> EditAccount(parts[1])
                    "report" -> MonthlyReport(YearMonth.parse(parts[1]))
                    "categories" -> Categories
                    "category" -> CategoryForm(parts[2].ifEmpty { null }, CategoryKind.valueOf(parts[1]))
                    "pending" -> Pending
                    "recurring" -> Recurring
                    "recurring-form" -> RecurringForm(parts[1].ifEmpty { null })
                    "mark-paid" -> MarkPaid(parts[1], LocalDate.parse(parts[2]))
                    "pay-bill" -> PayBill(parts[1])
                    "paste-sms" -> PasteSms(
                        parts.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let(Text::decode),
                        parts.getOrNull(2)?.takeIf { it.isNotEmpty() },
                    )
                    "sms-review" -> SmsReview
                    "scan-receipt" -> ScanReceipt
                    else -> null
                }
            }.getOrNull()
        }
    }
}

/** What Add card is filled in with from an SMS: the name, bank, last 4 and the "Avl Lmt" it showed. */
data class AccountPrefill(
    val name: String? = null,
    val institution: String? = null,
    val last4: String? = null,
    val availableLimitMinor: Long? = null,
) {
    internal fun fields(): String = listOf(name.orEmpty(), institution.orEmpty(), last4.orEmpty(), availableLimitMinor?.toString().orEmpty())
        .joinToString(SEPARATOR)

    internal companion object {
        private const val SEPARATOR = "\u001F"

        fun of(fields: String): AccountPrefill {
            val parts = fields.split(SEPARATOR)
            fun at(i: Int) = parts.getOrNull(i)?.takeIf { it.isNotEmpty() }
            return AccountPrefill(at(0), at(1), at(2), at(3)?.toLongOrNull())
        }
    }
}

/** Free text inside a route: URL-safe Base64, so its colons and pipes don't split the route. */
private object Text {
    fun encode(text: String): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray(Charsets.UTF_8))

    fun decode(text: String): String = String(java.util.Base64.getUrlDecoder().decode(text), Charsets.UTF_8)
}
