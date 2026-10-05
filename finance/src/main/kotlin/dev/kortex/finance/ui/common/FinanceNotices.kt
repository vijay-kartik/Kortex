package dev.kortex.finance.ui.common

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** A snackbar for the Finance tabs; [undoTransactionUid] adds an Undo that deletes that entry. */
data class FinanceNotice(val message: String, val undoTransactionUid: String? = null)

/**
 * Carries a notice from a sheet that just closed (Add expense saved) to the Finance tab it closed
 * onto, which shows it (Figma: Paste SMS 03, "Saved ₹42.50 at Whole Foods Market · UNDO").
 */
@Singleton
class FinanceNotices @Inject constructor() {
    private val _notices = MutableSharedFlow<FinanceNotice>(replay = 1, extraBufferCapacity = 1)
    val notices: SharedFlow<FinanceNotice> = _notices.asSharedFlow()

    fun post(notice: FinanceNotice) {
        _notices.tryEmit(notice)
    }

    /** Called once a notice is shown, so it isn't shown again when the tab comes back. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun consumed() {
        _notices.resetReplayCache()
    }
}
