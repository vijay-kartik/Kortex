package dev.kortex.finance.domain.usecase

import dev.kortex.finance.FakeFinanceRepository
import dev.kortex.finance.Fixtures
import dev.kortex.finance.Fixtures.TODAY
import dev.kortex.finance.domain.model.AccountKind
import dev.kortex.finance.domain.model.BankType
import dev.kortex.finance.domain.model.TransactionType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddAccountTest {
    private val repository = FakeFinanceRepository()
    private val addAccount = AddAccount(repository, Fixtures.clock)

    @Test
    fun `the opening balance is saved as the first entry`() = runTest {
        val result = addAccount(
            AccountDraft(AccountKind.BANK, "  Checking Account ", institution = "HDFC Bank", last4 = "4471", bankType = BankType.CURRENT, openingMinor = 12_450_00),
        ) as AccountSaveResult.Saved
        val account = repository.accounts.value.single()
        assertEquals(result.uid, account.uid)
        assertEquals("Checking Account", account.name)
        assertEquals(BankType.CURRENT, account.bankType)
        val opening = repository.transactions.value.single()
        assertEquals(TransactionType.OPENING, opening.type)
        assertEquals(12_450_00, opening.amountMinor)
        assertEquals(account.uid, opening.accountUid)
        assertEquals(TODAY, opening.occurredOn)
    }

    @Test
    fun `a credit card's outstanding today is its opening entry`() = runTest {
        addAccount(
            AccountDraft(AccountKind.CREDIT_CARD, "KORTEX", last4 = "8824", creditLimitMinor = 50_000_00, statementDay = 25, dueDay = 15, openingMinor = 1_400_00, bankType = BankType.SAVINGS),
        )
        val card = repository.accounts.value.single()
        assertEquals(25, card.statementDay)
        assertNull(card.bankType) // only banks have one
        assertEquals(1_400_00, repository.transactions.value.single().amountMinor)
    }

    @Test
    fun `nothing to open with, no entry`() = runTest {
        addAccount(AccountDraft(AccountKind.CASH, "Cash"))
        assertTrue(repository.transactions.value.isEmpty())
    }

    @Test
    fun `a linked debit card has no money of its own`() = runTest {
        val bank = addAccount(AccountDraft(AccountKind.BANK, "Checking", openingMinor = 100_00)) as AccountSaveResult.Saved
        addAccount(AccountDraft(AccountKind.DEBIT_CARD, "HDFC Debit", linkedAccountUid = bank.uid, openingMinor = 500_00))
        assertEquals(1, repository.transactions.value.size)
        assertEquals(bank.uid, repository.accounts.value.last().linkedAccountUid)
    }

    @Test
    fun `bad input is refused`() = runTest {
        assertEquals(AccountSaveResult.BlankName, addAccount(AccountDraft(AccountKind.BANK, "  ")))
        assertEquals(AccountSaveResult.InvalidLast4, addAccount(AccountDraft(AccountKind.BANK, "A", last4 = "44a1")))
        assertEquals(AccountSaveResult.InvalidDay, addAccount(AccountDraft(AccountKind.CREDIT_CARD, "A", statementDay = 32)))
        assertEquals(AccountSaveResult.InvalidAmount, addAccount(AccountDraft(AccountKind.BANK, "A", openingMinor = -1)))
        assertEquals(AccountSaveResult.UnknownLinkedAccount, addAccount(AccountDraft(AccountKind.DEBIT_CARD, "A", linkedAccountUid = "nope")))
        assertTrue(repository.accounts.value.isEmpty())
    }
}
