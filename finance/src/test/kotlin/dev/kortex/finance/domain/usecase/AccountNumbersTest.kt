package dev.kortex.finance.domain.usecase

import dev.kortex.finance.FakeFinanceRepository
import dev.kortex.finance.Fixtures
import dev.kortex.finance.domain.model.AccountKind
import dev.kortex.finance.domain.port.SealedSecret
import dev.kortex.finance.domain.port.SecretBox
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountNumbersTest {
    /** Reverses the digits and binds them to the account: enough to tell sealed from plain. */
    private class FakeBox(var hasKey: Boolean = true) : SecretBox {
        override suspend fun seal(accountUid: String, plain: String) =
            if (hasKey) SealedSecret("$accountUid:${plain.reversed()}", keyVersion = 1) else null

        override suspend fun open(accountUid: String, sealed: SealedSecret) =
            sealed.cipherText.takeIf { hasKey && it.startsWith("$accountUid:") }?.substringAfter(':')?.reversed()
    }

    private val repository = FakeFinanceRepository()
    private val box = FakeBox()
    private val add = AddAccount(repository, Fixtures.clock, box)
    private val reveal = RevealNumber(repository, box)

    @Test
    fun `a full number is kept sealed and the account shows only its last four`() = runTest {
        val saved = add(AccountDraft(AccountKind.CREDIT_CARD, "HDFC Regalia", fullNumber = "4111 1111 1111 8824")) as AccountSaveResult.Saved
        assertEquals(true, saved.numberKept)
        val account = repository.getAccount(saved.uid)!!
        assertEquals("8824", account.last4)
        assertTrue(account.hasSecret)
        assertEquals("${saved.uid}:4288111111111114", repository.getSecret(saved.uid)!!.cipherText)
        assertEquals("4111111111118824", reveal(saved.uid))
    }

    @Test
    fun `without a key the account is saved and the number isn't`() = runTest {
        box.hasKey = false
        val saved = add(AccountDraft(AccountKind.BANK, "HDFC Savings", fullNumber = "50100123454471")) as AccountSaveResult.Saved
        assertEquals(false, saved.numberKept)
        assertEquals("4471", repository.getAccount(saved.uid)!!.last4)
        assertNull(repository.getSecret(saved.uid))
    }

    @Test
    fun `a number that can't be one, or doesn't end in the last four, is refused`() = runTest {
        assertEquals(AccountSaveResult.InvalidNumber, add(AccountDraft(AccountKind.BANK, "A", fullNumber = "12345")))
        assertEquals(AccountSaveResult.InvalidNumber, add(AccountDraft(AccountKind.BANK, "A", fullNumber = "1234-5678-abcd")))
        assertEquals(AccountSaveResult.InvalidNumber, add(AccountDraft(AccountKind.BANK, "A", last4 = "1111", fullNumber = "4111111111118824")))
        assertTrue(repository.accounts.value.isEmpty())
    }

    @Test
    fun `editing replaces the number only when a new one is typed`() = runTest {
        val uid = (add(AccountDraft(AccountKind.CREDIT_CARD, "Card", fullNumber = "4111111111118824")) as AccountSaveResult.Saved).uid
        val update = UpdateAccount(repository, box)
        assertEquals(AccountSaveResult.Saved(uid), update(uid, AccountDraft(AccountKind.CREDIT_CARD, "Card renamed", last4 = "8824")))
        assertEquals("4111111111118824", reveal(uid))
        update(uid, AccountDraft(AccountKind.CREDIT_CARD, "Card renamed", fullNumber = "5500000000005512"))
        assertEquals("5512", repository.getAccount(uid)!!.last4)
        assertEquals("5500000000005512", reveal(uid))
    }

    @Test
    fun `a secret can't be read as another account's`() = runTest {
        val uid = (add(AccountDraft(AccountKind.CREDIT_CARD, "Card", fullNumber = "4111111111118824")) as AccountSaveResult.Saved).uid
        repository.saveSecret("other", repository.getSecret(uid)!!)
        assertNull(reveal("other"))
    }
}
