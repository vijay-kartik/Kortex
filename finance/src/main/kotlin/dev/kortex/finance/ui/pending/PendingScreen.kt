package dev.kortex.finance.ui.pending

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kortex.design.Amber
import dev.kortex.design.Muted
import dev.kortex.design.Synapse
import dev.kortex.finance.domain.calc.PendingKind
import dev.kortex.finance.ui.FinanceRoute
import dev.kortex.finance.ui.common.BreakdownCard
import dev.kortex.finance.ui.common.BreakdownLine
import dev.kortex.finance.ui.common.FilterChips
import dev.kortex.finance.ui.common.FinanceFormat
import dev.kortex.finance.ui.common.FinanceNoticeHost
import dev.kortex.finance.ui.common.FinancePushedScreen
import dev.kortex.finance.ui.common.Growth
import dev.kortex.finance.ui.common.NoticeCard
import dev.kortex.finance.ui.common.PaymentRow
import dev.kortex.finance.ui.common.RowGroup
import dev.kortex.mvi.ObserveEffects

/** The lighter of the two pending colours: subscriptions and fixed expenses. */
internal val RecurringTint = Synapse.copy(alpha = 0.55f)

@Composable
fun PendingRoute(onNavigate: (FinanceRoute) -> Unit, onBack: () -> Unit, viewModel: PendingViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ObserveEffects(viewModel.effects) { effect ->
        when (effect) {
            is PendingEffect.Navigate -> onNavigate(effect.route)
        }
    }
    Box(Modifier.fillMaxSize()) {
        PendingScreen(state, viewModel::onIntent, onBack)
        FinanceNoticeHost(Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
}

@Composable
fun PendingScreen(state: PendingState, onIntent: (PendingIntent) -> Unit, onBack: () -> Unit) {
    FinancePushedScreen(
        title = "Pending payments",
        onBack = onBack,
        action = {
            Text(
                "Manage",
                style = MaterialTheme.typography.labelLarge,
                color = Synapse,
                modifier = Modifier.clickable(role = Role.Button) { onIntent(PendingIntent.Manage) },
            )
        },
    ) {
        if (state.loading) return@FinancePushedScreen
        BreakdownCard(
            label = "Due in next 30 days",
            amountMinor = state.totalMinor,
            lines = listOf(
                BreakdownLine("Card bills · ${state.cardBillCount}", state.cardBillsMinor, Synapse),
                BreakdownLine("Subscriptions & fixed · ${state.recurringCount}", state.recurringMinor, RecurringTint),
            ),
            footer = listOf(
                "Total balance" to FinanceFormat.rupees(state.totalBalanceMinor),
                "Left after pending" to FinanceFormat.rupees(state.leftAfterMinor),
            ),
        )
        if (state.counts[PendingFilter.ALL] == 0) {
            NoticeCard(
                label = "All clear",
                body = "Nothing is due in the next 30 days. Card bills appear after each statement day; recurring payments as they come up.",
                accent = Growth,
            )
            return@FinancePushedScreen
        }
        FilterChips(
            options = PendingFilter.entries,
            selected = state.filter,
            label = { "${it.label} ${state.counts[it] ?: 0}" },
            onSelect = { onIntent(PendingIntent.Filter(it)) },
        )
        state.groups.forEach { group ->
            RowGroup(group.label, group.rows, key = { it.key }) { row -> SwipeablePendingRow(row, onIntent) }
        }
        Text(
            "Swipe a row to mark it paid",
            style = MaterialTheme.typography.labelSmall,
            color = Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
    }
}

@Composable
private fun SwipeablePendingRow(row: PendingRowUi, onIntent: (PendingIntent) -> Unit) {
    val swipe = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) onIntent(PendingIntent.Swipe(row))
            // Always springs back: a paid row leaves the list on its own once the data changes.
            false
        },
    )
    val action = if (row.kind == PendingKind.CARD_BILL) "Pay bill" else "Mark paid"
    SwipeToDismissBox(
        state = swipe,
        backgroundContent = {
            Box(Modifier.fillMaxSize().background(Growth.copy(alpha = 0.18f)).padding(horizontal = 20.dp), contentAlignment = Alignment.CenterEnd) {
                Text(action, style = MaterialTheme.typography.labelLarge, color = Growth)
            }
        },
        enableDismissFromStartToEnd = false,
        modifier = Modifier.semantics {
            customActions = listOf(CustomAccessibilityAction(action) { onIntent(PendingIntent.Swipe(row)); true })
        },
    ) {
        PaymentRow(
            title = row.title,
            subtitle = row.subtitle,
            amountMinor = row.amountMinor,
            date = FinanceFormat.weekdayDay(row.dueOn),
            urgency = row.urgency,
            onClick = { onIntent(PendingIntent.Open(row)) },
            subtitleColor = if (row.needsAccount) Amber else Muted,
        )
    }
}
