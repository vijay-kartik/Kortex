package dev.kortex.finance.ui.pending

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kortex.finance.domain.calc.PendingKind
import dev.kortex.finance.domain.port.Clock
import dev.kortex.finance.domain.usecase.MarkPaid
import dev.kortex.finance.domain.usecase.ObserveFinance
import dev.kortex.finance.domain.usecase.OccurrenceResult
import dev.kortex.finance.ui.FinanceRoute
import dev.kortex.finance.ui.common.FinanceFormat
import dev.kortex.finance.ui.common.FinanceNotice
import dev.kortex.finance.ui.common.FinanceNotices
import dev.kortex.finance.ui.common.FinanceUndo
import dev.kortex.mvi.MviViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

@HiltViewModel
class PendingViewModel @Inject constructor(
    observeFinance: ObserveFinance,
    private val markPaid: MarkPaid,
    private val notices: FinanceNotices,
    private val clock: Clock,
) : MviViewModel<PendingState, PendingIntent, PendingEffect>(PendingState()) {

    private val filter = MutableStateFlow(PendingFilter.ALL)

    init {
        combine(observeFinance(), filter) { snapshot, filter -> PendingUi.build(snapshot, clock.today(), filter) }
            .flowOn(observeFinance.dispatcher)
            .reduceInto { it }
    }

    override fun handleIntent(intent: PendingIntent) {
        when (intent) {
            is PendingIntent.Filter -> filter.value = intent.filter
            is PendingIntent.Open -> sendEffect(PendingEffect.Navigate(routeFor(intent.row)))
            is PendingIntent.Swipe -> swipe(intent.row)
            PendingIntent.Manage -> sendEffect(PendingEffect.Navigate(FinanceRoute.Recurring))
        }
    }

    private fun routeFor(row: PendingRowUi): FinanceRoute =
        if (row.kind == PendingKind.CARD_BILL) FinanceRoute.PayBill(row.sourceUid) else FinanceRoute.MarkPaid(row.sourceUid, row.dueOn)

    /** Figma: Recurring 05 — "Netflix paid · next due 3 Nov · UNDO". */
    private fun swipe(row: PendingRowUi) {
        if (row.kind == PendingKind.CARD_BILL || row.needsAccount) {
            sendEffect(PendingEffect.Navigate(routeFor(row)))
            return
        }
        viewModelScope.launch {
            when (val result = markPaid(row.sourceUid, row.dueOn)) {
                is OccurrenceResult.Paid -> notices.post(
                    FinanceNotice(
                        "${row.title} paid · next due ${FinanceFormat.day(result.nextDueOn)}",
                        FinanceUndo.RestoreRecurring(result.previous, result.transactionUid),
                    ),
                )
                is OccurrenceResult.AlreadyPaid -> notices.post(FinanceNotice("${row.title} was already paid"))
                else -> sendEffect(PendingEffect.Navigate(routeFor(row)))
            }
        }
    }
}
