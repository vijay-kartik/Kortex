package dev.kortex.app.ui.sync

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle

/**
 * Keeps links and topics in step with the cloud while the app is on screen (docs/CLOUD_SYNC_PLAN.md
 * › Live sync). Place it inside the signed-in content only, so it starts after onboarding has
 * restored the library. What happens on leaving the screen is up to
 * [dev.kortex.app.domain.sync.LiveSyncSession].
 */
@Composable
fun LiveSync(vm: LiveSyncViewModel = hiltViewModel()) {
    val user by vm.user.collectAsStateWithLifecycle()
    val uid = user?.uid ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(uid, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.runWhileVisible() }
    }
}
