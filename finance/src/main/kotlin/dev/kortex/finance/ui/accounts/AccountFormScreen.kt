package dev.kortex.finance.ui.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kortex.design.Alarm
import dev.kortex.design.Amber
import dev.kortex.design.Edge
import dev.kortex.design.Ink
import dev.kortex.design.Muted
import dev.kortex.design.R as DesignR
import dev.kortex.design.Synapse
import dev.kortex.design.SynapseDim
import dev.kortex.design.Well
import dev.kortex.finance.R
import dev.kortex.finance.domain.model.AccountKind
import dev.kortex.finance.domain.model.BankType
import dev.kortex.finance.ui.AccountPrefill
import dev.kortex.finance.ui.common.FieldList
import dev.kortex.finance.ui.common.FieldRow
import dev.kortex.finance.ui.common.FinanceFormat
import dev.kortex.finance.ui.common.FinanceSheet
import dev.kortex.finance.ui.common.IconTile
import dev.kortex.finance.ui.common.InputCard
import dev.kortex.finance.ui.common.NoticeCard
import dev.kortex.finance.ui.common.PrimaryButton
import dev.kortex.finance.ui.common.ScreenLock
import dev.kortex.finance.ui.common.SecondaryButton
import dev.kortex.finance.ui.common.Segmented
import dev.kortex.finance.ui.common.SheetButton
import dev.kortex.mvi.ObserveEffects

@Composable
fun AccountFormRoute(
    editUid: String?,
    initialKind: AccountKind?,
    onClose: () -> Unit,
    prefill: AccountPrefill? = null,
    viewModel: AccountFormViewModel = hiltViewModel(),
) {
    LaunchedEffect(editUid, initialKind) { viewModel.start(editUid, initialKind, prefill) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    ObserveEffects(viewModel.effects) { effect ->
        when (effect) {
            AccountFormEffect.Close -> onClose()
        }
    }
    val summary = state.deleteSummary
    if (summary != null) {
        DeleteAccountSheet(state.name, summary, viewModel::onIntent)
    } else {
        AccountFormScreen(state, viewModel::onIntent, onClose)
    }
}

@Composable
fun AccountFormScreen(state: AccountFormState, onIntent: (AccountFormIntent) -> Unit, onClose: () -> Unit) {
    fun edit(change: AccountFormState.() -> AccountFormState) = onIntent(AccountFormIntent.Edit(change))
    FinanceSheet(
        title = state.title,
        onClose = onClose,
        headerAction = if (state.editing) {
            { SheetButton(DesignR.drawable.ic_trash, "Delete account", { onIntent(AccountFormIntent.AskDelete) }, tint = Alarm) }
        } else {
            null
        },
        footer = {
            state.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Alarm)
                Spacer(Modifier.height(8.dp))
            }
            PrimaryButton(state.saveLabel, { onIntent(AccountFormIntent.Save) }, enabled = !state.saving && !state.loading)
        },
    ) {
        if (state.loading) return@FinanceSheet
        if (state.fromSms) {
            Text(
                "Filled in from the ${state.fromSmsBank ?: "bank"} SMS. The SMS only shows the last 4 digits, so add the full number.",
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
            )
        }
        if (!state.editing) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                KindTile("Bank account", "Savings or current", R.drawable.ic_fin_bank, state.kind == AccountKind.BANK, Modifier.weight(1f)) {
                    onIntent(AccountFormIntent.SelectKind(AccountKind.BANK))
                }
                KindTile("Credit card", "Visa, Mastercard…", R.drawable.ic_fin_card, state.kind == AccountKind.CREDIT_CARD, Modifier.weight(1f)) {
                    onIntent(AccountFormIntent.SelectKind(AccountKind.CREDIT_CARD))
                }
                KindTile("Cash", "Cash in hand", R.drawable.ic_fin_cash, state.kind == AccountKind.CASH, Modifier.weight(1f)) {
                    onIntent(AccountFormIntent.SelectKind(AccountKind.CASH))
                }
            }
        }
        val card = state.kind == AccountKind.CREDIT_CARD
        InputCard(
            label = if (card) "Card name" else "Account name",
            value = state.name,
            onValueChange = { edit { copy(name = it) } },
            placeholder = when (state.kind) {
                AccountKind.CREDIT_CARD -> "e.g. HDFC Regalia"
                AccountKind.CASH -> "e.g. Cash in hand"
                else -> "e.g. HDFC Savings"
            },
        )
        if (state.kind != AccountKind.CASH) {
            InputCard(
                label = if (card) "Card number" else "Account number",
                value = state.number,
                onValueChange = { text -> edit { copy(number = text.filter { it.isDigit() || it == ' ' }.take(23)) } },
                placeholder = state.savedLast4?.let { "Ends $it" + if (state.hasSecret) " · full number kept" else "" }
                    ?: if (card) "1234 5678 9012 3456" else "Full account number",
                helper = "Only the last 4 digits show in Kortex. The full number is kept encrypted, and only shows on your phone after your screen lock.",
                keyboardType = KeyboardType.Number,
            )
            if (state.editing && state.hasSecret) RevealRow(state, onIntent)
        }
        if (card) {
            InputCard("Card holder name", state.holder, { edit { copy(holder = it) } }, placeholder = "Full name on card")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InputCard("Statement date", state.statementDay, { edit { copy(statementDay = it.filter(Char::isDigit).take(2)) } }, Modifier.weight(1f), placeholder = "25", helper = "Day of month", keyboardType = KeyboardType.Number)
                InputCard("Due date", state.dueDay, { edit { copy(dueDay = it.filter(Char::isDigit).take(2)) } }, Modifier.weight(1f), placeholder = "15", helper = "Day of month", keyboardType = KeyboardType.Number)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InputCard(
                    "Credit limit",
                    state.creditLimit,
                    { edit { copy(creditLimit = it) } },
                    Modifier.weight(1f),
                    placeholder = "₹50,000",
                    tag = state.smsAvailableLimitMinor?.let { "Avl ${FinanceFormat.rupees(it, paise = false)}" },
                    keyboardType = KeyboardType.Decimal,
                )
                if (!state.editing) {
                    InputCard(
                        "Outstanding today",
                        state.opening,
                        { edit { copy(opening = it) } },
                        Modifier.weight(1f),
                        placeholder = state.smsOutstandingMinor()?.let(FinanceFormat::rupees) ?: "₹0.00",
                        helper = if (state.smsAvailableLimitMinor != null) "Limit − Avl in SMS" else "The first entry",
                        keyboardType = KeyboardType.Decimal,
                    )
                } else {
                    InputCard("Expiry", state.expiry, { edit { copy(expiry = it.take(5)) } }, Modifier.weight(1f), placeholder = "MM/YY")
                }
            }
        } else if (state.kind == AccountKind.BANK) {
            InputCard("Bank", state.institution, { edit { copy(institution = it) } }, placeholder = "e.g. HDFC Bank")
            Segmented(listOf("Savings", "Current"), if (state.bankType == BankType.SAVINGS) 0 else 1, { edit { copy(bankType = if (it == 0) BankType.SAVINGS else BankType.CURRENT) } })
            InputCard("IFSC", state.ifsc, { edit { copy(ifsc = it.uppercase().take(11)) } }, placeholder = "HDFC0001234", helper = "Optional")
        }
        if (state.editing) {
            FieldList(listOf(FieldRow("Balance · from your entries", FinanceFormat.rupees(state.balanceMinor ?: 0), placeholder = true)))
            Text("Balances only change through entries: add an expense, income or transfer to move it.", style = MaterialTheme.typography.bodySmall, color = Muted)
        } else if (!card) {
            InputCard(
                "Opening balance · saved as the first entry",
                state.opening,
                { edit { copy(opening = it) } },
                placeholder = "₹0.00",
                keyboardType = KeyboardType.Decimal,
            )
        }
    }
}

