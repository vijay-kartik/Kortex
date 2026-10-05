package dev.kortex.finance.domain.calc

import dev.kortex.finance.Fixtures
import dev.kortex.finance.Fixtures.TODAY
import dev.kortex.finance.Fixtures.cash
import dev.kortex.finance.Fixtures.checking
import dev.kortex.finance.Fixtures.kortex
import dev.kortex.finance.Fixtures.savings
import dev.kortex.finance.Fixtures.tx
import dev.kortex.finance.domain.model.AccountKind
import dev.kortex.finance.domain.model.TransactionType.CARD_PAYMENT
import dev.kortex.finance.domain.model.TransactionType.EXPENSE
import dev.kortex.finance.domain.model.TransactionType.INCOME
import dev.kortex.finance.domain.model.TransactionType.OPENING
import dev.kortex.finance.domain.model.TransactionType.TRANSFER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BalancesTest {
    private val accounts = listOf(checking, savings, cash, kortex)
    private val byUid = accounts.associateBy { it.uid }
    private val start = TODAY.withDayOfMonth(1)

    @Test
    fun `a bank balance is its opening entry plus everything after it`() {
        val txs = listOf(
            tx(OPENING, 10_000_00, start, "checking"),
            tx(INCOME, 5_420_00, start.plusDays(1), "checking"),
            tx(EXPENSE, 2_000_00, start.plusDays(2), "checking"),
            tx(CARD_PAYMENT, 1_400_00, start.plusDays(3), "checking", to = "kortex"),
        )
        assertEquals(12_020_00, Balances.balanceMinor(checking, txs, byUid))
    }

    @Test
    fun `a card owes its opening outstanding and spends, less what was paid to it`() {
        val txs = listOf(
            tx(OPENING, 1_400_00, start, "kortex"),
            tx(EXPENSE, 42_50, TODAY.minusDays(1), "kortex"),
        )
        val position = Balances.cardPosition(kortex, txs, byUid)
        assertEquals(1_442_50, position.outstandingMinor)
        assertEquals(48_557_50L, position.availableMinor) // Paste SMS 02: "Avl Lmt Rs.48,557.50"
        assertEquals(0.02885, position.utilisation!!, 0.00001)

        val paid = txs + tx(CARD_PAYMENT, 1_400_00, TODAY, "checking", to = "kortex")
        assertEquals(42_50, Balances.cardPosition(kortex, paid, byUid).outstandingMinor)
    }

    @Test
    fun `a card without a limit has no available amount`() {
        val noLimit = kortex.copy(creditLimitMinor = null)
        val position = Balances.cardPosition(noLimit, emptyList(), byUid + ("kortex" to noLimit))
        assertNull(position.availableMinor)
        assertNull(position.utilisation)
    }

    @Test
    fun `refunds to a credit card are ignored in v1`() {
        val txs = listOf(tx(OPENING, 500_00, start, "kortex"), tx(INCOME, 100_00, TODAY, "kortex"))
        assertEquals(500_00, Balances.balanceMinor(kortex, txs, byUid))
    }

    @Test
    fun `a transfer moves money between own accounts`() {
        val txs = listOf(
            tx(OPENING, 1_000_00, start, "checking"),
            tx(TRANSFER, 300_00, TODAY, "checking", to = "savings"),
        )
        assertEquals(700_00, Balances.balanceMinor(checking, txs, byUid))
        assertEquals(300_00, Balances.balanceMinor(savings, txs, byUid))
    }

    @Test
    fun `a linked debit card spends from its bank account`() {
        val debit = Fixtures.account("debit", AccountKind.DEBIT_CARD, "HDFC Debit").copy(linkedAccountUid = "checking")
        val all = byUid + ("debit" to debit)
        val txs = listOf(tx(OPENING, 1_000_00, start, "checking"), tx(EXPENSE, 250_00, TODAY, "debit"))
        assertEquals(750_00, Balances.balanceMinor(checking, txs, all))
        assertEquals(750_00, Balances.balanceMinor(debit, txs, all))
    }

    @Test
    fun `total balance is bank, cash and wallets only`() {
        val txs = listOf(
            tx(OPENING, 12_450_00, start, "checking"),
            tx(OPENING, 10_200_12, start, "savings"),
            tx(OPENING, 2_200_00, start, "cash"),
            tx(OPENING, 1_400_00, start, "kortex"),
        )
        assertEquals(24_850_12, Balances.totalBalanceMinor(accounts, txs)) // Dashboard: ₹24,850.12

        val archived = accounts.map { if (it.uid == "cash") it.copy(archived = true) else it }
        assertEquals(22_650_12, Balances.totalBalanceMinor(archived, txs))
    }

    @Test
    fun `balance up to a day leaves later entries out`() {
        val txs = listOf(tx(OPENING, 100_00, start, "checking"), tx(INCOME, 50_00, TODAY, "checking"))
        assertEquals(100_00, Balances.balanceMinor(checking, txs, byUid, upTo = TODAY.minusDays(1)))
        assertEquals(150_00, Balances.balanceMinor(checking, txs, byUid, upTo = TODAY))
    }

    @Test
    fun `entries for a deleted account count nowhere`() {
        val txs = listOf(tx(OPENING, 100_00, start, "gone"), tx(OPENING, 50_00, start, "checking"))
        assertEquals(50_00, Balances.totalBalanceMinor(accounts, txs))
    }
}
