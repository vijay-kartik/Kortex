package dev.kortex.finance.ui.common

import dev.kortex.finance.domain.model.InboxSms
import dev.kortex.finance.domain.model.Recurring
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** What Undo on a notice takes back. */
sealed interface FinanceUndo {
    /** An entry just saved: deletes it. */
    data class DeleteEntry(val transactionUid: String) : FinanceUndo

    /** Mark as paid or Skip: puts [previous] back and removes [paymentUid] when one was recorded. */
    data class RestoreRecurring(val previous: Recurring, val paymentUid: String?) : FinanceUndo

    /** A received SMS swiped out of To review: puts it back as it was. */
    data class RestoreInboxSms(val previous: InboxSms) : FinanceUndo
}

/** A snackbar for the Finance screens; [undo] adds an Undo. */
data class FinanceNotice(val message: String, val undo: FinanceUndo? = null)

/**
 * Carries a notice from a sheet that just closed (Add expense saved) to the Finance screen it closed
 * onto, which shows it (Figma: Paste SMS 03, "Saved ₹42.50 at Whole Foods Market · UNDO").
 */
@Singleton
class FinanceNotices @Inject constructor() {
    private val _notices = MutableSharedFlow<FinanceNotice>(replay = 1, extraBufferCapacity = 1)
    val notices: SharedFlow<FinanceNotice> = _notices.asSharedFlow()

    fun post(notice: FinanceNotice) {
        _notices.tryEmit(notice)
    }

    /** Called once a notice is shown, so it isn't shown again when the screen comes back. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun consumed() {
        _notices.resetReplayCache()
    }
}
