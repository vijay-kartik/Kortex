package dev.kortex.finance.domain.calc

import dev.kortex.finance.domain.model.Account
import dev.kortex.finance.domain.model.AccountKind
import dev.kortex.finance.domain.model.Transaction
import dev.kortex.finance.domain.model.TransactionType
import java.time.LocalDate

/** A credit card's numbers on the Credit Cards screen. */
data class CardPosition(
    /** Everything owed: the open statement plus what's been spent since. */
    val outstandingMinor: Long,
    /** Null when the card has no limit set. */
    val availableMinor: Long?,
    /** Outstanding ÷ limit, 0–1 (above 1 when over the limit); null without a limit. */
    val utilisation: Double?,
)

/**
 * Balances are never stored: each is the sum of its account's transactions, from the OPENING entry
 * on (docs/FINANCE_PLAN.md › Derived values). For a credit card the "balance" is what's owed.
 */
object Balances {

    /**
     * The account a transaction's money really moves on. A debit card's spends land on its linked
     * bank account; an unlinked debit card is its own ledger.
     */
    fun ledgerOf(accountUid: String, accounts: Map<String, Account>): String {
        val account = accounts[accountUid] ?: return accountUid
        return if (account.kind == AccountKind.DEBIT_CARD) account.linkedAccountUid ?: accountUid else accountUid
    }

    /**
     * What a transaction does to [target]'s balance: positive raises a bank balance or a card's
     * outstanding. Card credits other than bill payments (refunds) aren't handled in v1, so income
     * and transfers into a credit card count for nothing.
     */
    fun effect(transaction: Transaction, target: Account, accounts: Map<String, Account>): Long {
        val amount = transaction.amountMinor
        val from = ledgerOf(transaction.accountUid, accounts)
        val to = transaction.toAccountUid?.let { ledgerOf(it, accounts) }
        val isFrom = from == target.uid
        val isTo = to == target.uid
        return if (target.kind == AccountKind.CREDIT_CARD) {
            when (transaction.type) {
                TransactionType.OPENING, TransactionType.EXPENSE -> if (isFrom) amount else 0
                TransactionType.CARD_PAYMENT -> if (isTo) -amount else 0
                TransactionType.INCOME, TransactionType.TRANSFER -> 0
            }
        } else {
            when (transaction.type) {
                TransactionType.OPENING, TransactionType.INCOME -> if (isFrom) amount else 0
                TransactionType.EXPENSE, TransactionType.CARD_PAYMENT -> if (isFrom) -amount else 0
                TransactionType.TRANSFER -> (if (isTo) amount else 0) - (if (isFrom) amount else 0)
            }
        }
    }

    /**
     * [account]'s balance (a credit card's outstanding), counting transactions up to and including
     * [upTo] when given. A linked debit card reports its bank account's balance.
     */
    fun balanceMinor(
        account: Account,
        transactions: List<Transaction>,
        accounts: Map<String, Account>,
        upTo: LocalDate? = null,
    ): Long {
        val ledger = accounts[ledgerOf(account.uid, accounts)] ?: account
        return transactions.sumOf { tx ->
            if (upTo != null && tx.occurredOn.isAfter(upTo)) 0 else effect(tx, ledger, accounts)
        }
    }

    /** Every account's balance in one pass, keyed by uid. */
    fun balances(accounts: List<Account>, transactions: List<Transaction>): Map<String, Long> {
        val byUid = accounts.associateBy { it.uid }
        return accounts.associate { it.uid to balanceMinor(it, transactions, byUid) }
    }

    /** Bank, cash and wallets that aren't archived. Cards never count. */
    fun totalBalanceMinor(accounts: List<Account>, transactions: List<Transaction>): Long {
        val byUid = accounts.associateBy { it.uid }
        return accounts
            .filter { it.kind.countsInTotal && !it.archived }
            .sumOf { balanceMinor(it, transactions, byUid) }
    }

    fun cardPosition(card: Account, transactions: List<Transaction>, accounts: Map<String, Account>): CardPosition {
        val outstanding = balanceMinor(card, transactions, accounts)
        val limit = card.creditLimitMinor?.takeIf { it > 0 }
        return CardPosition(
            outstandingMinor = outstanding,
            availableMinor = limit?.let { it - outstanding },
            utilisation = limit?.let { outstanding.toDouble() / it },
        )
    }
}
