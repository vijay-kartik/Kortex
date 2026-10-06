package dev.kortex.app.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kortex.finance.domain.repository.SmsInboxRepository
import dev.kortex.finance.sms.BankSmsSettings
import dev.kortex.finance.sms.BankSmsStore
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Tools & Settings › FINANCES: whether received bank SMS become entries, whether clear ones save on
 * their own, how many wait for review, and clearing what's kept.
 */
@HiltViewModel
class BankSmsViewModel @Inject constructor(
    private val store: BankSmsStore,
    private val inbox: SmsInboxRepository,
) : ViewModel() {

    val settings: StateFlow<BankSmsSettings> = store.settings

    /** Waiting in To review. */
    val toReview: StateFlow<Int> = inbox.observeToReview().map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Every SMS kept, whatever became of it; "Clear SMS inbox" shows while there are any. */
    val kept: StateFlow<Int> = inbox.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Forgets every kept SMS, waiting ones included. Entries they became stay. */
    fun clearInbox() {
        viewModelScope.launch { inbox.clear() }
    }

    fun setEnabled(enabled: Boolean) = store.update { it.copy(enabled = enabled) }

    fun setAutoSave(autoSave: Boolean) = store.update { it.copy(autoSave = autoSave) }
}
