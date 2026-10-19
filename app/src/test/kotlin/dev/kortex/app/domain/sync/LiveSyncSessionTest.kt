package dev.kortex.app.domain.sync

import dev.kortex.finance.domain.port.BackgroundSync
import dev.kortex.sync.CloudSync
import dev.kortex.sync.CloudUser
import dev.kortex.sync.OtherAccountData
import dev.kortex.sync.SyncOutcome
import dev.kortex.sync.SyncProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [LiveSyncSession]: the push on leaving the screen, and the background push when that fails. */
class LiveSyncSessionTest {

    /** [runLive] stays live until cancelled; [pushPending] takes [pushDelayMs] and answers [pushSucceeds]. */
    private class FakeCloudSync(private val pushSucceeds: Boolean, private val pushDelayMs: Long = 0) : CloudSync {
        var liveRuns = 0
        var pushes = 0
        var pushesDone = 0

        override val syncing: StateFlow<Boolean> = MutableStateFlow(false)
        override val lastSyncedAt: Flow<Long?> = emptyFlow()
        override suspend fun syncNow(onProgress: (SyncProgress) -> Unit): SyncOutcome = SyncOutcome.Done
        override suspend fun runLive() {
            liveRuns++
            awaitCancellation()
        }
        override suspend fun pushPending(): Boolean {
            pushes++
            delay(pushDelayMs)
            pushesDone++
            return pushSucceeds
        }
        override suspend fun otherAccountData(user: CloudUser): OtherAccountData? = null
        override suspend fun keepLocalData(user: CloudUser) {}
        override suspend fun discardLocalData(user: CloudUser) {}
        override suspend fun linkCount(): Int = 0
        override suspend fun topicCount(): Int = 0
        override suspend fun financeAccountCount(): Int = 0
        override suspend fun financeEntryCount(): Int = 0
    }

    private class RecordingBackgroundSync : BackgroundSync {
        var requests = 0
        override fun requestPush() { requests++ }
    }

    private val background = RecordingBackgroundSync()

    private fun TestScope.appScope() = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

    /** Runs the session on screen, then leaves it. */
    private fun TestScope.showThenLeave(session: LiveSyncSession) {
        val visible = launch { session.runWhileVisible() }
        runCurrent()
        visible.cancel()
        advanceUntilIdle()
    }

    @Test
    fun `a push that succeeds on leaving schedules no background push`() = runTest {
        val cloud = FakeCloudSync(pushSucceeds = true)
        showThenLeave(LiveSyncSession(cloud, background, appScope()))

        assertEquals(1, cloud.pushes)
        assertEquals(0, background.requests)
    }

    @Test
    fun `a push that fails on leaving schedules one background push`() = runTest {
        val cloud = FakeCloudSync(pushSucceeds = false)
        showThenLeave(LiveSyncSession(cloud, background, appScope()))

        assertEquals(1, cloud.pushes)
        assertEquals(1, background.requests)
    }

    @Test
    fun `cancelling live sync pushes what is pending`() = runTest {
        val cloud = FakeCloudSync(pushSucceeds = true)
        val session = LiveSyncSession(cloud, background, appScope())
        val visible = launch { session.runWhileVisible() }
        runCurrent()
        assertEquals(1, cloud.liveRuns)
        assertEquals(0, cloud.pushes)

        visible.cancel()
        advanceUntilIdle()

        assertTrue(visible.isCancelled)
        assertEquals(1, cloud.pushes)
    }

    @Test
    fun `the push finishes in the app scope after the screen's scope is gone`() = runTest {
        val cloud = FakeCloudSync(pushSucceeds = false, pushDelayMs = 1_000)
        val session = LiveSyncSession(cloud, background, appScope())
        val screen = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        screen.launch { session.runWhileVisible() }
        runCurrent()

        screen.cancel()
        advanceUntilIdle()

        assertEquals(1, cloud.pushesDone)
        assertEquals(1, background.requests)
    }
}
