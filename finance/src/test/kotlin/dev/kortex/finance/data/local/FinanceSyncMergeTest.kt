package dev.kortex.finance.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class FinanceSyncMergeTest {
    @Test
    fun `a new document is applied and a delete of something never here is skipped`() {
        assertEquals(MergeAction.Apply, decideMerge(null, 10, remoteDeleted = false))
        assertEquals(MergeAction.Skip, decideMerge(null, 10, remoteDeleted = true))
    }

    @Test
    fun `a clean row takes whatever the cloud has`() {
        assertEquals(MergeAction.Apply, decideMerge(RowVersion(20, dirty = 0), 10, remoteDeleted = false))
        assertEquals(MergeAction.Delete, decideMerge(RowVersion(20, dirty = 0), 10, remoteDeleted = true))
    }

    @Test
    fun `an unpushed change wins unless the cloud's is newer`() {
        assertEquals(MergeAction.Skip, decideMerge(RowVersion(20, dirty = 2), 20, remoteDeleted = false))
        assertEquals(MergeAction.Skip, decideMerge(RowVersion(20, dirty = 1), 15, remoteDeleted = true))
        assertEquals(MergeAction.Apply, decideMerge(RowVersion(20, dirty = 1), 21, remoteDeleted = false))
        assertEquals(MergeAction.Delete, decideMerge(RowVersion(20, dirty = 1), 21, remoteDeleted = true))
    }

    @Test
    fun `a category name both phones added gets a number`() {
        assertEquals("Groceries", uniqueCategoryName("Groceries", listOf("Food")))
        assertEquals("Groceries (2)", uniqueCategoryName("Groceries", listOf("groceries")))
        assertEquals("Groceries (3)", uniqueCategoryName("Groceries", listOf("Groceries", "Groceries (2)")))
    }
}
