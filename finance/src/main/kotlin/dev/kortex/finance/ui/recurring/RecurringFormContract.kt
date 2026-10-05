package dev.kortex.finance.ui.recurring

import dev.kortex.finance.domain.model.CategoryKind
import dev.kortex.finance.domain.model.Frequency
import dev.kortex.finance.domain.model.Recurring
import dev.kortex.finance.domain.model.RecurringKind
import dev.kortex.finance.domain.usecase.FinanceSnapshot
import dev.kortex.finance.domain.usecase.RecurringDraft
import dev.kortex.finance.domain.usecase.RecurringSaveResult
import dev.kortex.finance.ui.FinanceRoute
import dev.kortex.finance.ui.accounts.AccountsUi
import dev.kortex.finance.ui.common.FinanceFormat
import dev.kortex.finance.ui.entry.AccountOption
import dev.kortex.finance.ui.entry.CategoryOption
import java.time.LocalDate

/** Add recurring payment, or editing one (Figma: Recurring 02). */
data class RecurringFormState(
    val loading: Boolean = true,
    val editUid: String? = null,
    val kind: RecurringKind = RecurringKind.SUBSCRIPTION,
    val amount: String = "",
    val name: String = "",
    val frequency: Frequency = Frequency.MONTHLY,
    val interval: Int = 1,
    val nextDueOn: LocalDate = LocalDate.now(),
    val today: LocalDate = LocalDate.now(),
    val accountUid: String? = null,
    val categoryUid: String? = null,
    val remindDaysBefore: Int = DEFAULT_REMINDER,
    val autoMarkPaid: Boolean = false,
    val paused: Boolean = false,
    val accounts: List<AccountOption> = emptyList(),
    val categories: List<CategoryOption> = emptyList(),
    val confirmDelete: Boolean = false,
    val error: String? = null,
    val saving: Boolean = false,
) {
    val editing: Boolean get() = editUid != null
    val title: String get() = if (editing) "Edit recurring payment" else "Add recurring payment"
    val saveLabel: String get() = if (editing) "Save changes" else "Save recurring payment"
    val kindHint: String
        get() = if (kind == RecurringKind.SUBSCRIPTION) "Something you can cancel, like Netflix or Spotify." else "Rent, EMIs, a gym, bills that don’t change much."
    val selectedAccount: AccountOption? get() = accounts.find { it.uid == accountUid }
    val selectedCategory: CategoryOption? get() = categories.find { it.uid == categoryUid }

    fun toDraft(): Pair<RecurringDraft?, String?> {
        val amountMinor = FinanceFormat.parseAmount(amount) ?: return null to "Enter an amount above zero."
        val account = accountUid ?: return null to "Pick the account it’s paid from."
        return RecurringDraft(
            name = name,
            kind = kind,
            amountMinor = amountMinor,
            frequency = frequency,
            interval = interval,
            nextDueOn = nextDueOn,
            accountUid = account,
            categoryUid = categoryUid,
            remindDaysBefore = remindDaysBefore,
            autoMarkPaid = autoMarkPaid,
            paused = paused,
        ) to null
    }

    companion object {
        const val DEFAULT_REMINDER = 2

        fun editing(recurring: Recurring, today: LocalDate) = RecurringFormState(
            loading = false,
            editUid = recurring.uid,
            kind = recurring.kind,
            amount = FinanceFormat.amountInput(recurring.amountMinor),
            name = recurring.name,
            frequency = recurring.frequency,
            interval = recurring.interval,
            nextDueOn = recurring.nextDueOn,
            today = today,
            accountUid = recurring.accountUid,
            categoryUid = recurring.categoryUid,
            remindDaysBefore = recurring.remindDaysBefore,
            autoMarkPaid = recurring.autoMarkPaid,
            paused = recurring.paused,
        )

        /** Every account a payment can come from, cards included (Netflix on a credit card). */
        fun options(snapshot: FinanceSnapshot): Pair<List<AccountOption>, List<CategoryOption>> {
            val accounts = snapshot.accounts.filterNot { it.archived }.map { account ->
                AccountOption(account.uid, RecurringLabels.account(account), AccountsUi.subtitle(account, snapshot.accountsByUid))
            }
            val categories = snapshot.categories.filter { it.kind == CategoryKind.EXPENSE }.map { CategoryOption(it.uid, it.name, it.colorToken) }
            return accounts to categories
        }

        fun message(result: RecurringSaveResult): String? = when (result) {
            is RecurringSaveResult.Saved -> null
            RecurringSaveResult.BlankName -> "Give it a name."
            RecurringSaveResult.InvalidAmount -> "Enter an amount above zero."
            RecurringSaveResult.UnknownAccount -> "Pick the account it’s paid from."
            RecurringSaveResult.WrongCategoryKind -> "Pick an expense category."
            RecurringSaveResult.InvalidReminder -> "Pick when to be reminded."
            RecurringSaveResult.NotFound -> "This recurring payment was deleted."
        }
    }
}

sealed interface RecurringFormIntent {
    data class Kind(val kind: RecurringKind) : RecurringFormIntent
    data class Amount(val text: String) : RecurringFormIntent
    data class Name(val text: String) : RecurringFormIntent
    data class Repeats(val frequency: Frequency) : RecurringFormIntent
    data class NextDue(val date: LocalDate) : RecurringFormIntent
    data class Account(val uid: String) : RecurringFormIntent
    data class Category(val uid: String?) : RecurringFormIntent
    data class Remind(val days: Int) : RecurringFormIntent
    data class AutoMarkPaid(val on: Boolean) : RecurringFormIntent
    data class Paused(val on: Boolean) : RecurringFormIntent
    data object AddAccount : RecurringFormIntent
    data object Save : RecurringFormIntent
    data object AskDelete : RecurringFormIntent
    data object CancelDelete : RecurringFormIntent
    data object ConfirmDelete : RecurringFormIntent
}

sealed interface RecurringFormEffect {
    data object Close : RecurringFormEffect
    data class Navigate(val route: FinanceRoute) : RecurringFormEffect

    /** Saved with a reminder: Android 13+ asks for permission to post it. */
    data object AskNotificationPermission : RecurringFormEffect
}
