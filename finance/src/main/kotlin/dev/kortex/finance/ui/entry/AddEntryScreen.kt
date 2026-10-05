package dev.kortex.finance.ui.entry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.clickable
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kortex.design.Alarm
import dev.kortex.design.Ink
import dev.kortex.design.InkSoft
import dev.kortex.design.Muted
import dev.kortex.design.Panel
import dev.kortex.design.Synapse
import dev.kortex.finance.ui.FinanceRoute
import dev.kortex.finance.ui.common.AmountLarge
import dev.kortex.finance.ui.common.FieldList
import dev.kortex.finance.ui.common.FieldRow
import dev.kortex.finance.ui.common.FinanceChip
import dev.kortex.finance.ui.common.FinanceColors
import dev.kortex.finance.ui.common.FinanceFormat
import dev.kortex.finance.ui.common.FinanceSheet
import dev.kortex.finance.ui.common.Growth
import dev.kortex.finance.ui.common.InputCard
import dev.kortex.finance.ui.common.PrimaryButton
import dev.kortex.mvi.ObserveEffects
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
fun AddEntryRoute(
    income: Boolean,
    onNavigate: (FinanceRoute) -> Unit,
    onClose: () -> Unit,
    viewModel: AddEntryViewModel = hiltViewModel(),
) {
    LaunchedEffect(income) { viewModel.start(income) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    ObserveEffects(viewModel.effects) { effect ->
        when (effect) {
            AddEntryEffect.Close -> onClose()
            is AddEntryEffect.Navigate -> onNavigate(effect.route)
        }
    }
    AddEntryScreen(state, viewModel::onIntent, onClose)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddEntryScreen(state: AddEntryState, onIntent: (AddEntryIntent) -> Unit, onClose: () -> Unit) {
    FinanceSheet(
        title = state.title,
        onClose = onClose,
        footer = {
            state.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Alarm)
                Spacer(Modifier.height(8.dp))
            }
            if (state.noAccounts) {
                PrimaryButton("Add an account first", { onIntent(AddEntryIntent.AddAccount) })
            } else {
                PrimaryButton(state.saveLabel, { onIntent(AddEntryIntent.Save) }, enabled = !state.saving && !state.loading)
            }
        },
    ) {
        if (state.loading) return@FinanceSheet
        InputCard(
            label = if (state.income) "Amount received" else "Amount",
            value = state.amount,
            onValueChange = { onIntent(AddEntryIntent.Amount(it)) },
            placeholder = "₹0.00",
            keyboardType = KeyboardType.Decimal,
            textStyle = AmountLarge.copy(color = if (state.income) Growth else Synapse),
        )
        InputCard(
            label = state.merchantLabel,
            value = state.merchant,
            onValueChange = { onIntent(AddEntryIntent.Merchant(it)) },
            placeholder = if (state.income) "e.g. Acme Technologies" else "e.g. Whole Foods Market",
        )
        AccountAndDate(state, onIntent)
        CategoryPicker(state, onIntent)
        InputCard("Note", state.note, { onIntent(AddEntryIntent.Note(it)) }, placeholder = "Add a note")
    }
    if (state.datePickerOpen) EntryDatePicker(state.date, onIntent)
}

@Composable
private fun AccountAndDate(state: AddEntryState, onIntent: (AddEntryIntent) -> Unit) {
    var accountsOpen by remember { mutableStateOf(false) }
    Box {
        FieldList(
            listOf(
                FieldRow("Date", FinanceFormat.fullDate(state.date), onClick = { onIntent(AddEntryIntent.ShowDatePicker(true)) }),
                FieldRow(
                    state.accountLabel,
                    state.selectedAccount?.name ?: "Pick one",
                    placeholder = state.selectedAccount == null,
                    onClick = { accountsOpen = true },
                ),
            ),
        )
        DropdownMenu(expanded = accountsOpen, onDismissRequest = { accountsOpen = false }, containerColor = Panel) {
            state.accounts.forEach { account ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(account.name, style = MaterialTheme.typography.bodyLarge, color = if (account.uid == state.accountUid) Synapse else Ink)
                            Text(account.detail, style = MaterialTheme.typography.bodySmall, color = Muted)
                        }
                    },
                    onClick = {
                        accountsOpen = false
                        onIntent(AddEntryIntent.PickAccount(account.uid))
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("+ Add account", style = MaterialTheme.typography.labelLarge, color = Synapse) },
                onClick = {
                    accountsOpen = false
                    onIntent(AddEntryIntent.AddAccount)
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryPicker(state: AddEntryState, onIntent: (AddEntryIntent) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text("Category", style = MaterialTheme.typography.bodyMedium, color = Muted, modifier = Modifier.weight(1f))
            Text(
                "Manage",
                style = MaterialTheme.typography.labelLarge,
                color = Synapse,
                modifier = Modifier.clickable(role = Role.Button) { onIntent(AddEntryIntent.ManageCategories) },
            )
        }
        val suggested = state.suggestedFrom
        val category = state.categories.find { it.uid == state.categoryUid }
        if (suggested != null && category != null) {
            Spacer(Modifier.height(6.dp))
            dev.kortex.finance.ui.common.InsightLine("Kortex suggested ", category.name, " from “$suggested” — tap another to change.", Synapse)
        }
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.categories.forEach { option ->
                FinanceChip(
                    label = option.name,
                    selected = option.uid == state.categoryUid,
                    onClick = { onIntent(AddEntryIntent.PickCategory(option.uid)) },
                    dot = FinanceColors.of(option.colorToken),
                )
            }
            FinanceChip("+ New category", selected = false, onClick = { onIntent(AddEntryIntent.NewCategory) }, dashed = true)
        }
        if (state.categoryUid == null) {
            Spacer(Modifier.height(6.dp))
            Text("No category: it counts as Other.", style = MaterialTheme.typography.bodySmall, color = InkSoft)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryDatePicker(date: LocalDate, onIntent: (AddEntryIntent) -> Unit) {
    // The picker works in UTC midnights.
    val picker = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = { onIntent(AddEntryIntent.ShowDatePicker(false)) },
        confirmButton = {
            TextButton(onClick = {
                val picked = picker.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() } ?: date
                onIntent(AddEntryIntent.PickDate(picked))
            }) { Text("OK", color = Synapse) }
        },
        dismissButton = { TextButton(onClick = { onIntent(AddEntryIntent.ShowDatePicker(false)) }) { Text("Cancel", color = Muted) } },
    ) {
        DatePicker(state = picker)
    }
}
