package dev.kortex.finance.ui.read

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kortex.design.Amber
import dev.kortex.design.Ink
import dev.kortex.design.InkSoft
import dev.kortex.design.Muted
import dev.kortex.design.Panel
import dev.kortex.finance.sms.BankSmsNotifications
import dev.kortex.finance.ui.FinanceRoute
import dev.kortex.finance.ui.common.FinanceNoticeHost
import dev.kortex.finance.ui.common.FinancePushedScreen
import dev.kortex.finance.ui.common.Growth
import dev.kortex.finance.ui.common.NoticeCard
import dev.kortex.finance.ui.common.RowGroup
import dev.kortex.mvi.ObserveEffects

@Composable
fun SmsReviewRoute(onNavigate: (FinanceRoute) -> Unit, onBack: () -> Unit, viewModel: SmsReviewViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Seen: the "N bank SMS to review" notification has done its job.
    LaunchedEffect(Unit) { BankSmsNotifications.cancelReview(context) }
    ObserveEffects(viewModel.effects) { effect ->
        when (effect) {
            is SmsReviewEffect.Navigate -> onNavigate(effect.route)
        }
    }
    Box(Modifier.fillMaxSize()) {
        SmsReviewScreen(state, viewModel::onIntent, onBack)
        FinanceNoticeHost(Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
}

@Composable
fun SmsReviewScreen(state: SmsReviewState, onIntent: (SmsReviewIntent) -> Unit, onBack: () -> Unit) {
    FinancePushedScreen(title = "Bank SMS to review", onBack = onBack) {
        if (state.loading) return@FinancePushedScreen
        if (state.rows.isEmpty()) {
            NoticeCard(
                label = "All clear",
                body = "Bank SMS that Kortex couldn’t save on its own wait here: ones it couldn’t read, " +
                    "card payments, possible duplicates, or a card or account it doesn’t know.",
                accent = Growth,
            )
            return@FinancePushedScreen
        }
        RowGroup(
            label = if (state.rows.size == 1) "1 waiting" else "${state.rows.size} waiting",
            rows = state.rows,
            key = { it.id },
        ) { row -> SwipeableSmsRow(row, onIntent) }
        Text(
            "Tap one to check and save it · swipe it away if it isn’t a payment",
            style = MaterialTheme.typography.labelSmall,
            color = Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
    }
}

@Composable
private fun SwipeableSmsRow(row: SmsReviewRowUi, onIntent: (SmsReviewIntent) -> Unit) {
    val swipe = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) onIntent(SmsReviewIntent.Dismiss(row))
            // Springs back: a dismissed row leaves the list on its own once the data changes.
            false
        },
    )
    SwipeToDismissBox(
        state = swipe,
        backgroundContent = {
            Box(Modifier.fillMaxSize().background(Muted.copy(alpha = 0.18f)).padding(horizontal = 20.dp), contentAlignment = Alignment.CenterEnd) {
                Text("Dismiss", style = MaterialTheme.typography.labelLarge, color = InkSoft)
            }
        },
        enableDismissFromStartToEnd = false,
        modifier = Modifier.semantics {
            customActions = listOf(CustomAccessibilityAction("Dismiss") { onIntent(SmsReviewIntent.Dismiss(row)); true })
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Panel)
                .clickable(role = Role.Button) { onIntent(SmsReviewIntent.Open(row)) }
                .padding(horizontal = 16.dp, vertical = 13.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.sender, style = MaterialTheme.typography.bodyLarge, color = Ink, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                Text(row.received, style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            Text(row.reason, style = MaterialTheme.typography.bodySmall, color = Amber)
            Spacer(Modifier.height(6.dp))
            Text(row.body, style = MaterialTheme.typography.bodySmall, color = InkSoft, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}
