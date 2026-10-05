package dev.kortex.finance.ui.recurring

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kortex.design.Alarm
import dev.kortex.design.Amber
import dev.kortex.design.Synapse
import dev.kortex.finance.domain.calc.DueUrgency
import dev.kortex.finance.ui.common.AmountLarge
import dev.kortex.finance.ui.common.ChoiceField
import dev.kortex.finance.ui.common.DateField
import dev.kortex.finance.ui.common.FieldGroup
import dev.kortex.finance.ui.common.FinanceFormat
import dev.kortex.finance.ui.common.FinanceSheet
import dev.kortex.finance.ui.common.InputCard
import dev.kortex.finance.ui.common.NoticeCard
import dev.kortex.finance.ui.common.PrimaryButton
import dev.kortex.finance.ui.common.SecondaryButton
import dev.kortex.finance.ui.entry.CategoryOption
import dev.kortex.mvi.ObserveEffects
import java.time.LocalDate

@Composable
fun MarkPaidRoute(recurringUid: String, dueOn: LocalDate, onClose: () -> Unit, viewModel: MarkPaidViewModel = hiltViewModel()) {
    LaunchedEffect(recurringUid, dueOn) { viewModel.start(recurringUid, dueOn) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    ObserveEffects(viewModel.effects) { effect ->
        when (effect) {
            MarkPaidEffect.Close -> onClose()
        }
    }
    MarkPaidScreen(state, viewModel::onIntent, onClose)
}

@Composable
fun MarkPaidScreen(state: MarkPaidState, onIntent: (MarkPaidIntent) -> Unit, onClose: () -> Unit) {
    FinanceSheet(
        title = "Mark as paid",
        onClose = onClose,
        footer = {
            state.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Alarm)
                Spacer(Modifier.height(8.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Skip this time", { onIntent(MarkPaidIntent.Skip) }, Modifier.weight(1f))
                PrimaryButton("Mark as paid", { onIntent(MarkPaidIntent.MarkPaid) }, Modifier.weight(1f), enabled = !state.saving && !state.loading)
            }
        },
    ) {
        if (state.loading) return@FinanceSheet
        val overdue = state.urgency == DueUrgency.OVERDUE
        NoticeCard(
            label = "${state.name} · ${if (overdue) "was due" else "due"} ${FinanceFormat.weekdayDay(state.dueOn)}",
            body = "${state.description}. Marking it paid records the expense and moves the next due date to ${FinanceFormat.day(state.nextDueOn)}.",
            accent = if (overdue) Alarm else Amber,
        )
        InputCard(
            label = "Amount",
            value = state.amount,
            onValueChange = { onIntent(MarkPaidIntent.Amount(it)) },
            placeholder = "₹0",
            keyboardType = KeyboardType.Decimal,
            textStyle = AmountLarge.copy(color = Synapse),
        )
        val noCategory = CategoryOption("", "None", "")
        FieldGroup(
            rows = listOf(
                { DateField("Paid on", state.paidOn, state.today, onPick = { onIntent(MarkPaidIntent.PaidOn(it)) }, latest = state.today) },
                {
                    ChoiceField(
                        label = "Paid from",
                        value = state.selectedAccount?.name ?: "Pick one",
                        options = state.accounts,
                        optionLabel = { it.name },
                        optionDetail = { it.detail },
                        onPick = { onIntent(MarkPaidIntent.Account(it.uid)) },
                        valueColor = if (state.selectedAccount == null) Amber else null,
                        selected = { it.uid == state.accountUid },
                    )
                },
                {
                    ChoiceField(
                        label = "Category",
                        value = state.selectedCategory?.name ?: "None",
                        options = listOf(noCategory) + state.categories,
                        optionLabel = { it.name },
                        onPick = { onIntent(MarkPaidIntent.Category(it.uid.ifEmpty { null })) },
                        placeholder = state.selectedCategory == null,
                        selected = { (it.uid.ifEmpty { null }) == state.categoryUid },
                    )
                },
            ),
        )
    }
}
