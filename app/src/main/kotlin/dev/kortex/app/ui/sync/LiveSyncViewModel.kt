package dev.kortex.app.ui.sync

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kortex.app.domain.sync.LiveSyncSession
import dev.kortex.sync.CloudAccount
import dev.kortex.sync.CloudUser
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/** Hands [LiveSync] the signed-in user and the [LiveSyncSession] it runs while on screen. */
@HiltViewModel
class LiveSyncViewModel @Inject constructor(
    account: CloudAccount,
    private val session: LiveSyncSession,
) : ViewModel() {

    val user: StateFlow<CloudUser?> = account.user

    suspend fun runWhileVisible() = session.runWhileVisible()
}
