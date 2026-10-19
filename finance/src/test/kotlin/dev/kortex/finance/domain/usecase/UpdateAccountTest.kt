package dev.kortex.finance.domain.usecase

import dev.kortex.finance.FakeFinanceRepository
import dev.kortex.finance.Fixtures
import dev.kortex.finance.domain.model.AccountKind
import dev.kortex.finance.domain.model.BankType
import dev.kortex.finance.domain.model.TransactionType
import dev.kortex.finance.domain.port.SealedSecret
import dev.kortex.finance.domain.port.SecretBox
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateAccountTest {
    /** Reverses the digits and binds them to the account, as in [AccountNumbersTest]. */
    private class FakeBox : SecretBox {
        override suspend fun seal(accountUid: String, plain: String) = SealedSecret("$accountUid:${plain.reversed()}", keyVersion = 1)

        override suspend fun open(accountUid: String, sealed: SealedSecret) =
            sealed.cipherText.takeIf { it.startsWith("$accountUid:") }?.substringAfter(':')?.reversed()
    }

    private val repository = FakeFinanceRepository()
    private val box = FakeBox()
    private val add = AddAccount(repository, Fixtures.clock, box)
    private val update = UpdateAccount(repository, box)
    private val reveal = RevealNumber(repository, box)

    private suspend fun addCard() = (
        add(
            AccountDraft(
                AccountKind.CREDIT_CARD,
                "HDFC Regalia",
                bankType = BankType.SAVINGS,
                creditLimitMinor = 50_000_00,
                statementDay = 25,
                dueDay = 15,
                colorToken = "synapse",
                openingMinor = 1_400_00,
                fullNumber = "4111111111118824",
            ),
        ) as AccountSaveResult.Saved
        ).uid

    private suspend fun addBank() = (
        add(AccountDraft(AccountKind.BANK, "Checking", last4 = "4471", bankType = BankType.CURRENT, openingMinor = 100_00)) as AccountSaveResult.Saved
        ).uid

    /** What Edit account sends for [uid] when nothing was changed. */
    private suspend fun unchanged(uid: String) = repository.getAccount(uid)!!.let {
        AccountDraft(
            kind = it.kind,
            name = it.name,
            last4 = it.last4,
            bankType = it.bankType,
            creditLimitMinor = it.creditLimitMinor,
            statementDay = it.statementDay,
            dueDay = it.dueDay,
        )
    }

    @Test
    fun `an unknown account isn't found and nothing changes`() = runTest {
        addBank()
        val before = repository.accounts.value
        assertEquals(AccountSaveResult.NotFound, update("missing", AccountDraft(AccountKind.BANK, "Checking")))
        assertEquals(before, repository.accounts.value)
    }

    @Test
    fun `the name is required and saved trimmed`() = runTest {
        val uid = addBank()
        assertEquals(AccountSaveResult.BlankName, update(uid, unchanged(uid).copy(name = "")))
        assertEquals(AccountSaveResult.BlankName, update(uid, unchanged(uid).copy(name = "   ")))
        assertEquals("Checking", repository.getAccount(uid)!!.name)
        assertEquals(AccountSaveResult.Saved(uid), update(uid, unchanged(uid).copy(name = "  Salary Account ")))
        assertEquals("Salary Account", repository.getAccount(uid)!!.name)
    }

    @Test
    fun `fields that can't be read are refused and the account stays as it was`() = runTest {
        val uid = addCard()
        val before = repository.getAccount(uid)
        val draft = unchanged(uid)
        assertEquals(AccountSaveResult.InvalidLast4, update(uid, draft.copy(last4 = "44a1")))
        assertEquals(AccountSaveResult.InvalidLast4, update(uid, draft.copy(last4 = "123")))
        assertEquals(AccountSaveResult.InvalidDay, update(uid, draft.copy(statementDay = 0)))
        assertEquals(AccountSaveResult.InvalidDay, update(uid, draft.copy(dueDay = 32)))
        assertEquals(AccountSaveResult.InvalidAmount, update(uid, draft.copy(creditLimitMinor = -1)))
        assertEquals(AccountSaveResult.InvalidNumber, update(uid, draft.copy(last4 = null, fullNumber = "12345")))
        assertEquals(before, repository.getAccount(uid))
        assertEquals("4111111111118824", reveal(uid))
    }

    @Test
    fun `a bank account stays a bank account and keeps only bank fields`() = runTest {
        val uid = addBank()
        update(uid, unchanged(uid).copy(kind = AccountKind.CREDIT_CARD, creditLimitMinor = 10_000_00, statementDay = 5, dueDay = 20))
        val bank = repository.getAccount(uid)!!
        assertEquals(AccountKind.BANK, bank.kind)
        assertEquals(BankType.CURRENT, bank.bankType)
        assertNull(bank.creditLimitMinor)
        assertNull(bank.statementDay)
        assertNull(bank.dueDay)
    }

    @Test
    fun `a credit card keeps its card fields and drops a bank type`() = runTest {
        val uid = addCard()
        update(uid, unchanged(uid).copy(bankType = BankType.SAVINGS, creditLimitMinor = 75_000_00, statementDay = 1, dueDay = 31))
        val card = repository.getAccount(uid)!!
        assertNull(card.bankType)
        assertEquals(75_000_00L, card.creditLimitMinor)
        assertEquals(1, card.statementDay)
        assertEquals(31, card.dueDay)
    }

    @Test
    fun `no colour keeps the one it had, a new one replaces it`() = runTest {
        val uid = addCard()
        update(uid, unchanged(uid).copy(colorToken = null))
        assertEquals("synapse", repository.getAccount(uid)!!.colorToken)
        update(uid, unchanged(uid).copy(colorToken = "ember"))
        assertEquals("ember", repository.getAccount(uid)!!.colorToken)
    }

    @Test
    fun `the IFSC is uppercased and blank fields are cleared`() = runTest {
        val uid = addBank()
        update(uid, unchanged(uid).copy(institution = "HDFC Bank", ifsc = " hdfc0001234 ", network = "RuPay", expiry = "08/29", holder = "A K"))
        update(uid, unchanged(uid).copy(institution = "  ", ifsc = " hdfc0001234 ", network = "", expiry = " ", holder = null))
        val bank = repository.getAccount(uid)!!
        assertEquals("HDFC0001234", bank.ifsc)
        assertNull(bank.institution)
        assertNull(bank.network)
        assertNull(bank.expiry)
        assertNull(bank.holder)
    }

    @Test
    fun `what Edit account doesn't show is left alone, balance included`() = runTest {
        val bank = addBank()
        val debit = (add(AccountDraft(AccountKind.DEBIT_CARD, "HDFC Debit", linkedAccountUid = bank)) as AccountSaveResult.Saved).uid
        repository.updateAccount(repository.getAccount(debit)!!.copy(archived = true))
        val before = repository.getAccount(debit)!!
        val entries = repository.transactions.value
        update(debit, unchanged(debit).copy(name = "Debit", linkedAccountUid = null, openingMinor = 9_999_00))
        update(bank, unchanged(bank).copy(openingMinor = 9_999_00))
        val after = repository.getAccount(debit)!!
        assertEquals("Debit", after.name)
        assertEquals(bank, after.linkedAccountUid)
        assertTrue(after.archived)
        assertEquals(before.createdAtMillis, after.createdAtMillis)
        assertEquals(entries, repository.transactions.value)
        assertEquals(100_00L, repository.transactions.value.single { it.type == TransactionType.OPENING }.amountMinor)
    }

    @Test
    fun `a full number that doesn't end in the last four is refused, as on Add account`() = runTest {
        val uid = addCard()
        assertEquals(AccountSaveResult.InvalidNumber, update(uid, unchanged(uid).copy(last4 = "8824", fullNumber = "5500000000005512")))
        assertEquals("8824", repository.getAccount(uid)!!.last4)
        assertEquals("4111111111118824", reveal(uid))
        assertEquals(AccountSaveResult.Saved(uid, numberKept = true), update(uid, unchanged(uid).copy(last4 = "5512", fullNumber = "5500000000005512")))
        assertEquals("5512", repository.getAccount(uid)!!.last4)
        assertEquals("5500000000005512", reveal(uid))
    }

    @Test
    fun `new last four alone can't replace a kept full number`() = runTest {
        val uid = addCard()
        assertEquals(AccountSaveResult.InvalidNumber, update(uid, unchanged(uid).copy(last4 = "5512")))
        assertEquals(AccountSaveResult.InvalidNumber, update(uid, unchanged(uid).copy(last4 = null)))
        val card = repository.getAccount(uid)!!
        assertEquals("8824", card.last4)
        assertTrue(card.hasSecret)
        assertTrue(reveal(uid)!!.endsWith(card.last4!!))
    }

    @Test
    fun `without a kept full number the last four can change on their own`() = runTest {
        val uid = addBank()
        assertEquals(AccountSaveResult.Saved(uid), update(uid, unchanged(uid).copy(last4 = "9012")))
        assertEquals("9012", repository.getAccount(uid)!!.last4)
        assertNull(reveal(uid))
    }
}
