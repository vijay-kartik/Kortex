package dev.kortex.finance.domain.usecase

import dev.kortex.finance.domain.model.AccountKind
import dev.kortex.finance.domain.port.SecretBox
import dev.kortex.finance.domain.repository.FinanceRepository

/**
 * Saves Edit account (Figma: Edit account — bank / card). The kind can't change, and there is no
 * balance to change: [AccountDraft.openingMinor] is ignored, as balances only move with entries.
 */
class UpdateAccount(private val repository: FinanceRepository, private val secrets: SecretBox = SecretBox.None) {
    suspend operator fun invoke(uid: String, draft: AccountDraft): AccountSaveResult {
        val account = repository.getAccount(uid) ?: return AccountSaveResult.NotFound
        val name = draft.name.trim().ifEmpty { return AccountSaveResult.BlankName }
        val number = AccountNumbers.clean(draft.fullNumber)
        if (number == AccountNumbers.INVALID) return AccountSaveResult.InvalidNumber
        val last4 = number?.takeLast(4) ?: draft.last4?.trim()?.ifEmpty { null }
        if (last4 != null && (last4.length != 4 || !last4.all { it.isDigit() })) return AccountSaveResult.InvalidLast4
        if (listOfNotNull(draft.statementDay, draft.dueDay).any { it !in 1..31 }) return AccountSaveResult.InvalidDay
        if ((draft.creditLimitMinor ?: 0) < 0) return AccountSaveResult.InvalidAmount
        val kind = account.kind
        repository.updateAccount(
            account.copy(
                name = name,
                institution = draft.institution.clean(),
                last4 = last4,
                bankType = draft.bankType.takeIf { kind == AccountKind.BANK },
                ifsc = draft.ifsc.clean()?.uppercase(),
                network = draft.network.clean(),
                expiry = draft.expiry.clean(),
                holder = draft.holder.clean(),
                creditLimitMinor = draft.creditLimitMinor.takeIf { kind == AccountKind.CREDIT_CARD },
                statementDay = draft.statementDay.takeIf { kind == AccountKind.CREDIT_CARD },
                dueDay = draft.dueDay.takeIf { kind == AccountKind.CREDIT_CARD },
                colorToken = draft.colorToken ?: account.colorToken,
            ),
        )
        // A new full number replaces the one kept; leaving the field empty keeps it.
        return AccountSaveResult.Saved(uid, number?.let { AccountNumbers.keep(repository, secrets, uid, it) })
    }
}

/** Figma: Delete account. Its entries stay in history; the account itself goes. */
class DeleteAccount(private val repository: FinanceRepository) {
    suspend operator fun invoke(uid: String) = repository.deleteAccount(uid)
}

/** The Undo on the "Saved ₹42.50 at Whole Foods Market" snackbar. */
class DeleteTransaction(private val repository: FinanceRepository) {
    suspend operator fun invoke(uid: String) = repository.deleteTransaction(uid)
}
