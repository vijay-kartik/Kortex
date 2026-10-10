package dev.kortex.finance.ui.recurring

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kortex.design.Amber
import dev.kortex.design.Muted
import dev.kortex.design.Synapse
import dev.kortex.finance.domain.port.Clock
import dev.kortex.finance.domain.usecase.ObserveFinance
import dev.kortex.finance.ui.FinanceRoute
import dev.kortex.finance.ui.common.BreakdownCard
import dev.kortex.finance.ui.common.BreakdownLine
import dev.kortex.finance.ui.common.FilterChips
import dev.kortex.finance.ui.common.FinanceFormat
import dev.kortex.finance.ui.common.FinanceNoticeHost
import dev.kortex.finance.ui.common.FinancePushedScreen
import dev.kortex.finance.ui.common.NoticeCard
import dev.kortex.finance.ui.common.PaymentRow
import dev.kortex.finance.ui.common.PrimaryButton
import dev.kortex.finance.ui.common.RowGroup
import dev.kortex.finance.ui.pending.RecurringTint
import dev.kortex.mvi.MviViewModel
import dev.kortex.mvi.ObserveEffects
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn

@HiltViewModel
class RecurringListViewModel @Inject constructor(
    observeFinance: ObserveFinance,
    clock: Clock,
) : MviViewModel<RecurringListState, RecurringListIntent, RecurringListEffect>(RecurringListState()) {

    private val filter = MutableStateFlow(RecurringFilter.ALL)

    init {
        combine(observeFinance(), filter) { snapshot, filter -> RecurringListUi.build(snapshot, clock.today(), filter) }
            .flowOn(observeFinance.dispatcher)
            .reduceInto { it }
    }

    override fun handleIntent(intent: RecurringListIntent) {
        when (intent) {
            is RecurringListIntent.Filter -> filter.value = intent.filter
            RecurringListIntent.Add -> sendEffect(RecurringListEffect.Navigate(FinanceRoute.RecurringForm()))
            is RecurringListIntent.Open -> sendEffect(RecurringListEffect.Navigate(FinanceRoute.RecurringForm(intent.uid)))
        }
    }
}

@Composable
fun RecurringListRoute(onNavigate: (FinanceRoute) -> Unit, onBack: () -> Unit, viewModel: RecurringListViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ObserveEffects(viewModel.effects) { effect ->
        when (effect) {
            is RecurringListEffect.Navigate -> onNavigate(effect.route)
        }
    }
    Box(Modifier.fillMaxSize()) {
        RecurringListScreen(state, viewModel::onIntent, onBack)
        FinanceNoticeHost(Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
}

@Composable
fun RecurringListScreen(state: RecurringListState, onIntent: (RecurringListIntent) -> Unit, onBack: () -> Unit) {
    FinancePushedScreen(
        title = "Recurring payments",
        onBack = onBack,
        action = {
            Text(
                "+ Add",
                style = MaterialTheme.typography.labelLarge,
                color = Synapse,
                modifier = Modifier.clickable(role = Role.Button) { onIntent(RecurringListIntent.Add) },
            )
        },
    ) {
        if (state.loading) return@FinancePushedScreen
        if (state.counts[RecurringFilter.ALL] == 0) {
            NoticeCard(
                label = "No recurring payments yet",
                body = "Add subscriptions like Netflix and fixed expenses like rent or a gym. Kortex shows them in Pending payments before they're due and can remind you.",
                accent = Synapse,
            )
            PrimaryButton("Add recurring payment", { onIntent(RecurringListIntent.Add) })
            return@FinancePushedScreen
        }
        BreakdownCard(
            label = "Recurring each month",
            amountMinor = state.perMonthMinor,
            lines = listOf(
                BreakdownLine("Subscriptions · ${state.subscriptionCount}", state.subscriptionsMinor, Synapse),
                BreakdownLine("Fixed expenses · ${state.fixedCount}", state.fixedMinor, RecurringTint),
            ),
            footer = listOfNotNull(
                "Over a year" to FinanceFormat.rupees(state.perYearMinor, paise = false),
                state.nextDue?.let { "Next due" to it },
            ),
        )
        FilterChips(
            options = RecurringFilter.entries,
            selected = state.filter,
            label = { "${it.label} ${state.counts[it] ?: 0}" },
            onSelect = { onIntent(RecurringListIntent.Filter(it)) },
        )
        state.groups.forEach { group ->
            RowGroup(group.label, group.rows, key = { it.uid }) { row ->
                PaymentRow(
                    title = row.name,
                    subtitle = row.subtitle,
                    amountMinor = row.amountMinor,
                    date = row.next,
                    urgency = row.urgency,
                    onClick = { onIntent(RecurringListIntent.Open(row.uid)) },
                    subtitleColor = if (row.warning) Amber else Muted,
                )
            }
        }
        Text(
            "Card bills come from each card’s statement, so they aren’t listed here.",
            style = MaterialTheme.typography.labelSmall,
            color = Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
