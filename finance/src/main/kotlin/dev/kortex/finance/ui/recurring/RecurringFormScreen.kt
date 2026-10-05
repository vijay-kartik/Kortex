package dev.kortex.finance.ui.recurring

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kortex.design.Alarm
import dev.kortex.design.Amber
import dev.kortex.design.Muted
import dev.kortex.design.R as DesignR
import dev.kortex.design.Synapse
import dev.kortex.finance.domain.model.Frequency
import dev.kortex.finance.domain.model.RecurringKind
import dev.kortex.finance.ui.FinanceRoute
import dev.kortex.finance.ui.common.AmountLarge
import dev.kortex.finance.ui.common.CheckRow
import dev.kortex.finance.ui.common.ChoiceField
import dev.kortex.finance.ui.common.DateField
import dev.kortex.finance.ui.common.FieldGroup
import dev.kortex.finance.ui.common.FinanceFormat
import dev.kortex.finance.ui.common.FinanceSheet
import dev.kortex.finance.ui.common.InputCard
import dev.kortex.finance.ui.common.NoticeCard
import dev.kortex.finance.ui.common.PrimaryButton
import dev.kortex.finance.ui.common.SecondaryButton
import dev.kortex.finance.ui.common.SectionLabel
import dev.kortex.finance.ui.common.Segmented
import dev.kortex.finance.ui.common.SheetButton
import dev.kortex.finance.ui.entry.CategoryOption
import dev.kortex.mvi.ObserveEffects

@Composable
fun RecurringFormRoute(
    uid: String?,
    onNavigate: (FinanceRoute) -> Unit,
    onClose: () -> Unit,
    viewModel: RecurringFormViewModel = hiltViewModel(),
) {
    LaunchedEffect(uid) { viewModel.start(uid) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    ObserveEffects(viewModel.effects) { effect ->
        when (effect) {
            RecurringFormEffect.Close -> onClose()
            is RecurringFormEffect.Navigate -> onNavigate(effect.route)
            RecurringFormEffect.AskNotificationPermission -> if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    if (state.confirmDelete) {
        DeleteRecurringSheet(state, viewModel::onIntent)
    } else {
        RecurringFormScreen(state, viewModel::onIntent, onClose)
    }
}

@Composable
fun RecurringFormScreen(state: RecurringFormState, onIntent: (RecurringFormIntent) -> Unit, onClose: () -> Unit) {
    FinanceSheet(
        title = state.title,
        onClose = onClose,
        headerAction = if (state.editing) {
            { SheetButton(DesignR.drawable.ic_trash, "Delete recurring payment", { onIntent(RecurringFormIntent.AskDelete) }, tint = Alarm) }
        } else {
            null
        },
        footer = {
            state.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Alarm)
                Spacer(Modifier.height(8.dp))
            }
            PrimaryButton(state.saveLabel, { onIntent(RecurringFormIntent.Save) }, enabled = !state.saving && !state.loading)
        },
    ) {
        if (state.loading) return@FinanceSheet
        Column {
            SectionLabel("Type")
            Spacer(Modifier.height(8.dp))
            Segmented(
                listOf("Subscription", "Fixed expense"),
                if (state.kind == RecurringKind.SUBSCRIPTION) 0 else 1,
                { onIntent(RecurringFormIntent.Kind(if (it == 0) RecurringKind.SUBSCRIPTION else RecurringKind.FIXED)) },
            )
            Spacer(Modifier.height(6.dp))
            Text(state.kindHint, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        InputCard(
            label = "Amount",
            value = state.amount,
            onValueChange = { onIntent(RecurringFormIntent.Amount(it)) },
            placeholder = "₹0",
            keyboardType = KeyboardType.Decimal,
            textStyle = AmountLarge.copy(color = Synapse),
        )
        InputCard(
            label = "Name",
            value = state.name,
            onValueChange = { onIntent(RecurringFormIntent.Name(it)) },
            placeholder = if (state.kind == RecurringKind.SUBSCRIPTION) "e.g. Netflix" else "e.g. Rent",
        )
        val noCategory = CategoryOption("", "None", "")
        FieldGroup(
            rows = listOf(
                {
                    ChoiceField(
                        label = "Repeats",
                        value = RecurringLabels.repeats(state.frequency, state.interval),
                        options = Frequency.entries,
                        optionLabel = { RecurringLabels.repeats(it) },
                        onPick = { onIntent(RecurringFormIntent.Repeats(it)) },
                        selected = { it == state.frequency },
                    )
                },
                { DateField("Next due", state.nextDueOn, state.today, onPick = { onIntent(RecurringFormIntent.NextDue(it)) }) },
                {
                    ChoiceField(
                        label = "Paid from",
                        value = state.selectedAccount?.name ?: "Needs an account",
                        options = state.accounts,
                        optionLabel = { it.name },
                        optionDetail = { it.detail },
                        onPick = { onIntent(RecurringFormIntent.Account(it.uid)) },
                        valueColor = if (state.selectedAccount == null) Amber else null,
                        selected = { it.uid == state.accountUid },
                        footer = "+ Add account" to { onIntent(RecurringFormIntent.AddAccount) },
                    )
                },
                {
                    ChoiceField(
                        label = "Category",
                        value = state.selectedCategory?.name ?: "None",
                        options = listOf(noCategory) + state.categories,
                        optionLabel = { it.name },
                        onPick = { onIntent(RecurringFormIntent.Category(it.uid.ifEmpty { null })) },
                        placeholder = state.selectedCategory == null,
                        selected = { (it.uid.ifEmpty { null }) == state.categoryUid },
                    )
                },
                {
                    ChoiceField(
                        label = "Remind me",
                        value = RecurringLabels.reminder(state.remindDaysBefore),
                        options = RecurringLabels.reminderChoices,
                        optionLabel = RecurringLabels::reminder,
                        onPick = { onIntent(RecurringFormIntent.Remind(it)) },
                        placeholder = state.remindDaysBefore < 0,
                        selected = { it == state.remindDaysBefore },
                    )
                },
            ),
        )
        CheckRow(
            "Mark it paid automatically on the due date (for auto-debits)",
            state.autoMarkPaid,
            { onIntent(RecurringFormIntent.AutoMarkPaid(it)) },
        )
        if (state.editing) {
            CheckRow(
                "Paused: not counted or shown in Pending payments until you turn this off",
                state.paused,
                { onIntent(RecurringFormIntent.Paused(it)) },
            )
        }
    }
}

@Composable
private fun DeleteRecurringSheet(state: RecurringFormState, onIntent: (RecurringFormIntent) -> Unit) {
    FinanceSheet(
        title = "Delete recurring payment",
        onClose = { onIntent(RecurringFormIntent.CancelDelete) },
        footer = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Cancel", { onIntent(RecurringFormIntent.CancelDelete) }, Modifier.weight(1f))
                PrimaryButton("Delete", { onIntent(RecurringFormIntent.ConfirmDelete) }, Modifier.weight(1f), color = Alarm)
            }
        },
    ) {
        val amount = FinanceFormat.parseAmount(state.amount)?.let { " · ${FinanceFormat.rupees(it, paise = false)}" }.orEmpty()
        NoticeCard(
            label = "${state.name}$amount",
            body = "It stops showing in Pending payments and won’t be reminded or marked paid again. Payments already recorded stay in your history.",
            accent = Amber,
        )
    }
}
