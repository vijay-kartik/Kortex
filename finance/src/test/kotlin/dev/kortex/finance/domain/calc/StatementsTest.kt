package dev.kortex.finance.domain.calc

import dev.kortex.finance.Fixtures.TODAY
import dev.kortex.finance.Fixtures.checking
import dev.kortex.finance.Fixtures.kortex
import dev.kortex.finance.Fixtures.kortexStatement
import dev.kortex.finance.Fixtures.tx
import dev.kortex.finance.domain.FinanceIds
import dev.kortex.finance.domain.model.StatementStatus
import dev.kortex.finance.domain.model.TransactionType.CARD_PAYMENT
import dev.kortex.finance.domain.model.TransactionType.EXPENSE
import dev.kortex.finance.domain.model.TransactionType.OPENING
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatementsTest {
    private val accounts = listOf(checking, kortex).associateBy { it.uid }

    private fun payment(amount: Long) = tx(CARD_PAYMENT, amount, TODAY, "checking", to = "kortex", statementUid = kortexStatement.uid)

    @Test
    fun `status follows the payments against the statement`() {
        assertEquals(StatementStatus.DUE, Statements.status(kortexStatement, emptyList(), TODAY))
        assertEquals(StatementStatus.PARTLY_PAID, Statements.status(kortexStatement, listOf(payment(500_00)), TODAY))
        assertEquals(900_00, Statements.unpaidMinor(kortexStatement, listOf(payment(500_00))))
        assertEquals(StatementStatus.PAID, Statements.status(kortexStatement, listOf(payment(1_400_00)), TODAY))
        assertEquals(StatementStatus.OVERDUE, Statements.status(kortexStatement, emptyList(), LocalDate.of(2026, 10, 16)))
    }

    @Test
    fun `a payment for another statement doesn't count`() {
        val other = tx(CARD_PAYMENT, 1_400_00, TODAY, "checking", to = "kortex", statementUid = "stmt-aug")
        assertEquals(1_400_00, Statements.unpaidMinor(kortexStatement, listOf(other)))
    }

    @Test
    fun `spent since the statement starts the day after it`() {
        val txs = listOf(
            tx(EXPENSE, 300_00, LocalDate.of(2026, 9, 25), "kortex"),
            tx(EXPENSE, 42_50, LocalDate.of(2026, 9, 28), "kortex"),
            tx(EXPENSE, 99_00, LocalDate.of(2026, 9, 28), "checking"),
        )
        assertEquals(42_50, Statements.spentSinceMinor(kortex, kortexStatement, txs, accounts))
    }

    @Test
    fun `days clamp to short months`() {
        assertEquals(LocalDate.of(2027, 2, 28), Statements.dayIn(YearMonth.of(2027, 2), 31))
        assertEquals(LocalDate.of(2026, 10, 15), Statements.dueOnFor(LocalDate.of(2026, 9, 25), 15))
        assertEquals(LocalDate.of(2027, 2, 28), Statements.dueOnFor(LocalDate.of(2027, 1, 25), 31))
    }

    @Test
    fun `statement days to create are the ones passed since the last`() {
        assertEquals(
            listOf(LocalDate.of(2026, 9, 25)),
            Statements.statementDaysToCreate(kortex, LocalDate.of(2026, 8, 25), TODAY),
        )
        assertEquals(
            listOf(LocalDate.of(2026, 8, 25), LocalDate.of(2026, 9, 25)),
            Statements.statementDaysToCreate(kortex, LocalDate.of(2026, 7, 25), TODAY),
        )
        assertEquals(emptyList<LocalDate>(), Statements.statementDaysToCreate(kortex, LocalDate.of(2026, 8, 25), LocalDate.of(2026, 9, 24)))
        assertEquals(emptyList<LocalDate>(), Statements.statementDaysToCreate(kortex.copy(statementDay = null), null, TODAY))
    }

    @Test
    fun `the engine bills what was owed on the statement day`() {
        val txs = listOf(
            tx(OPENING, 1_400_00, LocalDate.of(2026, 9, 1), "kortex"),
            tx(EXPENSE, 42_50, LocalDate.of(2026, 9, 28), "kortex"),
        )
        val statement = Statements.autoStatement(kortex, LocalDate.of(2026, 9, 25), LocalDate.of(2026, 8, 25), txs, accounts, 0)!!
        assertEquals(1_400_00, statement.totalDueMinor)
        assertEquals(200_00, statement.minDueMinor) // 5% is ₹70, so the ₹200 floor applies
        assertEquals(LocalDate.of(2026, 10, 15), statement.dueOn)
        assertEquals(LocalDate.of(2026, 8, 26), statement.periodStart)
        assertEquals(FinanceIds.statement("kortex", LocalDate.of(2026, 9, 25)), statement.uid)
    }

    @Test
    fun `nothing owed means no statement`() {
        assertNull(Statements.autoStatement(kortex, LocalDate.of(2026, 9, 25), null, emptyList(), accounts, 0))
    }

    @Test
    fun `minimum due is 5 percent with a floor, never above the bill`() {
        assertEquals(5_000_00, Statements.defaultMinDueMinor(1_00_000_00))
        assertEquals(200_00, Statements.defaultMinDueMinor(1_400_00))
        assertEquals(100_00, Statements.defaultMinDueMinor(100_00))
    }
}
