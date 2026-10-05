package dev.kortex.finance.ui.accounts

import dev.kortex.finance.domain.model.Account
import dev.kortex.finance.domain.model.AccountKind
import dev.kortex.finance.domain.model.BankType
import dev.kortex.finance.domain.usecase.AccountDraft
import dev.kortex.finance.domain.usecase.AccountSaveResult
import dev.kortex.finance.ui.AccountPrefill
import dev.kortex.finance.ui.common.FinanceFormat

/** What Delete account shows before it goes (Figma: delete-account-confirm). */
data class DeleteSummary(val balanceMinor: Long, val entryCount: Int, val recurringNames: List<String>)

/**
 * Add account (bank, credit card, cash) and Edit account (Figma: add-account, add-account · bank,
 * edit-account, edit-account · bank). Fields are kept as typed; [toDraft] reads them.
 */
data class AccountFormState(
    val loading: Boolean = false,
    /** Null when adding. */
    val editUid: String? = null,
    val kind: AccountKind = AccountKind.BANK,
    val name: String = "",
    /** As typed; only the last 4 digits are kept for now. */
    val number: String = "",
    /** On edit: the digits already saved, shown as the placeholder. */
    val savedLast4: String? = null,
    val institution: String = "",
    val holder: String = "",
    val expiry: String = "",
    val bankType: BankType = BankType.SAVINGS,
    val ifsc: String = "",
    val statementDay: String = "",
    val dueDay: String = "",
    val creditLimit: String = "",
    /** Opening balance, or a card's outstanding today. Add only. */
    val opening: String = "",
    /** On edit: the balance from entries, read-only. */
    val balanceMinor: Long? = null,
    val error: String? = null,
    val saving: Boolean = false,
    val deleteSummary: DeleteSummary? = null,
    /** Set when filled in from an SMS (Paste SMS 09): the bank it named and the "Avl Lmt" it showed. */
    val fromSmsBank: String? = null,
    val smsAvailableLimitMinor: Long? = null,
) {
    val editing: Boolean get() = editUid != null
    val fromSms: Boolean get() = fromSmsBank != null || smsAvailableLimitMinor != null
    val title: String get() = if (editing) "Edit account" else if (fromSms && kind == AccountKind.CREDIT_CARD) "Add card" else "Add account"
    val saveLabel: String get() = if (editing) "Save changes" else if (fromSms) "Add and continue" else "Add account"

    /** Null with the error to show when a field can't be read. */
    fun toDraft(): Pair<AccountDraft?, String?> {
        val digits = number.filter { it.isDigit() }
        val last4 = if (digits.isEmpty()) savedLast4 else if (digits.length < 4) return null to "Enter at least the last 4 digits." else digits.takeLast(4)
        fun day(text: String): Int? = text.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
        if (statementDay.isNotBlank() && day(statementDay) == null || dueDay.isNotBlank() && day(dueDay) == null) {
            return null to "Statement and due dates are days of the month, 1–31."
        }
        val limit = creditLimit.takeIf { it.isNotBlank() }?.let { FinanceFormat.parseAmount(it) ?: return null to "Check the credit limit." }
        val openingMinor = opening.takeIf { it.isNotBlank() }?.let { FinanceFormat.parseAmount(it) ?: if (it.trim().matches(Regex("0+(\\.0*)?"))) 0L else return null to "Check the amount." }
            // From an SMS: what's owed today is the limit less the "Avl Lmt" it showed.
            ?: smsOutstandingMinor(limit)
            ?: 0L
        return AccountDraft(
            kind = kind,
            name = name,
            institution = institution,
            last4 = last4,
            bankType = bankType,
            ifsc = ifsc,
            holder = holder,
            expiry = expiry,
            creditLimitMinor = limit,
            statementDay = day(statementDay),
            dueDay = day(dueDay),
            openingMinor = openingMinor,
        ) to null
    }

    /** Limit − "Avl Lmt" from the SMS, for a card added from one; null otherwise. */
    fun smsOutstandingMinor(limit: Long? = creditLimit.takeIf { it.isNotBlank() }?.let(FinanceFormat::parseAmount)): Long? {
        if (kind != AccountKind.CREDIT_CARD || limit == null) return null
        return smsAvailableLimitMinor?.let { (limit - it).coerceAtLeast(0) }
    }

    companion object {
        fun prefilled(kind: AccountKind, prefill: AccountPrefill) = AccountFormState(
            kind = kind,
            name = prefill.name.orEmpty(),
            institution = prefill.institution.orEmpty(),
            savedLast4 = prefill.last4,
            fromSmsBank = prefill.institution,
            smsAvailableLimitMinor = prefill.availableLimitMinor,
        )

        fun editing(account: Account, balanceMinor: Long) = AccountFormState(
            editUid = account.uid,
            kind = account.kind,
            name = account.name,
            savedLast4 = account.last4,
            institution = account.institution.orEmpty(),
            holder = account.holder.orEmpty(),
            expiry = account.expiry.orEmpty(),
            bankType = account.bankType ?: BankType.SAVINGS,
            ifsc = account.ifsc.orEmpty(),
            statementDay = account.statementDay?.toString().orEmpty(),
            dueDay = account.dueDay?.toString().orEmpty(),
            creditLimit = account.creditLimitMinor?.let(FinanceFormat::amountInput).orEmpty(),
            balanceMinor = balanceMinor,
        )

        fun message(result: AccountSaveResult): String? = when (result) {
            is AccountSaveResult.Saved -> null
            AccountSaveResult.BlankName -> "Give it a name."
            AccountSaveResult.InvalidLast4 -> "The number should end in 4 digits."
            AccountSaveResult.InvalidDay -> "Statement and due dates are days of the month, 1–31."
            AccountSaveResult.InvalidAmount -> "Amounts can’t be negative."
            AccountSaveResult.UnknownLinkedAccount -> "That bank account no longer exists."
            AccountSaveResult.NotFound -> "This account was deleted."
        }
    }
}

sealed interface AccountFormIntent {
    data class SelectKind(val kind: AccountKind) : AccountFormIntent
    data class Edit(val change: AccountFormState.() -> AccountFormState) : AccountFormIntent
    data object Save : AccountFormIntent
    data object AskDelete : AccountFormIntent
    data object CancelDelete : AccountFormIntent
    data object ConfirmDelete : AccountFormIntent
}

sealed interface AccountFormEffect {
    data object Close : AccountFormEffect
}
