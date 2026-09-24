package dev.kortex.links.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncMergeTest {

    private val clean = LocalVersion(updatedAtMillis = 1_000, dirty = false)
    private val dirty = LocalVersion(updatedAtMillis = 1_000, dirty = true)

    @Test
    fun `a doc for a row this device never had is applied, unless it is a delete`() {
        assertEquals(RemoteAction.Apply, decideRemote(null, remoteUpdatedAtMillis = 500, remoteDeleted = false))
        assertEquals(RemoteAction.Skip, decideRemote(null, remoteUpdatedAtMillis = 500, remoteDeleted = true))
    }

    @Test
    fun `a row in sync takes whatever the cloud has, older or newer`() {
        assertEquals(RemoteAction.Apply, decideRemote(clean, remoteUpdatedAtMillis = 2_000, remoteDeleted = false))
        assertEquals(RemoteAction.Apply, decideRemote(clean, remoteUpdatedAtMillis = 500, remoteDeleted = false))
        assertEquals(RemoteAction.Delete, decideRemote(clean, remoteUpdatedAtMillis = 500, remoteDeleted = true))
    }

    @Test
    fun `an unpushed change beats an older or equally old doc`() {
        assertEquals(RemoteAction.Skip, decideRemote(dirty, remoteUpdatedAtMillis = 500, remoteDeleted = false))
        assertEquals(RemoteAction.Skip, decideRemote(dirty, remoteUpdatedAtMillis = 1_000, remoteDeleted = false))
        assertEquals(RemoteAction.Skip, decideRemote(dirty, remoteUpdatedAtMillis = 500, remoteDeleted = true))
    }

    @Test
    fun `a newer doc beats an unpushed change`() {
        assertEquals(RemoteAction.Apply, decideRemote(dirty, remoteUpdatedAtMillis = 1_001, remoteDeleted = false))
        assertEquals(RemoteAction.Delete, decideRemote(dirty, remoteUpdatedAtMillis = 1_001, remoteDeleted = true))
    }

    @Test
    fun `an edit made elsewhere after a delete here brings the link back`() {
        val deletedHere = LocalVersion(updatedAtMillis = 1_000, dirty = true)
        assertEquals(RemoteAction.Apply, decideRemote(deletedHere, remoteUpdatedAtMillis = 2_000, remoteDeleted = false))
        assertEquals(RemoteAction.Skip, decideRemote(deletedHere, remoteUpdatedAtMillis = 900, remoteDeleted = false))
    }
}
