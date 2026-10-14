package dev.kortex.app.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kortex.finance.domain.port.Clock
import dev.kortex.finance.domain.port.ReviewNotices
import dev.kortex.finance.domain.port.SmsImportScheduler
import dev.kortex.finance.domain.repository.SmsInboxRepository
import dev.kortex.finance.sms.BankSmsSettings
import dev.kortex.finance.sms.BankSmsStore
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Tools & Settings › FINANCES: whether received bank SMS become entries, whether clear ones save on
 * their own, adding earlier SMS, how many wait for review, and clearing what's kept.
 */
@HiltViewModel
class BankSmsViewModel @Inject constructor(
    private val store: BankSmsStore,
    private val inbox: SmsInboxRepository,
    private val imports: SmsImportScheduler,
    private val reviewNotices: ReviewNotices,
    private val clock: Clock,
) : ViewModel() {

    val settings: StateFlow<BankSmsSettings> = store.settings

    /** Waiting in To review. */
    val toReview: StateFlow<Int> = inbox.observeToReview().map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Every SMS kept, whatever became of it; "Clear SMS inbox" shows while there are any. */
    val kept: StateFlow<Int> = inbox.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** An import of earlier SMS is reading or waiting to. */
    val importing: StateFlow<Boolean> = imports.observeImporting()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Add earlier SMS: reads the phone's SMS from the start of [from]. Needs `READ_SMS`. */
    fun startImport(from: LocalDate) =
        imports.importSince(from.atStartOfDay(clock.zone()).toInstant().toEpochMilli())

    /** Forgets every kept SMS, waiting ones included, and the notification about them. Entries they became stay. */
    fun clearInbox() {
        viewModelScope.launch {
            inbox.clear()
            reviewNotices.cancelReview()
        }
    }

    fun setEnabled(enabled: Boolean) = store.update { it.copy(enabled = enabled) }

    fun setAutoSave(autoSave: Boolean) = store.update { it.copy(autoSave = autoSave) }
}
