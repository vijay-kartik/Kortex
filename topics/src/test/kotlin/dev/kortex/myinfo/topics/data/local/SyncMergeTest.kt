package dev.kortex.myinfo.topics.data.local

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
    fun `a row in sync takes whatever the cloud has`() {
        assertEquals(RemoteAction.Apply, decideRemote(clean, remoteUpdatedAtMillis = 500, remoteDeleted = false))
        assertEquals(RemoteAction.Delete, decideRemote(clean, remoteUpdatedAtMillis = 500, remoteDeleted = true))
    }

    @Test
    fun `an unpushed change wins ties and beats older docs, a newer doc beats it`() {
        assertEquals(RemoteAction.Skip, decideRemote(dirty, remoteUpdatedAtMillis = 1_000, remoteDeleted = false))
        assertEquals(RemoteAction.Skip, decideRemote(dirty, remoteUpdatedAtMillis = 500, remoteDeleted = true))
        assertEquals(RemoteAction.Apply, decideRemote(dirty, remoteUpdatedAtMillis = 1_001, remoteDeleted = false))
        assertEquals(RemoteAction.Delete, decideRemote(dirty, remoteUpdatedAtMillis = 1_001, remoteDeleted = true))
    }

    @Test
    fun `a free name is kept`() {
        assertEquals("Japan trip", uniqueTopicName("Japan trip", listOf("Tax 2026")))
    }

    @Test
    fun `a taken name gets the first free number, ignoring case`() {
        assertEquals("Japan trip (2)", uniqueTopicName("Japan trip", listOf("japan TRIP")))
        assertEquals("Japan trip (4)", uniqueTopicName("Japan trip", listOf("Japan trip", "Japan trip (2)", "japan trip (3)")))
    }
}
