package dev.kortex.finance.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kortex.design.Alarm
import dev.kortex.design.Amber
import dev.kortex.design.Edge
import dev.kortex.design.Ink
import dev.kortex.design.InkSoft
import dev.kortex.design.KortexTheme
import dev.kortex.design.Muted
import dev.kortex.design.Synapse
import dev.kortex.finance.R
import dev.kortex.finance.ui.FinanceRoute
import dev.kortex.finance.ui.common.AmountMedium
import dev.kortex.finance.ui.common.AmountMono
import dev.kortex.finance.ui.common.BottomBarClearance
import dev.kortex.finance.ui.common.CashFlowChart
import dev.kortex.finance.ui.common.FinanceCard
import dev.kortex.finance.ui.common.FinanceColors
import dev.kortex.finance.ui.common.FinanceFormat
import dev.kortex.finance.ui.common.FlowBar
import dev.kortex.finance.ui.common.Growth
import dev.kortex.finance.ui.common.IconTile
import dev.kortex.finance.ui.common.InsightLine
import dev.kortex.finance.ui.common.PaceChart
import dev.kortex.finance.ui.common.PrimaryButton
import dev.kortex.finance.ui.common.SectionLabel
import dev.kortex.finance.ui.common.SplitBar
import dev.kortex.mvi.ObserveEffects

@Composable
fun DashboardRoute(onNavigate: (FinanceRoute) -> Unit, viewModel: DashboardViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ObserveEffects(viewModel.effects) { effect ->
        when (effect) {
            is DashboardEffect.Navigate -> onNavigate(effect.route)
        }
    }
    DashboardScreen(state, viewModel::onIntent)
}

@Composable
fun DashboardScreen(state: DashboardState, onIntent: (DashboardIntent) -> Unit, modifier: Modifier = Modifier) {
    if (state.loading) return
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = BottomBarClearance),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!state.hasAccounts) {
            item { FirstAccountCard(onAdd = { onIntent(DashboardIntent.AddAccount) }) }
            return@LazyColumn
        }
        if (state.smsToReview > 0) {
            item { SmsReviewCard(state.smsToReview, onClick = { onIntent(DashboardIntent.OpenSmsReview) }) }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tile(
                    label = "Total balance",
                    value = FinanceFormat.rupees(state.totalBalanceMinor),
                    caption = if (state.accountCount == 1) "1 account" else "${state.accountCount} accounts",
                    captionColor = Muted,
                    modifier = Modifier.weight(1f),
                )
                Tile(
                    label = "Pending",
                    value = FinanceFormat.rupees(state.pendingMinor, paise = false),
                    caption = if (state.pendingCount == 0) "Nothing due · 30 days" else "${state.pendingCount} due · 30 days",
                    captionColor = if (state.pendingCount == 0) Muted else Amber,
                    modifier = Modifier.weight(1f),
                    onClick = { onIntent(DashboardIntent.OpenPending) },
                )
            }
        }
        item { CashFlowCard(state.flow, state.monthName, state.monthNetMinor) }
        if (state.insights.isNotEmpty()) {
            item {
                Column {
                    state.insights.forEachIndexed { index, insight ->
                        if (index > 0) HorizontalDivider(color = Edge)
                        InsightLine(
                            insight.before,
                            insight.highlight,
                            insight.after,
                            highlightColor = when (insight.tone) {
                                Tone.GOOD -> Growth
                                Tone.WARN -> Alarm
                                Tone.NEUTRAL -> Ink
                            },
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                QuickAction("Add Expense", R.drawable.ic_fin_expense_box, Alarm, Modifier.weight(1f)) { onIntent(DashboardIntent.AddExpense) }
                QuickAction("Add Income", R.drawable.ic_fin_income_box, Growth, Modifier.weight(1f)) { onIntent(DashboardIntent.AddIncome) }
            }
        }
        state.pace?.let { pace -> item { PaceCard(pace) } }
        if (state.shares.isNotEmpty()) item { WhereItWentCard(state.shares) }
    }
}

@Composable
private fun Tile(label: String, value: String, caption: String, captionColor: Color, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    FinanceCard(modifier, onClick = onClick, padding = 14.dp) {
        SectionLabel(label)
        Spacer(Modifier.height(8.dp))
        // Shrinks to fit rather than cutting off: ₹4,07,288.81 must never read as ₹4,07,288.8.
        val valueStyle = MaterialTheme.typography.titleLarge.copy(color = Ink)
        BasicText(
            value,
            style = valueStyle,
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = valueStyle.fontSize, stepSize = 0.5.sp),
        )
        Spacer(Modifier.height(6.dp))
        Text(caption, style = MaterialTheme.typography.bodySmall, color = captionColor)
    }
}

/** Bank SMS that weren't saved on their own (docs/SMS_AUTO_PLAN.md, phase 6). */
@Composable
private fun SmsReviewCard(count: Int, onClick: () -> Unit) {
    FinanceCard(onClick = onClick, highlighted = true, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (count == 1) "1 bank SMS to review" else "$count bank SMS to review", style = MaterialTheme.typography.bodyLarge, color = Ink)
                Text("Check them before they count", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            Text("Review", style = MaterialTheme.typography.labelLarge, color = Synapse)
        }
    }
}

