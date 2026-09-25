package dev.kortex.app.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kortex.app.di.ApplicationScope
import dev.kortex.sync.CloudAccount
import dev.kortex.sync.CloudSync
import dev.kortex.sync.CloudUser
import dev.kortex.sync.SyncOutcome
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Tools & Settings › CLOUD SYNC: the signed-in account, when it last synced, and Sync now. */
@HiltViewModel
class CloudSyncViewModel @Inject constructor(
    account: CloudAccount,
    private val cloudSync: CloudSync,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    val user: StateFlow<CloudUser?> = account.user
    val syncing: StateFlow<Boolean> = cloudSync.syncing
    val lastSyncedAt: StateFlow<Long?> = cloudSync.lastSyncedAt
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    /** Runs in the app scope, so leaving Settings doesn't cut a restore short. */
    fun syncNow() {
        appScope.launch {
            val message = when (val outcome = cloudSync.syncNow()) {
                SyncOutcome.Done -> "Links synced"
                is SyncOutcome.Failed -> outcome.message
            }
            _messages.send(message)
        }
    }
}
