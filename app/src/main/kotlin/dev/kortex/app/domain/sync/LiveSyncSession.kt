package dev.kortex.app.domain.sync

import dev.kortex.app.di.ApplicationScope
import dev.kortex.finance.domain.port.BackgroundSync
import dev.kortex.sync.CloudSync
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Live sync while the app is on screen (docs/CLOUD_SYNC_PLAN.md › Live sync). When the app leaves
 * the screen, whatever wasn't pushed yet is pushed in [appScope], which outlives the screen; if
 * that fails (offline, say), [backgroundSync] tries again once there's a connection.
 */
class LiveSyncSession @Inject constructor(
    private val cloudSync: CloudSync,
    private val backgroundSync: BackgroundSync,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    /** Runs live sync until cancelled, then pushes what's pending. Call it only while signed in and on screen. */
    suspend fun runWhileVisible() {
        try {
            cloudSync.runLive()
        } finally {
            appScope.launch { if (!cloudSync.pushPending()) backgroundSync.requestPush() }
        }
    }
}
