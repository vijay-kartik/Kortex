package dev.kortex.finance.domain.usecase

import dev.kortex.finance.domain.FinanceIds
import dev.kortex.finance.domain.model.Account
import dev.kortex.finance.domain.model.AccountKind
import dev.kortex.finance.domain.model.BankType
import dev.kortex.finance.domain.model.Transaction
import dev.kortex.finance.domain.model.TransactionType
import dev.kortex.finance.domain.port.Clock
import dev.kortex.finance.domain.port.SecretBox
import dev.kortex.finance.domain.repository.FinanceRepository

/** What Add account collects (Figma: Add account — bank / credit card, Paste SMS 09). */
data class AccountDraft(
    val kind: AccountKind,
    val name: String,
    val institution: String? = null,
    val last4: String? = null,
    val bankType: BankType? = null,
    val ifsc: String? = null,
    val linkedAccountUid: String? = null,
    val network: String? = null,
    val expiry: String? = null,
    val holder: String? = null,
    val creditLimitMinor: Long? = null,
    val statementDay: Int? = null,
    val dueDay: Int? = null,
    val colorToken: String? = null,
    /** "Opening balance", or a credit card's "Outstanding today". Saved as the first entry. */
    val openingMinor: Long = 0,
    /** The full card or account number, digits only; kept encrypted, never in the account itself. */
    val fullNumber: String? = null,
)

sealed interface AccountSaveResult {
    /** [numberKept] is null when no full number was given, false when it couldn't be encrypted (no key yet). */
    data class Saved(val uid: String, val numberKept: Boolean? = null) : AccountSaveResult
    data object BlankName : AccountSaveResult
    data object InvalidLast4 : AccountSaveResult
    data object InvalidDay : AccountSaveResult
    data object InvalidAmount : AccountSaveResult
    data object UnknownLinkedAccount : AccountSaveResult

    /** A full number that isn't 8 to 19 digits, or doesn't end in the last 4 given. */
    data object InvalidNumber : AccountSaveResult
    data object NotFound : AccountSaveResult
}

/**
 * Adds an account or card. Its starting balance isn't a field on it but an OPENING transaction
 * saved alongside, so balances only ever change through entries.
 */
class AddAccount(
    private val repository: FinanceRepository,
    private val clock: Clock,
    private val secrets: SecretBox = SecretBox.None,
) {
    suspend operator fun invoke(draft: AccountDraft): AccountSaveResult {
        val name = draft.name.trim().ifEmpty { return AccountSaveResult.BlankName }
        val number = AccountNumbers.clean(draft.fullNumber)
        if (number == AccountNumbers.INVALID) return AccountSaveResult.InvalidNumber
        val last4 = draft.last4?.trim()?.ifEmpty { null } ?: number?.takeLast(4)
        if (last4 != null && (last4.length != 4 || !last4.all { it.isDigit() })) return AccountSaveResult.InvalidLast4
        if (number != null && number.takeLast(4) != last4) return AccountSaveResult.InvalidNumber
        if (listOfNotNull(draft.statementDay, draft.dueDay).any { it !in 1..31 }) return AccountSaveResult.InvalidDay
        if (draft.openingMinor < 0 || (draft.creditLimitMinor ?: 0) < 0) return AccountSaveResult.InvalidAmount
        val linked = draft.linkedAccountUid?.let { uid ->
            repository.getAccount(uid)?.takeIf { it.kind == AccountKind.BANK } ?: return AccountSaveResult.UnknownLinkedAccount
        }

        val now = clock.nowMillis()
        val account = Account(
            uid = FinanceIds.random(),
            kind = draft.kind,
            name = name,
            institution = draft.institution.clean(),
            last4 = last4,
            bankType = draft.bankType.takeIf { draft.kind == AccountKind.BANK },
            ifsc = draft.ifsc.clean()?.uppercase(),
            linkedAccountUid = linked?.uid.takeIf { draft.kind == AccountKind.DEBIT_CARD },
            network = draft.network.clean(),
            expiry = draft.expiry.clean(),
            holder = draft.holder.clean(),
            creditLimitMinor = draft.creditLimitMinor.takeIf { draft.kind == AccountKind.CREDIT_CARD },
            statementDay = draft.statementDay.takeIf { draft.kind == AccountKind.CREDIT_CARD },
            dueDay = draft.dueDay.takeIf { draft.kind == AccountKind.CREDIT_CARD },
            colorToken = draft.colorToken,
            createdAtMillis = now,
        )
        // A linked debit card has no money of its own; its bank account already has an opening entry.
        val opening = draft.openingMinor.takeIf { it > 0 && account.linkedAccountUid == null }?.let { amount ->
            Transaction(
                uid = FinanceIds.random(),
                type = TransactionType.OPENING,
                amountMinor = amount,
                occurredAtMillis = now,
                occurredOn = clock.dayOf(now),
                accountUid = account.uid,
            )
        }
        repository.addAccount(account, opening)
        return AccountSaveResult.Saved(account.uid, number?.let { AccountNumbers.keep(repository, secrets, account.uid, it) })
    }
}

internal object AccountNumbers {
    const val INVALID = "invalid"

    /** Digits only; null when not given, [INVALID] when it can't be a card or account number. */
    fun clean(raw: String?): String? {
        val digits = raw?.filter { !it.isWhitespace() && it != '-' }?.ifEmpty { null } ?: return null
        return if (digits.all(Char::isDigit) && digits.length in 8..19) digits else INVALID
    }

    /** Seals and saves [number] for [accountUid]; false when there was no key to seal it with. */
    suspend fun keep(repository: FinanceRepository, secrets: SecretBox, accountUid: String, number: String): Boolean {
        val sealed = secrets.seal(accountUid, number) ?: return false
        repository.saveSecret(accountUid, sealed)
        return true
    }
}

/** Shows a full number (Figma: Credit Cards › Card details), after the screen lock has been passed. */
class RevealNumber(private val repository: FinanceRepository, private val secrets: SecretBox) {
    suspend operator fun invoke(accountUid: String): String? =
        repository.getSecret(accountUid)?.let { secrets.open(accountUid, it) }
}

internal fun String?.clean(): String? = this?.trim()?.ifEmpty { null }
