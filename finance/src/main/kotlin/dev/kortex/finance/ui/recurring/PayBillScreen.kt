package dev.kortex.finance.ui.recurring

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kortex.design.Alarm
import dev.kortex.design.Amber
import dev.kortex.design.Edge
import dev.kortex.design.Muted
import dev.kortex.design.Synapse
import dev.kortex.design.Well
import dev.kortex.finance.ui.common.AmountLarge
import dev.kortex.finance.ui.common.ChoiceField
import dev.kortex.finance.ui.common.DateField
import dev.kortex.finance.ui.common.FieldGroup
import dev.kortex.finance.ui.common.FinanceFormat
import dev.kortex.finance.ui.common.FinanceSheet
import dev.kortex.finance.ui.common.InputCard
import dev.kortex.finance.ui.common.NoticeCard
import dev.kortex.finance.ui.common.PrimaryButton
import dev.kortex.finance.ui.common.RadioRow
import dev.kortex.finance.ui.common.SectionLabel
import dev.kortex.mvi.ObserveEffects

@Composable
fun PayBillRoute(statementUid: String, onClose: () -> Unit, viewModel: PayBillViewModel = hiltViewModel()) {
    LaunchedEffect(statementUid) { viewModel.start(statementUid) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    ObserveEffects(viewModel.effects) { effect ->
        when (effect) {
            PayBillEffect.Close -> onClose()
        }
    }
    PayBillScreen(state, viewModel::onIntent, onClose)
}

@Composable
fun PayBillScreen(state: PayBillState, onIntent: (PayBillIntent) -> Unit, onClose: () -> Unit) {
    val paying = state.payingMinor
    FinanceSheet(
        title = "Pay card bill",
        onClose = onClose,
        footer = {
            state.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Alarm)
                Spacer(Modifier.height(8.dp))
            }
            PrimaryButton(
                paying?.let { "Record ${FinanceFormat.rupees(it, paise = false)} payment" } ?: "Record payment",
                { onIntent(PayBillIntent.Pay) },
                enabled = !state.saving && !state.loading && paying != null,
            )
        },
    ) {
        if (state.loading) return@FinanceSheet
        val overdue = state.dueOn.isBefore(state.today)
        NoticeCard(
            label = "${state.cardLabel} · statement ${FinanceFormat.day(state.statementOn)}",
            body = buildString {
                append("Total due ${FinanceFormat.rupees(state.totalDueMinor, paise = false)} ")
                append(if (overdue) "was due ${FinanceFormat.weekdayDay(state.dueOn)}." else "by ${FinanceFormat.weekdayDay(state.dueOn)}.")
                if (state.paidSoFarMinor > 0) append(" ${FinanceFormat.rupees(state.paidSoFarMinor, paise = false)} paid so far.")
                if (state.minRemainingMinor > 0) append(" Minimum due ${FinanceFormat.rupees(state.minRemainingMinor, paise = false)}.")
            },
            accent = if (overdue) Alarm else Muted,
        )
        Column {
            SectionLabel("Paying")
            Spacer(Modifier.height(6.dp))
            Text(paying?.let { FinanceFormat.rupees(it, paise = false) } ?: "₹—", style = AmountLarge, color = Synapse)
        }
        val shape = RoundedCornerShape(16.dp)
        Column(Modifier.fillMaxWidth().clip(shape).background(Well).border(1.dp, Edge, shape)) {
            RadioRow(
                FinanceFormat.rupees(state.unpaidMinor),
                if (state.paidSoFarMinor > 0) "What’s left · clears the bill" else "Full statement · clears the bill",
                state.choice == PayChoice.FULL,
                { onIntent(PayBillIntent.Choose(PayChoice.FULL)) },
            )
            if (state.showMinimum) {
                HorizontalDivider(color = Edge)
                RadioRow(
                    FinanceFormat.rupees(state.minRemainingMinor),
                    "Minimum due · interest on the rest",
                    state.choice == PayChoice.MINIMUM,
                    { onIntent(PayBillIntent.Choose(PayChoice.MINIMUM)) },
                )
            }
            HorizontalDivider(color = Edge)
            RadioRow("Another amount", "Type it in", state.choice == PayChoice.OTHER, { onIntent(PayBillIntent.Choose(PayChoice.OTHER)) })
            if (state.choice == PayChoice.OTHER) {
                InputCard(
                    label = "Amount",
                    value = state.otherAmount,
                    onValueChange = { onIntent(PayBillIntent.OtherAmount(it)) },
                    placeholder = "₹0",
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                )
            }
        }
        FieldGroup(
            rows = listOf(
                {
                    ChoiceField(
                        label = "Pay from",
                        value = state.selectedAccount?.name ?: "Pick one",
                        options = state.accounts,
                        optionLabel = { it.name },
                        optionDetail = { it.detail },
                        onPick = { onIntent(PayBillIntent.From(it.uid)) },
                        valueColor = if (state.selectedAccount == null) Amber else null,
                        selected = { it.uid == state.fromAccountUid },
                    )
                },
                { DateField("Paid on", state.paidOn, state.today, onPick = { onIntent(PayBillIntent.PaidOn(it)) }, latest = state.today) },
            ),
        )
        Text(
            "Recorded as a card payment, not an expense. The purchases were counted when you made them.",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
        )
    }
}
