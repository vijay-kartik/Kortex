package dev.kortex.finance.ui

import dev.kortex.finance.domain.model.AccountKind
import dev.kortex.finance.ui.accounts.AccountFormState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AccountFormStateTest {
    @Test
    fun aFullCardNumberKeepsOnlyTheLastFour() {
        val (draft, problem) = AccountFormState(
            kind = AccountKind.CREDIT_CARD,
            name = "HDFC Regalia",
            number = "4111 1111 1111 8824",
            statementDay = "25",
            dueDay = "15",
            creditLimit = "50,000",
            opening = "1400",
        ).toDraft()
        assertNull(problem)
        assertEquals("8824", draft!!.last4)
        assertEquals(25, draft.statementDay)
        assertEquals(15, draft.dueDay)
        assertEquals(50_000_00L, draft.creditLimitMinor)
        assertEquals(1_400_00L, draft.openingMinor)
    }

    @Test
    fun tooFewDigitsIsAProblem() {
        val (draft, problem) = AccountFormState(name = "HDFC Savings", number = "12").toDraft()
        assertNull(draft)
        assertNotNull(problem)
    }

    @Test
    fun editingWithoutRetypingKeepsTheSavedDigits() {
        val (draft, _) = AccountFormState(editUid = "a", name = "HDFC Savings", savedLast4 = "4471").toDraft()
        assertEquals("4471", draft!!.last4)
    }

    @Test
    fun zeroOpeningIsFineButGarbageIsNot() {
        assertEquals(0L, AccountFormState(name = "Cash", kind = AccountKind.CASH, opening = "0").toDraft().first!!.openingMinor)
        assertEquals(0L, AccountFormState(name = "Cash", kind = AccountKind.CASH).toDraft().first!!.openingMinor)
        assertNull(AccountFormState(name = "Cash", kind = AccountKind.CASH, opening = "abc").toDraft().first)
        assertNull(AccountFormState(name = "Card", kind = AccountKind.CREDIT_CARD, statementDay = "x").toDraft().first)
    }
}
