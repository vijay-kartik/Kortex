package dev.kortex.app.ui.sync

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.kortex.sync.CloudAccount
import dev.kortex.sync.CloudSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Keeps links and topics in step with the cloud while the app is on screen (docs/CLOUD_SYNC_PLAN.md
 * › Live sync). Place it inside the signed-in content only, so it starts after onboarding has
 * restored the library. When the app leaves the screen, whatever wasn't pushed yet is pushed in
 * [appScope], which outlives the screen.
 */
@Composable
fun LiveSync(account: CloudAccount, cloudSync: CloudSync, appScope: CoroutineScope) {
    val user by account.user.collectAsStateWithLifecycle()
    val uid = user?.uid ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(uid, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                cloudSync.runLive()
            } finally {
                appScope.launch { cloudSync.pushPending() }
            }
        }
    }
}
