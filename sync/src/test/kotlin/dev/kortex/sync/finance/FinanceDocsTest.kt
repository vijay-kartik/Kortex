package dev.kortex.sync.finance

import dev.kortex.finance.data.local.AccountEntity
import dev.kortex.finance.data.local.BudgetEntity
import dev.kortex.finance.data.local.CardStatementEntity
import dev.kortex.finance.data.local.CategoryEntity
import dev.kortex.finance.data.local.MerchantEntity
import dev.kortex.finance.data.local.RecurringEntity
import dev.kortex.finance.data.local.SecretEntity
import dev.kortex.finance.data.local.TransactionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FinanceDocsTest {
    private val serverTime = "SERVER_TIME"

    private val card = AccountEntity(
        uid = "kortex", kind = "CREDIT_CARD", name = "KORTEX", institution = "HDFC Bank", last4 = "8824", hasSecret = false,
        bankType = null, ifsc = null, linkedAccountUid = null, network = "Visa", expiry = "08/29", holder = "Kartik",
        creditLimitMinor = 50_000_00, statementDay = 25, dueDay = 15, colorToken = null, archived = false,
        createdAtMillis = 1_000, updatedAtMillis = 2_000, dirty = 3,
    )

    private val expense = TransactionEntity(
        uid = "rec_abc", type = "EXPENSE", amountMinor = 649_00, currency = "INR", occurredAtMillis = 5_000, occurredOn = "2026-09-29",
        accountUid = "kortex", toAccountUid = null, categoryUid = "utilities", merchant = "Netflix", payeeKey = "netflix", note = null,
        source = "RECURRING", sourceRef = null, recurringUid = "netflix", dueOn = "2026-10-03", statementUid = null,
        receiptItemCount = null, receiptItems = null, receiptTaxMinor = null, receiptPhotoPath = null, receiptPhotoMime = null,
        createdAtMillis = 5_000, updatedAtMillis = 6_000, dirty = 1,
    )

    @Test
    fun `records come back as they went up, clean`() {
        assertEquals(card.copy(dirty = 0), remoteAccount(card.uid, accountDoc(card, serverTime))!!.row)
        assertEquals(expense.copy(dirty = 0), remoteTransaction(expense.uid, transactionDoc(expense, serverTime))!!.row)

        val category = CategoryEntity("c1", "Groceries", "EXPENSE", "Lilac", builtIn = false, sortOrder = 4, createdAtMillis = 1, updatedAtMillis = 2, dirty = 1)
        assertEquals(category.copy(dirty = 0), remoteCategory("c1", categoryDoc(category, serverTime))!!.row)

        val recurring = RecurringEntity(
            "netflix", "Netflix", "SUBSCRIPTION", 649_00, "INR", "MONTHLY", 1, 3, "2026-10-03", "kortex", "utilities",
            remindDaysBefore = 2, autoMarkPaid = false, paused = false, createdAtMillis = 1, updatedAtMillis = 2, dirty = 1,
        )
        assertEquals(recurring.copy(dirty = 0), remoteRecurring("netflix", recurringDoc(recurring, serverTime))!!.row)

        val statement = CardStatementEntity("stmt_1", "kortex", "2026-08-26", "2026-09-25", "2026-10-15", 1_400_00, 200_00, "AUTO", 1, 2, dirty = 1)
        assertEquals(statement.copy(dirty = 0), remoteStatement("stmt_1", statementDoc(statement, serverTime))!!.row)

        val merchant = MerchantEntity("mer_1", "netflix", "Netflix", "utilities", updatedAtMillis = 2, dirty = 1)
        assertEquals(merchant.copy(dirty = 0), remoteMerchant("mer_1", merchantDoc(merchant, serverTime))!!.row)

        val secret = SecretEntity("kortex", "c2VhbGVk", keyVersion = 1, updatedAtMillis = 2, dirty = 1)
        val doc = secretDoc(secret, serverTime)
        assertEquals(setOf("cipherText", "keyVersion", "updatedAt", "serverUpdatedAt", "deleted"), doc.keys)
        assertEquals(secret.copy(dirty = 0), remoteSecret("kortex", doc)!!.row)
    }

    @Test
    fun `documents carry the server time, updatedAt and deleted false, never dirty`() {
        val doc = transactionDoc(expense, serverTime)
        assertEquals(serverTime, doc["serverUpdatedAt"])
        assertEquals(6_000L, doc["updatedAt"])
        assertEquals(5_000L, doc["occurredAt"])
        assertEquals(false, doc["deleted"])
        assertTrue("dirty" !in doc)
        assertTrue(doc.containsKey("receipt"))
        assertNull(doc["receipt"])
    }

    @Test
    fun `a delete needs only its time`() {
        val row = remoteTransaction("gone", finDeletedDoc(9_000, serverTime))!!
        assertTrue(row.deleted)
        assertNull(row.row)
        assertEquals(9_000L, row.updatedAtMillis)
    }

    @Test
    fun `numbers from JavaScript arrive as doubles`() {
        val doc = accountDoc(card, serverTime).toMutableMap().apply {
            put("creditLimitMinor", 5_000_000.0)
            put("statementDay", 25.0)
            put("updatedAt", 2_000.0)
        }
        val row = remoteAccount(card.uid, doc)!!.row!!
        assertEquals(50_000_00L, row.creditLimitMinor)
        assertEquals(25, row.statementDay)
    }

    @Test
    fun `documents that can't be applied are skipped`() {
        assertNull(remoteAccount("a", accountDoc(card, serverTime) - "updatedAt"))
        assertNull(remoteAccount("a", accountDoc(card, serverTime) + ("kind" to "SPACESHIP")))
        assertNull(remoteTransaction("t", transactionDoc(expense, serverTime) + ("amountMinor" to 0L)))
        assertNull(remoteTransaction("t", transactionDoc(expense, serverTime) + ("occurredOn" to "29/09/2026")))
        assertNull(remoteTransaction("t", transactionDoc(expense, serverTime) - "accountUid"))
    }

    @Test
    fun `budgets on built-in and your categories go up and come back clean`() {
        val food = BudgetEntity("food", 8_000_00, createdAtMillis = 1_000, updatedAtMillis = 2_000, dirty = 2)
        val doc = budgetDoc(food, serverTime)
        assertEquals(setOf("amountMinor", "createdAt", "updatedAt", "serverUpdatedAt", "deleted"), doc.keys)
        assertEquals(food.copy(dirty = 0), remoteBudget("food", doc)!!.row)

        val changed = BudgetEntity("c1", 2_500_00, createdAtMillis = 1_000, updatedAtMillis = 3_000, dirty = 1)
        assertEquals(changed.copy(dirty = 0), remoteBudget("c1", budgetDoc(changed, serverTime))!!.row)
    }

    @Test
    fun `a cleared budget arrives as a delete, and one of nothing is skipped`() {
        val cleared = remoteBudget("food", finDeletedDoc(9_000, serverTime))!!
        assertTrue(cleared.deleted)
        assertNull(cleared.row)
        assertEquals(9_000L, cleared.updatedAtMillis)

        val budget = BudgetEntity("food", 8_000_00, createdAtMillis = 1, updatedAtMillis = 2)
        assertNull(remoteBudget("food", budgetDoc(budget, serverTime) + ("amountMinor" to 0L)))
        assertNull(remoteBudget("food", budgetDoc(budget, serverTime) - "amountMinor"))
    }
}