@Composable
private fun CashFlowCard(flow: List<FlowBar>, monthName: String, netMinor: Long) {
    FinanceCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Cash flow", style = MaterialTheme.typography.titleMedium, color = Ink, modifier = Modifier.weight(1f))
            SectionLabel("6M", color = Synapse)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LegendDot("In", Growth)
            LegendDot("Out", Synapse)
        }
        Spacer(Modifier.height(12.dp))
        CashFlowChart(flow)
        HorizontalDivider(color = Edge, modifier = Modifier.padding(vertical = 12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$monthName so far", style = MaterialTheme.typography.bodyMedium, color = Muted, modifier = Modifier.weight(1f))
            Text(
                FinanceFormat.rupees(netMinor, signed = true),
                style = AmountMono,
                color = if (netMinor >= 0) Growth else Alarm,
            )
        }
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}

@Composable
private fun QuickAction(label: String, icon: Int, tint: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FinanceCard(modifier, onClick = onClick, padding = 12.dp) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon, tint, size = 24.dp)
            Spacer(Modifier.width(10.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = Ink)
        }
    }
}

@Composable
private fun PaceCard(pace: PaceUi) {
    FinanceCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pace vs ${pace.lastMonthName}", style = MaterialTheme.typography.titleMedium, color = Ink, modifier = Modifier.weight(1f))
            Text("— ${pace.thisMonthShort}   - - ${pace.lastMonthShort}", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Spacer(Modifier.height(16.dp))
        PaceChart(pace.thisMonth, pace.lastMonth, pace.daysInMonth)
        Spacer(Modifier.height(8.dp))
        Row {
            listOf(1, 8, 15, 22, pace.daysInMonth).forEachIndexed { index, day ->
                Text(
                    day.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                    modifier = Modifier.weight(1f),
                    textAlign = if (index == 4) androidx.compose.ui.text.style.TextAlign.End else androidx.compose.ui.text.style.TextAlign.Start,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("At this pace, month ends near", style = MaterialTheme.typography.bodyMedium, color = Muted, modifier = Modifier.weight(1f))
            Text(FinanceFormat.rupees(pace.projectedMinor, paise = false), style = AmountMono, color = Ink)
        }
    }
}

@Composable
fun WhereItWentCard(shares: List<ShareUi>, modifier: Modifier = Modifier, title: String = "Where it went") {
    FinanceCard(modifier) {
        SectionLabel(title)
        Spacer(Modifier.height(12.dp))
        SplitBar(shares.map { it.amountMinor to FinanceColors.of(it.colorToken) })
        Spacer(Modifier.height(12.dp))
        Text(
            androidx.compose.ui.text.buildAnnotatedString {
                shares.forEachIndexed { index, share ->
                    if (index > 0) append("  ·  ")
                    pushStyle(androidx.compose.ui.text.SpanStyle(color = if (share.colorToken == null) InkSoft else FinanceColors.of(share.colorToken)))
                    append("${share.label} ${share.percent}%")
                    pop()
                }
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun FirstAccountCard(onAdd: () -> Unit) {
    FinanceCard(highlighted = true) {
        SectionLabel("Start here", color = Synapse)
        Spacer(Modifier.height(8.dp))
        Text("Add your first account", style = AmountMedium, color = Ink)
        Spacer(Modifier.height(6.dp))
        Text(
            "Your balance, pending payments and spending all start from your accounts and cards. Its balance today is saved as the first entry.",
            style = MaterialTheme.typography.bodyMedium,
            color = InkSoft,
        )
        Spacer(Modifier.height(16.dp))
        PrimaryButton("Add account", onAdd)
    }
}

@Preview
@Composable
private fun DashboardPreview() {
    KortexTheme {
        Box(Modifier.background(MaterialTheme.colorScheme.background)) {
            DashboardScreen(
                DashboardState(
                    loading = false,
                    hasAccounts = true,
                    totalBalanceMinor = 24_850_12,
                    accountCount = 3,
                    pendingMinor = 4_467_00,
                    pendingCount = 5,
                    flow = listOf("APR", "MAY", "JUN", "JUL", "AUG", "SEP").mapIndexed { i, m ->
                        FlowBar(m, 5_420_00, (3_000_00 + i * 120_00).toLong(), i == 5)
                    },
                    monthName = "September",
                    monthNetMinor = 2_239_50,
                    insights = listOf(
                        Insight("You’ve spent ", "₹438 less", " than August by day 29.", Tone.GOOD),
                        Insight("You kept ", "41%", " of September’s income — 8 points more than August.", Tone.GOOD),
                    ),
                    shares = listOf(ShareUi("Food", "Synapse", 39, 1_240_50), ShareUi("Travel", "Teal", 19, 604_00), ShareUi("Other", null, 42, 1_336_00)),
                ),
                onIntent = {},
            )
        }
    }
}
