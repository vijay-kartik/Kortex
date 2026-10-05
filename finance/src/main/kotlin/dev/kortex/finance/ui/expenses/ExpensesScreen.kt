package dev.kortex.finance.ui.expenses

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kortex.design.Alarm
import dev.kortex.design.CircleButton
import dev.kortex.design.Edge
import dev.kortex.design.Ink
import dev.kortex.design.InkSoft
import dev.kortex.design.Muted
import dev.kortex.finance.R
import dev.kortex.finance.ui.FinanceRoute
import dev.kortex.finance.ui.common.AmountLarge
import dev.kortex.finance.ui.common.AmountMedium
import dev.kortex.finance.ui.common.AmountMono
import dev.kortex.finance.ui.common.BottomBarClearance
import dev.kortex.finance.ui.common.DayBars
import dev.kortex.finance.ui.common.FinanceCard
import dev.kortex.finance.ui.common.FinanceFormat
import dev.kortex.finance.ui.common.FinancePushedScreen
import dev.kortex.finance.ui.common.Growth
import dev.kortex.finance.ui.common.InsightLine
import dev.kortex.finance.ui.common.ProgressTrack
import dev.kortex.finance.ui.common.SavingsRing
import dev.kortex.finance.ui.common.SectionLabel
import dev.kortex.finance.ui.common.Segmented
import dev.kortex.finance.ui.dashboard.Tone
import dev.kortex.finance.ui.dashboard.WhereItWentCard
import dev.kortex.mvi.ObserveEffects
import java.time.YearMonth

@Composable
fun ExpensesRoute(onNavigate: (FinanceRoute) -> Unit, viewModel: ExpensesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ObserveEffects(viewModel.effects) { effect ->
        when (effect) {
            is ExpensesEffect.Navigate -> onNavigate(effect.route)
        }
    }
    ExpensesScreen(state, viewModel::onIntent)
}

@Composable
fun ExpensesScreen(state: ExpensesState, onIntent: (ExpensesIntent) -> Unit, modifier: Modifier = Modifier) {
    if (state.loading) return
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = BottomBarClearance),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Segmented(ExpensesMode.entries.map { it.label }, state.mode.ordinal, { onIntent(ExpensesIntent.SelectMode(ExpensesMode.entries[it])) })
        }
        item { PeriodHeader(state.periodTitle, state.canGoNext, onIntent) }
        when (state.mode) {
            ExpensesMode.Daily -> daily(state)
            ExpensesMode.Monthly -> monthly(state, onIntent)
            ExpensesMode.Yearly -> yearly(state)
        }
    }
}

