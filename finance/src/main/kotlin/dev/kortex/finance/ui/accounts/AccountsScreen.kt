package dev.kortex.finance.ui.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kortex.design.Alarm
import dev.kortex.design.Amber
import dev.kortex.design.Ink
import dev.kortex.design.InkSoft
import dev.kortex.design.Muted
import dev.kortex.design.Synapse
import dev.kortex.finance.R
import dev.kortex.finance.domain.model.AccountKind
import dev.kortex.finance.domain.model.StatementStatus
import dev.kortex.finance.ui.FinanceRoute
import dev.kortex.finance.ui.common.AmountLarge
import dev.kortex.finance.ui.common.AmountMono
import dev.kortex.finance.ui.common.BottomBarClearance
import dev.kortex.finance.ui.common.FieldList
import dev.kortex.finance.ui.common.FieldRow
import dev.kortex.finance.ui.common.FinanceCard
import dev.kortex.finance.ui.common.FinanceFormat
import dev.kortex.finance.ui.common.IconTile
import dev.kortex.finance.ui.common.PrimaryButton
import dev.kortex.finance.ui.common.ProgressTrack
import dev.kortex.finance.ui.common.ScreenLock
import dev.kortex.finance.ui.common.SectionLabel
import dev.kortex.mvi.ObserveEffects

@Composable
fun AccountsRoute(onNavigate: (FinanceRoute) -> Unit, viewModel: AccountsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ObserveEffects(viewModel.effects) { effect ->
        when (effect) {
            is AccountsEffect.Navigate -> onNavigate(effect.route)
        }
    }
    AccountsScreen(state, viewModel::onIntent)
}

@Composable
fun AccountsScreen(state: AccountsState, onIntent: (AccountsIntent) -> Unit, modifier: Modifier = Modifier) {
    if (state.loading) return
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = BottomBarClearance),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            FinanceCard(padding = 20.dp) {
                SectionLabel("Total balance")
                Spacer(Modifier.height(10.dp))
                Text(FinanceFormat.rupees(state.totalMinor), style = AmountLarge, color = Ink)
                Spacer(Modifier.height(8.dp))
                Text("Updated from your entries, SMS and receipts", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Your accounts", style = MaterialTheme.typography.titleMedium, color = Ink, modifier = Modifier.weight(1f))
                Text(
                    "+ Add",
                    style = MaterialTheme.typography.labelLarge,
                    color = Synapse,
                    modifier = Modifier.clickableRole { onIntent(AccountsIntent.Add) },
                )
            }
        }
        if (state.rows.isEmpty()) {
            item {
                FinanceCard {
                    Text("No accounts yet", style = MaterialTheme.typography.bodyLarge, color = Ink)
                    Spacer(Modifier.height(4.dp))
                    Text("Add a bank account, cash or wallet with its balance today.", style = MaterialTheme.typography.bodySmall, color = Muted)
                    Spacer(Modifier.height(14.dp))
                    PrimaryButton("Add account", { onIntent(AccountsIntent.Add) })
                }
            }
        }
        items(state.rows, key = { it.uid }) { row ->
            FinanceCard(onClick = { onIntent(AccountsIntent.Open(row.uid)) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(
                        icon = when (row.kind) {
                            AccountKind.CASH -> R.drawable.ic_fin_cash
                            AccountKind.WALLET -> R.drawable.ic_fin_wallet
                            AccountKind.DEBIT_CARD -> R.drawable.ic_fin_card
                            else -> R.drawable.ic_fin_bank
                        },
                        tint = Ink,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(row.name, style = MaterialTheme.typography.bodyLarge, color = Ink)
                        Text(row.subtitle, style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                    Text(FinanceFormat.rupees(row.balanceMinor), style = AmountMono, color = if (row.balanceMinor < 0) Alarm else Ink)
                }
                Spacer(Modifier.height(12.dp))
                Text(row.lastActivity, style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
    }
}

@Composable
fun CardsRoute(onNavigate: (FinanceRoute) -> Unit, viewModel: CardsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ObserveEffects(viewModel.effects) { effect ->
        when (effect) {
            is CardsEffect.Navigate -> onNavigate(effect.route)
        }
    }
    CardsScreen(state, viewModel::onIntent)
}

@Composable
fun CardsScreen(state: CardsState, onIntent: (CardsIntent) -> Unit, modifier: Modifier = Modifier) {
    if (state.loading) return
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = BottomBarClearance),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state.cards.isEmpty()) {
            item {
                FinanceCard(highlighted = true) {
                    SectionLabel("Credit cards", color = Synapse)
                    Spacer(Modifier.height(8.dp))
                    Text("No credit cards yet", style = MaterialTheme.typography.titleLarge, color = Ink)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Add a card with its limit, statement and due dates, and what you owe on it today.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = InkSoft,
                    )
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton("Add card", { onIntent(CardsIntent.AddCard) })
                }
            }
            return@LazyColumn
        }
        state.cards.forEach { card ->
            item(key = card.uid) { CardVisual(card) { onIntent(CardsIntent.Edit(card.uid)) } }
            item(key = "${card.uid}/overview") { CardOverview(card, onPayBill = { onIntent(CardsIntent.PayBill(it)) }) }
            item(key = "${card.uid}/details") { CardDetails(card, state.revealed[card.uid], onIntent) }
        }
        item {
            Text(
                "+ Add another card",
                style = MaterialTheme.typography.labelLarge,
                color = Synapse,
                modifier = Modifier.clickableRole { onIntent(CardsIntent.AddCard) },
            )
        }
    }
}

