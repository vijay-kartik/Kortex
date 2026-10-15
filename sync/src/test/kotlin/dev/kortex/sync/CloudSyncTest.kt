package dev.kortex.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.firebase.firestore.FirebaseFirestoreException
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CloudSyncTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val me = CloudUser(uid = "me", name = "Me", email = "me@example.com")
    private val other = CloudUser(uid = "other", name = "Other", email = "other@example.com")

    private val log = mutableListOf<String>()
    private val links = FakePart(SyncPartId.Links, log)
    private val topics = FakePart(SyncPartId.Topics, log)
    private val finance = FakePart(SyncPartId.Finance, log)

    private lateinit var storeScope: CoroutineScope
    private lateinit var store: SyncStore
    private lateinit var sync: FirestoreCloudSync

    @Before
    fun setUp() {
        storeScope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = storeScope) {
            File(folder.root, "cloud_sync.preferences_pb")
        }
        store = SyncStore(dataStore)
        sync = FirestoreCloudSync(MutableStateFlow(me), store, listOf(links, topics, finance), NoCounts, now = { NOW })
    }

    @After
    fun tearDown() {
        storeScope.cancel()
    }

    @Test
    fun `refuses to sync while the phone's data belongs to another account`() = runTest {
        store.setLocalOwner(other)
        links.records = 3

        val outcome = sync.syncNow()

        assertTrue(outcome is SyncOutcome.Failed)
        assertTrue((outcome as SyncOutcome.Failed).message.contains("other@example.com"))
        assertEquals(emptyList<String>(), log)
        assertEquals(false, sync.pushPending())
        assertEquals(emptyList<String>(), log)
    }

    @Test
    fun `pulls every part, in order, before pushing any`() = runTest {
        store.setLocalOwner(me)

        assertEquals(SyncOutcome.Done, sync.syncNow())

        assertEquals(
            listOf("pull Links", "pull Topics", "pull Finance", "push Links", "push Topics", "push Finance"),
            log,
        )
        assertEquals(NOW, sync.lastSyncedAt.first())
    }

    @Test
    fun `progress starts at each part's total and never passes it`() = runTest {
        store.setLocalOwner(me)
        links.changed = 2
        links.pages = listOf(1, 5) // documents written since the count
        finance.changed = 4
        finance.pages = listOf(3)
        val heard = mutableListOf<SyncProgress>()

        sync.syncNow { heard += it }

        val first = heard.first()
        assertEquals(SyncProgress.Part(0, 2), first.links)
        assertEquals(SyncProgress.Part(0, 0), first.topics)
        assertEquals(SyncProgress.Part(0, 4), first.finance)
        assertEquals(SyncProgress.Part(1, 2), heard[1].links)
        val last = heard.last()
        assertEquals(SyncProgress.Part(2, 2), last.links)
        assertEquals(SyncProgress.Part(3, 4), last.finance)
    }

    @Test
    fun `an account with nothing to pull reports empty progress`() = runTest {
        store.setLocalOwner(me)
        val heard = mutableListOf<SyncProgress>()

        sync.syncNow { heard += it }

        assertTrue(heard.single().isEmpty)
    }

    @Test
    fun `Firestore failures map to what the user can do about them`() = runTest {
        store.setLocalOwner(me)

        topics.pushError = FirebaseFirestoreException("offline", FirebaseFirestoreException.Code.UNAVAILABLE)
        assertEquals(SyncOutcome.Failed(FirestoreCloudSync.NETWORK_FAILURE), sync.syncNow())

        topics.pushError = FirebaseFirestoreException("denied", FirebaseFirestoreException.Code.PERMISSION_DENIED)
        assertEquals(SyncOutcome.Failed(FirestoreCloudSync.AUTH_FAILURE), sync.syncNow())

        topics.pushError = IllegalStateException("boom")
        assertEquals(SyncOutcome.Failed(FirestoreCloudSync.GENERIC_FAILURE), sync.syncNow())
        assertNull(sync.lastSyncedAt.first())
    }

    @Test
    fun `another account's data is counted per part`() = runTest {
        store.setLocalOwner(other)
        links.records = 3
        finance.records = 2
        topics.unpushed = 1
        finance.unpushed = 4

        val data = sync.otherAccountData(me)

        assertNotNull(data)
        assertEquals(3, data!!.linkCount)
        assertEquals(0, data.topicCount)
        assertEquals(2, data.financeCount)
        assertEquals(5, data.unpushedCount)
        assertEquals("other@example.com", data.ownerEmail)
    }

    @Test
    fun `an empty phone left by another account is claimed without asking`() = runTest {
        store.setLocalOwner(other)

        assertNull(sync.otherAccountData(me))

        assertEquals(listOf("adopt Links", "adopt Topics", "adopt Finance"), log)
        assertEquals("me", store.localOwner()?.uid)
    }

    @Test
    fun `discarding clears every part and claims the phone for the new user`() = runTest {
        store.setLocalOwner(other)
        links.records = 3
        topics.records = 1
        store.advancePulledAt(me.uid, SyncCollection.Links, 1_000)

        sync.discardLocalData(me)

        assertEquals(listOf("discard Links", "discard Topics", "discard Finance"), log)
        assertEquals("me", store.localOwner()?.uid)
        assertEquals(0L, store.pulledAt(me.uid, SyncCollection.Links))
        assertNull(sync.otherAccountData(me))
    }

    @Test
    fun `keeping hands every part to the new user`() = runTest {
        store.setLocalOwner(other)
        links.records = 3

        sync.keepLocalData(me)

        assertEquals(listOf("adopt Links", "adopt Topics", "adopt Finance"), log)
        assertEquals("me", store.localOwner()?.uid)
        assertEquals(SyncOutcome.Done, sync.syncNow())
    }

    private class FakePart(override val id: SyncPartId, private val log: MutableList<String>) : SyncPart {
        var changed = 0
        var pages = emptyList<Int>()
        var records = 0
        var unpushed = 0
        var pushError: Exception? = null

        override suspend fun countChanged(userUid: String) = changed

        override suspend fun pull(userUid: String, onPage: (Int) -> Unit) {
            log += "pull $id"
            pages.forEach(onPage)
        }

        override suspend fun push(userUid: String) {
            pushError?.let { throw it }
            log += "push $id"
        }

        override fun listeners(userUid: String) = emptyList<LiveListener>()

        override fun observeUnpushedCount(): Flow<Int> = flowOf(unpushed)

        override suspend fun unpushedCount() = unpushed

        override suspend fun recordCount() = records

        override suspend fun adoptForNewAccount() {
            log += "adopt $id"
        }

        override suspend fun discardAll() {
            log += "discard $id"
            records = 0
        }
    }

    private object NoCounts : LibraryCounts {
        override suspend fun links() = 0
        override suspend fun topics() = 0
        override suspend fun financeAccounts() = 0
        override suspend fun financeEntries() = 0
    }

    private companion object {
        const val NOW = 42_000L
    }
}