@Composable
private fun PeriodHeader(title: String, canGoNext: Boolean, onIntent: (ExpensesIntent) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        CircleButton(R.drawable.ic_fin_back, "Previous", { onIntent(ExpensesIntent.Previous) })
        Text(title, style = MaterialTheme.typography.titleMedium, color = Ink, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        CircleButton(R.drawable.ic_fin_chevron_right, "Next", { onIntent(ExpensesIntent.Next) }, enabled = canGoNext, tint = if (canGoNext) Ink else Edge)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.daily(state: ExpensesState) {
    item {
        FinanceCard(highlighted = true) {
            SectionLabel(if (state.isToday) "Today’s spending" else "Spent this day", color = dev.kortex.design.Synapse)
            Spacer(Modifier.height(8.dp))
            Text(FinanceFormat.rupees(state.dayTotalMinor), style = AmountLarge, color = Ink)
            Spacer(Modifier.height(6.dp))
            val count = state.dayRows.size
            Text(
                if (count == 0) "Nothing logged" else "$count ${if (count == 1) "transaction" else "transactions"} logged",
                style = MaterialTheme.typography.bodyMedium,
                color = InkSoft,
            )
        }
    }
    if (state.dayRows.isNotEmpty()) {
        item {
            FinanceCard {
                Text("What did I spend on", style = MaterialTheme.typography.titleMedium, color = Ink)
                state.dayRows.forEach { row ->
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(row.title, style = MaterialTheme.typography.bodyLarge, color = Ink)
                            Text(row.subtitle, style = MaterialTheme.typography.bodySmall, color = Muted)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("-${FinanceFormat.rupees(row.amountMinor)}", style = AmountMono, color = Alarm)
                            Text(row.time, style = MaterialTheme.typography.bodySmall, color = Muted)
                        }
                    }
                }
            }
        }
    }
    item {
        FinanceCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Last 7 days", style = MaterialTheme.typography.titleMedium, color = Ink, modifier = Modifier.weight(1f))
                Text(FinanceFormat.rupees(state.lastDaysTotalMinor, paise = false), style = AmountMono, color = Muted)
            }
            Spacer(Modifier.height(14.dp))
            DayBars(state.lastDays)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.monthly(state: ExpensesState, onIntent: (ExpensesIntent) -> Unit) {
    item {
        FinanceCard {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Total("Total spent", state.monthSpentMinor, Modifier.weight(1f))
                Total("Total income", state.monthIncomeMinor, Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Savings this month", style = MaterialTheme.typography.bodyMedium, color = Muted, modifier = Modifier.weight(1f))
                Text(
                    FinanceFormat.rupees(state.monthSavingsMinor),
                    style = AmountMedium,
                    color = if (state.monthSavingsMinor >= 0) Growth else Alarm,
                )
            }
        }
    }
    item {
        FinanceCard(onClick = { onIntent(ExpensesIntent.OpenReport) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${FinanceFormat.monthName(state.month)} report", style = MaterialTheme.typography.bodyLarge, color = Ink)
                    Text("Income vs expenses, savings rate", style = MaterialTheme.typography.bodySmall, color = Muted)
                }
                androidx.compose.material3.Icon(
                    androidx.compose.ui.res.painterResource(R.drawable.ic_fin_chevron_right),
                    contentDescription = null,
                    tint = Muted,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
    if (state.standouts.isNotEmpty()) {
        item {
            Column {
                SectionLabel("What stood out", Modifier.padding(bottom = 4.dp))
                state.standouts.forEachIndexed { index, insight ->
                    if (index > 0) HorizontalDivider(color = Edge)
                    InsightLine(
                        insight.before,
                        insight.highlight,
                        insight.after,
                        highlightColor = when (insight.tone) {
                            Tone.GOOD -> Growth
                            Tone.WARN -> Alarm
                            Tone.NEUTRAL -> InkSoft
                        },
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Total(label: String, amountMinor: Long, modifier: Modifier = Modifier) {
    FinanceCard(modifier, padding = 12.dp) {
        SectionLabel(label)
        Spacer(Modifier.height(6.dp))
        Text(FinanceFormat.rupees(amountMinor), style = MaterialTheme.typography.titleLarge, color = Ink, maxLines = 1)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.yearly(state: ExpensesState) {
    item {
        FinanceCard(highlighted = true) {
            SectionLabel("Total expenditure", color = dev.kortex.design.Synapse)
            Spacer(Modifier.height(8.dp))
            Text(FinanceFormat.rupees(state.yearSpentMinor), style = AmountLarge, color = Ink)
            Spacer(Modifier.height(6.dp))
            Text("From all your entries in ${state.year}", style = MaterialTheme.typography.bodyMedium, color = InkSoft)
        }
    }
    if (state.yearShares.isNotEmpty()) item { WhereItWentCard(state.yearShares) }
    item {
        FinanceCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Total savings in ${state.year}", style = MaterialTheme.typography.bodyLarge, color = InkSoft, modifier = Modifier.weight(1f))
                Text(
                    FinanceFormat.rupees(state.yearSavingsMinor),
                    style = AmountMedium,
                    color = if (state.yearSavingsMinor >= 0) Growth else Alarm,
                )
            }
            state.yearSavingsPercent?.let {
                Spacer(Modifier.height(10.dp))
                InsightLine("Savings rate: ", "$it% of income saved", "", Growth)
            }
        }
    }
}

@Composable
fun MonthlyReportRoute(month: YearMonth, onBack: () -> Unit, viewModel: MonthlyReportViewModel = hiltViewModel()) {
    LaunchedEffect(month) { viewModel.show(month) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    MonthlyReportScreen(state, onBack)
}

@Composable
fun MonthlyReportScreen(state: MonthlyReportState, onBack: () -> Unit) {
    FinancePushedScreen(title = state.title, onBack = onBack) {
        if (state.loading) return@FinancePushedScreen
        if (state.shares.isNotEmpty()) WhereItWentCard(state.shares)
        FinanceCard {
            Text("Income vs Expenses", style = MaterialTheme.typography.titleMedium, color = Ink)
            val max = maxOf(state.incomeMinor, state.spentMinor).coerceAtLeast(1)
            listOf(Triple("Total income", state.incomeMinor, Growth), Triple("Total expenses", state.spentMinor, Alarm)).forEach { (label, value, color) ->
                Spacer(Modifier.height(14.dp))
                Row {
                    Text(label, style = MaterialTheme.typography.bodyMedium, color = Muted, modifier = Modifier.weight(1f))
                    Text(FinanceFormat.rupees(value), style = AmountMono, color = color)
                }
                Spacer(Modifier.height(8.dp))
                ProgressTrack(value.toFloat() / max, color)
            }
        }
        FinanceCard {
            Text("Savings rate", style = MaterialTheme.typography.titleMedium, color = Ink)
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SavingsRing(state.savingsPercent)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(state.savingsHeadline, style = MaterialTheme.typography.bodyLarge, color = Ink)
                    Text(state.savingsLine, style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }
        }
    }
}
