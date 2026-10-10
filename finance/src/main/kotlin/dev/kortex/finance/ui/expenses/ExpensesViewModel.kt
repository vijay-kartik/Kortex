package dev.kortex.finance.ui.expenses

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kortex.finance.domain.port.Clock
import dev.kortex.finance.domain.usecase.FinanceSnapshot
import dev.kortex.finance.domain.usecase.ObserveFinance
import dev.kortex.finance.ui.FinanceRoute
import dev.kortex.mvi.MviViewModel
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class ExpensesViewModel @Inject constructor(
    observeFinance: ObserveFinance,
    private val clock: Clock,
) : MviViewModel<ExpensesState, ExpensesIntent, ExpensesEffect>(ExpensesState()) {

    private val period = MutableStateFlow(ExpensesPeriod.startingAt(clock.today()))

    init {
        combine(observeFinance(), period) { snapshot: FinanceSnapshot, period: ExpensesPeriod ->
            ExpensesUi.build(snapshot, clock.today(), period, clock.zone())
        }.flowOn(observeFinance.dispatcher).reduceInto { it }
    }

    override fun handleIntent(intent: ExpensesIntent) {
        when (intent) {
            is ExpensesIntent.SelectMode -> period.update { it.copy(mode = intent.mode) }
            ExpensesIntent.Previous -> period.update { it.previous() }
            ExpensesIntent.Next -> period.update { it.next(clock.today()) }
            ExpensesIntent.OpenReport -> sendEffect(ExpensesEffect.Navigate(FinanceRoute.MonthlyReport(period.value.month)))
        }
    }
}

/** Read-only, so no intents: it shows one month's report and follows changes to the data. */
@HiltViewModel
class MonthlyReportViewModel @Inject constructor(
    private val observeFinance: ObserveFinance,
    private val clock: Clock,
) : ViewModel() {
    private val _state = MutableStateFlow(MonthlyReportState())
    val state: StateFlow<MonthlyReportState> = _state.asStateFlow()

    private var job: Job? = null

    /** Starts following [month]; the route calls it once with the month it was opened for. */
    fun show(month: YearMonth) {
        if (job != null) return
        job = viewModelScope.launch {
            observeFinance()
                .map { snapshot -> MonthlyReportUi.build(snapshot, month, clock.today()) }
                .flowOn(observeFinance.dispatcher)
                .collect { _state.value = it }
        }
    }
}
