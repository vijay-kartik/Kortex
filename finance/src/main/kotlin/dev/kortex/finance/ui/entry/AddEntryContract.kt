package dev.kortex.finance.ui.entry

import dev.kortex.finance.domain.model.AccountKind
import dev.kortex.finance.domain.model.CategoryKind
import dev.kortex.finance.domain.model.TransactionType
import dev.kortex.finance.domain.usecase.FinanceSnapshot
import dev.kortex.finance.domain.usecase.TransactionSaveResult
import dev.kortex.finance.ui.FinanceRoute
import dev.kortex.finance.ui.accounts.AccountsUi
import java.time.LocalDate

data class AccountOption(val uid: String, val name: String, val detail: String)

data class CategoryOption(val uid: String, val name: String, val colorToken: String)

/** Add expense / Add income, entered by hand (Figma: finances-add-expense, finances-add-income). */
data class AddEntryState(
    val loading: Boolean = true,
    val income: Boolean = false,
    val amount: String = "",
    val merchant: String = "",
    val date: LocalDate = LocalDate.now(),
    val accountUid: String? = null,
    val categoryUid: String? = null,
    /** True once the user picked a category themselves, so a suggestion never overrides it. */
    val categoryPicked: Boolean = false,
    /** The merchant name the current category was suggested from. */
    val suggestedFrom: String? = null,
    val note: String = "",
    val accounts: List<AccountOption> = emptyList(),
    val categories: List<CategoryOption> = emptyList(),
    val datePickerOpen: Boolean = false,
    val error: String? = null,
    val saving: Boolean = false,
) {
    val title: String get() = if (income) "Add Income" else "Add Expense"
    val merchantLabel: String get() = if (income) "From" else "Merchant / Description"
    val accountLabel: String get() = if (income) "Received into" else "Paid from"
    val saveLabel: String get() = if (income) "Save income" else "Save expense"
    val selectedAccount: AccountOption? get() = accounts.find { it.uid == accountUid }
    val noAccounts: Boolean get() = !loading && accounts.isEmpty()

    companion object {
        /** Accounts the entry can use (income never goes into a credit card) and the matching categories. */
        fun options(snapshot: FinanceSnapshot, income: Boolean): Pair<List<AccountOption>, List<CategoryOption>> {
            val accounts = snapshot.accounts
                .filter { !it.archived && !(income && it.kind == AccountKind.CREDIT_CARD) }
                .map { AccountOption(it.uid, it.name, AccountsUi.subtitle(it, snapshot.accountsByUid)) }
            val kind = if (income) CategoryKind.INCOME else CategoryKind.EXPENSE
            val categories = snapshot.categories.filter { it.kind == kind }.map { CategoryOption(it.uid, it.name, it.colorToken) }
            return accounts to categories
        }

        /** The account last used for this kind of entry, else the first one. */
        fun defaultAccount(snapshot: FinanceSnapshot, income: Boolean, options: List<AccountOption>): String? {
            val type = if (income) TransactionType.INCOME else TransactionType.EXPENSE
            val recent = snapshot.transactions.filter { it.type == type }.maxByOrNull { it.occurredAtMillis }?.accountUid
            return recent?.takeIf { uid -> options.any { it.uid == uid } } ?: options.firstOrNull()?.uid
        }

        fun message(result: TransactionSaveResult): String? = when (result) {
            is TransactionSaveResult.Saved, is TransactionSaveResult.AlreadySaved -> null
            TransactionSaveResult.InvalidAmount -> "Enter an amount above zero."
            TransactionSaveResult.UnknownAccount -> "Pick an account."
            TransactionSaveResult.InvalidTarget -> "Pick another account."
            TransactionSaveResult.WrongCategoryKind -> "Pick a category."
            TransactionSaveResult.OpeningNotAllowed -> "Opening balances are set when an account is added."
            TransactionSaveResult.RefundNotSupported -> "Income can’t go into a credit card yet."
        }
    }
}

sealed interface AddEntryIntent {
    data class Amount(val text: String) : AddEntryIntent
    data class Merchant(val text: String) : AddEntryIntent
    data class Note(val text: String) : AddEntryIntent
    data class ShowDatePicker(val open: Boolean) : AddEntryIntent
    data class PickDate(val date: LocalDate) : AddEntryIntent
    data class PickAccount(val uid: String) : AddEntryIntent
    data class PickCategory(val uid: String) : AddEntryIntent
    data object NewCategory : AddEntryIntent
    data object ManageCategories : AddEntryIntent
    data object AddAccount : AddEntryIntent
    data object Save : AddEntryIntent
}

sealed interface AddEntryEffect {
    data object Close : AddEntryEffect
    data class Navigate(val route: FinanceRoute) : AddEntryEffect
}
