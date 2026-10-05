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

    data class AddAccount(val kind: AccountKind = AccountKind.BANK) : FinanceRoute

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

    fun encode(): String = when (this) {
        is AddEntry -> "entry:${if (income) "income" else "expense"}"
        is AddAccount -> "add-account:${kind.name}"
        is EditAccount -> "edit-account:$uid"
        is MonthlyReport -> "report:$month"
        Categories -> "categories"
        is CategoryForm -> "category:${kind.name}:${uid.orEmpty()}"
        Pending -> "pending"
        Recurring -> "recurring"
        is RecurringForm -> "recurring-form:${uid.orEmpty()}"
        is MarkPaid -> "mark-paid:$recurringUid:$dueOn"
        is PayBill -> "pay-bill:$statementUid"
    }

    companion object {
        fun decode(text: String): FinanceRoute? {
            val parts = text.split(':')
            return runCatching {
                when (parts[0]) {
                    "entry" -> AddEntry(income = parts[1] == "income")
                    "add-account" -> AddAccount(AccountKind.valueOf(parts[1]))
                    "edit-account" -> EditAccount(parts[1])
                    "report" -> MonthlyReport(YearMonth.parse(parts[1]))
                    "categories" -> Categories
                    "category" -> CategoryForm(parts[2].ifEmpty { null }, CategoryKind.valueOf(parts[1]))
                    "pending" -> Pending
                    "recurring" -> Recurring
                    "recurring-form" -> RecurringForm(parts[1].ifEmpty { null })
                    "mark-paid" -> MarkPaid(parts[1], LocalDate.parse(parts[2]))
                    "pay-bill" -> PayBill(parts[1])
                    else -> null
                }
            }.getOrNull()
        }
    }
}