/** "Full number · Show": asks for the screen lock, then shows it for a while. */
@Composable
private fun RevealRow(state: AccountFormState, onIntent: (AccountFormIntent) -> Unit) {
    val context = LocalContext.current
    var problem by remember { mutableStateOf<String?>(null) }
    FieldList(
        listOf(
            FieldRow(
                "Full number",
                state.revealedNumber?.let(ScreenLock::grouped) ?: "Show",
                valueColor = if (state.revealedNumber == null) Synapse else null,
                onClick = {
                    problem = null
                    if (state.revealedNumber != null) {
                        onIntent(AccountFormIntent.Hide)
                    } else {
                        ScreenLock.confirm(context, "Show ${state.name.ifBlank { "the" }} number", { onIntent(AccountFormIntent.Reveal) }, { problem = it })
                    }
                },
            ),
        ),
    )
    problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Amber) }
}

@Composable
private fun KindTile(title: String, subtitle: String, icon: Int, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .clip(shape)
            .background(if (selected) SynapseDim else Well)
            .border(1.dp, if (selected) Synapse.copy(alpha = 0.6f) else Edge, shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(14.dp),
    ) {
        IconTile(icon, if (selected) Synapse else Muted, size = 32.dp)
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.labelLarge, color = Ink)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = if (selected) Synapse else Muted, maxLines = 1)
    }
}

@Composable
private fun DeleteAccountSheet(name: String, summary: DeleteSummary, onIntent: (AccountFormIntent) -> Unit) {
    FinanceSheet(
        title = "Delete account",
        onClose = { onIntent(AccountFormIntent.CancelDelete) },
        footer = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Cancel", { onIntent(AccountFormIntent.CancelDelete) }, Modifier.weight(1f))
                PrimaryButton("Delete account", { onIntent(AccountFormIntent.ConfirmDelete) }, Modifier.weight(1f), color = Alarm)
            }
        },
    ) {
        NoticeCard(
            label = "$name · ${summary.entryCount} ${if (summary.entryCount == 1) "entry" else "entries"}",
            body = "Its expenses and income stay in your history, marked as from a deleted account." +
                if (summary.balanceMinor != 0L) " Total balance changes by ${FinanceFormat.rupees(-summary.balanceMinor)}." else "",
            accent = Amber,
        )
        FieldList(
            listOfNotNull(
                FieldRow("Balance", FinanceFormat.rupees(summary.balanceMinor)),
                FieldRow("Entries", "${summary.entryCount}, kept"),
                summary.recurringNames.takeIf { it.isNotEmpty() }?.let { FieldRow("Recurring from it", it.joinToString(", ")) },
            ),
        )
        if (summary.recurringNames.isNotEmpty()) {
            Text(
                "${summary.recurringNames.joinToString(" and ")} ${if (summary.recurringNames.size == 1) "is" else "are"} paid from this account. Pick a new account for ${if (summary.recurringNames.size == 1) "it" else "them"} afterwards.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        }
    }
}
