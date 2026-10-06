package dev.kortex.finance.ui.read

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kortex.finance.domain.port.Clock
import dev.kortex.finance.domain.repository.SmsInboxRepository
import dev.kortex.finance.domain.usecase.ResolveInboxSms
import dev.kortex.finance.ui.FinanceRoute
import dev.kortex.finance.ui.common.FinanceNotice
import dev.kortex.finance.ui.common.FinanceNotices
import dev.kortex.finance.ui.common.FinanceUndo
import dev.kortex.mvi.MviViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@HiltViewModel
class SmsReviewViewModel @Inject constructor(
    inbox: SmsInboxRepository,
    private val resolve: ResolveInboxSms,
    private val notices: FinanceNotices,
    clock: Clock,
) : MviViewModel<SmsReviewState, SmsReviewIntent, SmsReviewEffect>(SmsReviewState()) {

    init {
        inbox.observeToReview().map { SmsReviewUi.build(it, clock.today(), clock.zone()) }.reduceInto { it }
    }

    override fun handleIntent(intent: SmsReviewIntent) {
        when (intent) {
            is SmsReviewIntent.Open -> sendEffect(SmsReviewEffect.Navigate(FinanceRoute.PasteSms(intent.row.body, intent.row.id)))
            is SmsReviewIntent.Dismiss -> viewModelScope.launch {
                val before = resolve.dismiss(intent.row.id) ?: return@launch
                notices.post(FinanceNotice("SMS from ${intent.row.sender} dismissed", FinanceUndo.RestoreInboxSms(before)))
            }
        }
    }
}
