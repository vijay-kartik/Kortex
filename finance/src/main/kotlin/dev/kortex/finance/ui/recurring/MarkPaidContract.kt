package dev.kortex.finance.ui.recurring

import dev.kortex.finance.domain.calc.DueUrgency
import dev.kortex.finance.domain.usecase.OccurrenceResult
import dev.kortex.finance.domain.usecase.TransactionSaveResult
import dev.kortex.finance.ui.entry.AccountOption
import dev.kortex.finance.ui.entry.CategoryOption
import java.time.LocalDate

/** Mark as paid (Figma: Recurring 03). */
data class MarkPaidState(
    val loading: Boolean = true,
    val name: String = "",
    /** "Subscription, every month". */
    val description: String = "",
    val dueOn: LocalDate = LocalDate.now(),
    val urgency: DueUrgency = DueUrgency.LATER,
    /** Where the next due date moves to once this is paid or skipped. */
    val nextDueOn: LocalDate = LocalDate.now(),
    val amount: String = "",
    val paidOn: LocalDate = LocalDate.now(),
    val today: LocalDate = LocalDate.now(),
    val accountUid: String? = null,
    val categoryUid: String? = null,
    val accounts: List<AccountOption> = emptyList(),
    val categories: List<CategoryOption> = emptyList(),
    val error: String? = null,
    val saving: Boolean = false,
) {
    val selectedAccount: AccountOption? get() = accounts.find { it.uid == accountUid }
    val selectedCategory: CategoryOption? get() = categories.find { it.uid == categoryUid }

    companion object {
        fun message(result: OccurrenceResult): String? = when (result) {
            is OccurrenceResult.Paid, is OccurrenceResult.Skipped, is OccurrenceResult.AlreadyPaid -> null
            OccurrenceResult.NotFound -> "This recurring payment was deleted."
            is OccurrenceResult.Failed -> when (result.reason) {
                TransactionSaveResult.InvalidAmount -> "Enter an amount above zero."
                TransactionSaveResult.UnknownAccount -> "Pick the account it was paid from."
                TransactionSaveResult.WrongCategoryKind -> "Pick an expense category."
                else -> "Couldn’t record it. Try again."
            }
        }
    }
}

sealed interface MarkPaidIntent {
    data class Amount(val text: String) : MarkPaidIntent
    data class PaidOn(val date: LocalDate) : MarkPaidIntent
    data class Account(val uid: String) : MarkPaidIntent
    data class Category(val uid: String?) : MarkPaidIntent
    data object MarkPaid : MarkPaidIntent
    data object Skip : MarkPaidIntent
}

sealed interface MarkPaidEffect {
    data object Close : MarkPaidEffect
}