@Composable
private fun CardVisual(card: CardUi, onClick: () -> Unit) {
    FinanceCard(highlighted = true, onClick = onClick, padding = 20.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(card.name, style = MaterialTheme.typography.titleMedium, color = Ink, modifier = Modifier.weight(1f))
            androidx.compose.material3.Icon(
                androidx.compose.ui.res.painterResource(R.drawable.ic_fin_card),
                contentDescription = null,
                tint = Ink,
            )
        }
        Spacer(Modifier.height(28.dp))
        Text("••••  ••••  ••••  ${card.last4 ?: "····"}", style = MaterialTheme.typography.titleLarge, color = Ink)
        Spacer(Modifier.height(12.dp))
        Row {
            Column(Modifier.weight(1f)) {
                SectionLabel("Card holder", color = Synapse)
                Text(card.holder ?: "—", style = MaterialTheme.typography.bodyMedium, color = Ink)
            }
            Column(Modifier.weight(1f)) {
                SectionLabel("Expires", color = Synapse)
                Text(card.expiry ?: "—", style = MaterialTheme.typography.bodyMedium, color = Ink)
            }
        }
    }
}

@Composable
private fun CardOverview(card: CardUi, onPayBill: (String) -> Unit) {
    FinanceCard {
        Text("Credit limit overview", style = MaterialTheme.typography.titleMedium, color = Ink)
        card.limitMinor?.let {
            Text("Total credit limit: ${FinanceFormat.rupees(it)}", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Spacer(Modifier.height(16.dp))
        val statement = card.statement
        if (statement != null && statement.status != StatementStatus.PAID) {
            Text(
                if (statement.status == StatementStatus.OVERDUE) "Statement overdue · was due ${statement.dueLabel}" else "Statement due · by ${statement.dueLabel}",
                style = MaterialTheme.typography.bodySmall,
                color = if (statement.status == StatementStatus.OVERDUE) Alarm else Muted,
            )
            Text(FinanceFormat.rupees(statement.unpaidMinor), style = AmountLarge, color = Ink)
            Text("Minimum due ${FinanceFormat.rupees(statement.minDueMinor, paise = false)}", style = MaterialTheme.typography.bodySmall, color = Amber)
            Spacer(Modifier.height(12.dp))
            Text("Spent since ${statement.statementOn} statement", style = MaterialTheme.typography.bodySmall, color = Muted)
            Text(FinanceFormat.rupees(card.spentSinceMinor), style = AmountMono, color = Ink)
            Spacer(Modifier.height(14.dp))
            PrimaryButton("Pay bill", { onPayBill(statement.statementUid) })
        } else {
            Text(if (statement == null) "Outstanding · no statement yet" else "Statement paid · spent since", style = MaterialTheme.typography.bodySmall, color = Muted)
            Text(FinanceFormat.rupees(if (statement == null) card.outstandingMinor else card.spentSinceMinor), style = AmountLarge, color = Ink)
        }
        card.availableMinor?.let {
            Spacer(Modifier.height(12.dp))
            Text("Available limit", style = MaterialTheme.typography.bodySmall, color = Muted)
            Text(FinanceFormat.rupees(it), style = AmountMono, color = Ink)
        }
        card.utilisationPercent?.let { percent ->
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProgressTrack((percent / 100).toFloat(), if (percent > 80) Alarm else Synapse, Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                Text("$percent% used", style = MaterialTheme.typography.bodySmall, color = Ink)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Outstanding ${FinanceFormat.rupees(card.outstandingMinor)}" +
                    (card.statement?.takeIf { it.status != StatementStatus.PAID }?.let { ": the bill plus ${FinanceFormat.rupees(card.spentSinceMinor)} spent since." } ?: "."),
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        }
    }
}

@Composable
private fun CardDetails(card: CardUi, revealed: String?, onIntent: (CardsIntent) -> Unit) {
    val context = LocalContext.current
    var problem by remember { mutableStateOf<String?>(null) }
    Column {
        Text("Card details", style = MaterialTheme.typography.titleMedium, color = Ink)
        Spacer(Modifier.height(12.dp))
        FieldList(
            listOfNotNull(
                FieldRow(
                    "Card number",
                    revealed?.let(ScreenLock::grouped) ?: ("•••• •••• •••• ${card.last4 ?: "····"}" + if (card.hasSecret) "  · Show" else ""),
                    valueColor = if (card.hasSecret && revealed == null) Synapse else null,
                    onClick = if (!card.hasSecret) null else {
                        {
                            problem = null
                            if (revealed != null) {
                                onIntent(CardsIntent.Hide(card.uid))
                            } else {
                                ScreenLock.confirm(context, "Show ${card.name}’s number", { onIntent(CardsIntent.Reveal(card.uid)) }, { problem = it })
                            }
                        }
                    },
                ),
                card.holder?.let { FieldRow("Card holder", it) },
                card.expiry?.let { FieldRow("Expiry", it) },
                card.network?.let { FieldRow("Card network", it) },
                card.billingCycle?.let { FieldRow("Billing cycle", it) },
                card.dueDay?.let { FieldRow("Payment due date", it) },
            ),
        )
        problem?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = Amber)
        }
    }
}

/** A text button: clickable with the button role. */
internal fun Modifier.clickableRole(onClick: () -> Unit): Modifier = clickable(role = Role.Button, onClick = onClick)
