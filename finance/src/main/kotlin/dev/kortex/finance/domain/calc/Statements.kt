package dev.kortex.finance.domain.calc

import dev.kortex.finance.domain.FinanceIds
import dev.kortex.finance.domain.model.Account
import dev.kortex.finance.domain.model.CardStatement
import dev.kortex.finance.domain.model.StatementSource
import dev.kortex.finance.domain.model.StatementStatus
import dev.kortex.finance.domain.model.Transaction
import dev.kortex.finance.domain.model.TransactionType
import java.time.LocalDate
import java.time.YearMonth

/** Credit card bills: what's paid, what's left, and the statements the engine creates. */
object Statements {

    /** Until an SMS or an edit gives the bank's figure: 5% of the bill, at least ₹200, never more than the bill. */
    const val MIN_DUE_FLOOR_MINOR = 200_00L
    const val MIN_DUE_PERCENT = 5

    fun paidMinor(statement: CardStatement, transactions: List<Transaction>): Long =
        transactions
            .filter { it.type == TransactionType.CARD_PAYMENT && it.statementUid == statement.uid }
            .sumOf { it.amountMinor }

    fun unpaidMinor(statement: CardStatement, transactions: List<Transaction>): Long =
        (statement.totalDueMinor - paidMinor(statement, transactions)).coerceAtLeast(0)

    fun status(statement: CardStatement, transactions: List<Transaction>, today: LocalDate): StatementStatus {
        val paid = paidMinor(statement, transactions)
        return when {
            paid >= statement.totalDueMinor -> StatementStatus.PAID
            today.isAfter(statement.dueOn) -> StatementStatus.OVERDUE
            paid > 0 -> StatementStatus.PARTLY_PAID
            else -> StatementStatus.DUE
        }
    }

    /** Spent on [card] after its statement: Credit Cards' "Spent since 25 Sep statement". */
    fun spentSinceMinor(
        card: Account,
        statement: CardStatement,
        transactions: List<Transaction>,
        accounts: Map<String, Account>,
    ): Long = transactions
        .filter {
            it.type == TransactionType.EXPENSE &&
                Balances.ledgerOf(it.accountUid, accounts) == card.uid &&
                it.occurredOn.isAfter(statement.statementOn)
        }
        .sumOf { it.amountMinor }

    fun latest(cardUid: String, statements: List<CardStatement>): CardStatement? =
        statements.filter { it.cardUid == cardUid }.maxByOrNull { it.statementOn }

    /** [day] of [month], or the month's last day when it's shorter. */
    fun dayIn(month: YearMonth, day: Int): LocalDate = month.atDay(day.coerceIn(1, month.lengthOfMonth()))

    /** The bill from a statement on [statementOn] is due on [dueDay] of the next month. */
    fun dueOnFor(statementOn: LocalDate, dueDay: Int): LocalDate = dayIn(YearMonth.from(statementOn).plusMonths(1), dueDay)

    /**
     * Statement days of [card] after [lastStatementOn] (or, for a card with none yet, in the last
     * month only) up to and including [today]. Empty without a statement day.
     */
    fun statementDaysToCreate(card: Account, lastStatementOn: LocalDate?, today: LocalDate): List<LocalDate> {
        val day = card.statementDay ?: return emptyList()
        var month = lastStatementOn?.let { YearMonth.from(it).plusMonths(1) } ?: YearMonth.from(today).minusMonths(1)
        return buildList {
            while (true) {
                val on = dayIn(month, day)
                if (on.isAfter(today)) break
                if (lastStatementOn == null || on.isAfter(lastStatementOn)) add(on)
                month = month.plusMonths(1)
            }
        }
    }

    /**
     * The statement the engine writes for [card] on [statementOn], billing what was owed that day.
     * Null when nothing is owed or the card has no due day.
     */
    fun autoStatement(
        card: Account,
        statementOn: LocalDate,
        previousStatementOn: LocalDate?,
        transactions: List<Transaction>,
        accounts: Map<String, Account>,
        nowMillis: Long,
    ): CardStatement? {
        val dueDay = card.dueDay ?: return null
        val total = Balances.balanceMinor(card, transactions, accounts, upTo = statementOn)
        if (total <= 0) return null
        return CardStatement(
            uid = FinanceIds.statement(card.uid, statementOn),
            cardUid = card.uid,
            periodStart = previousStatementOn?.plusDays(1) ?: YearMonth.from(statementOn).minusMonths(1).let {
                dayIn(it, card.statementDay ?: statementOn.dayOfMonth).plusDays(1)
            },
            statementOn = statementOn,
            dueOn = dueOnFor(statementOn, dueDay),
            totalDueMinor = total,
            minDueMinor = defaultMinDueMinor(total),
            source = StatementSource.AUTO,
            createdAtMillis = nowMillis,
        )
    }

    fun defaultMinDueMinor(totalDueMinor: Long): Long =
        maxOf(roundDiv(totalDueMinor * MIN_DUE_PERCENT, 100), MIN_DUE_FLOOR_MINOR).coerceAtMost(totalDueMinor)
}
