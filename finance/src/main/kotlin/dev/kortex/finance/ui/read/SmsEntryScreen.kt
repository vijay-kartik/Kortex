package dev.kortex.finance.ui.read

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kortex.design.Alarm
import dev.kortex.design.Amber
import dev.kortex.design.Edge
import dev.kortex.design.Ink
import dev.kortex.design.InkSoft
import dev.kortex.design.Muted
import dev.kortex.design.Synapse
import dev.kortex.design.Sunken
import dev.kortex.design.Void
import dev.kortex.design.dashedBorder
import dev.kortex.finance.domain.read.SmsField
import dev.kortex.finance.domain.read.SmsKind
import dev.kortex.finance.domain.usecase.SmsEntryType
import dev.kortex.finance.ui.FinanceRoute
import dev.kortex.finance.ui.common.AmountLarge
import dev.kortex.finance.ui.common.ChoiceField
import dev.kortex.finance.ui.common.FieldGroup
import dev.kortex.finance.ui.common.FinanceDatePicker
import dev.kortex.finance.ui.common.FinanceFormat
import dev.kortex.finance.ui.common.FinanceSheet
import dev.kortex.finance.ui.common.Growth
import dev.kortex.finance.ui.common.InputCard
import dev.kortex.finance.ui.common.NoticeCard
import dev.kortex.finance.ui.common.PrimaryButton
import dev.kortex.finance.ui.common.SecondaryButton
import dev.kortex.finance.ui.common.ValueField
import dev.kortex.finance.ui.entry.CategoryOption
import dev.kortex.mvi.ObserveEffects

@Composable
fun SmsEntryRoute(
    text: String?,
    inboxId: String?,
    onNavigate: (FinanceRoute) -> Unit,
    onClose: () -> Unit,
    viewModel: SmsEntryViewModel = hiltViewModel(),
) {
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(text) {
        viewModel.start(text, inboxId)
        // The clipboard is read once, only because Paste SMS was tapped (Paste SMS notes › Ways in).
        if (text == null) viewModel.onIntent(SmsEntryIntent.Clipboard(clipboard.getText()?.text))
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    ObserveEffects(viewModel.effects) { effect ->
        when (effect) {
            SmsEntryEffect.Close -> onClose()
            is SmsEntryEffect.Navigate -> onNavigate(effect.route)
            is SmsEntryEffect.Replace -> {
                onClose()
                onNavigate(effect.route)
            }
        }
    }
    SmsEntryScreen(state, viewModel::onIntent, onClose)
}

@Composable
fun SmsEntryScreen(state: SmsEntryState, onIntent: (SmsEntryIntent) -> Unit, onClose: () -> Unit) {
    FinanceSheet(
        title = state.title,
        onClose = onClose,
        footer = { Footer(state, onIntent, onClose) },
    ) {
        when (state.stage) {
            SmsStage.Reading -> Reading(state)
            SmsStage.Paste -> PasteBox(state, onIntent)
            SmsStage.Review -> Review(state, onIntent)
        }
    }
}

@Composable
private fun Footer(state: SmsEntryState, onIntent: (SmsEntryIntent) -> Unit, onClose: () -> Unit) {
    state.error?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = Alarm)
        Spacer(Modifier.height(8.dp))
    }
    when {
        state.stage == SmsStage.Reading -> Unit
        state.stage == SmsStage.Paste -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Enter manually", { onIntent(SmsEntryIntent.EnterManually) }, Modifier.weight(1f))
            PrimaryButton("Read message", { onIntent(SmsEntryIntent.ReadPasteBox) }, Modifier.weight(1f), enabled = state.pasteBox.isNotBlank())
        }
        state.type == SmsEntryType.CARD_REFUND -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Close", onClose, Modifier.weight(1f))
            PrimaryButton("Enter manually", { onIntent(SmsEntryIntent.EnterManually) }, Modifier.weight(1f))
        }
        state.duplicate != null -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Skip", { onIntent(SmsEntryIntent.Skip) }, Modifier.weight(1f))
            PrimaryButton("Add anyway", { onIntent(SmsEntryIntent.AddAnyway) }, Modifier.weight(1f), enabled = !state.saving)
        }
        state.choosingAccount -> PrimaryButton("Choose how you paid", {}, enabled = false)
        else -> PrimaryButton(
            when (state.type) {
                SmsEntryType.INCOME -> "Save income"
                SmsEntryType.CARD_PAYMENT -> "Record card payment"
                else -> "Save expense"
            },
            { onIntent(SmsEntryIntent.Save) },
            enabled = !state.saving,
        )
    }
}

@Composable
private fun Reading(state: SmsEntryState) {
    if (state.text.isNotEmpty()) SmsCard(state.header, state.text, emptyMap())
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(Synapse, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text("Reading the message…", style = MaterialTheme.typography.labelSmall, color = Synapse)
    }
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(dev.kortex.design.Well).border(1.dp, Edge, shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        repeat(4) { i ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Box(Modifier.width(if (i % 2 == 0) 60.dp else 72.dp).height(10.dp).clip(RoundedCornerShape(5.dp)).background(Sunken))
                Box(Modifier.width(if (i == 2) 140.dp else 96.dp).height(10.dp).clip(RoundedCornerShape(5.dp)).background(Sunken))
            }
        }
    }
}

@Composable
private fun PasteBox(state: SmsEntryState, onIntent: (SmsEntryIntent) -> Unit) {
    when (state.rejected) {
        SmsKind.OTP -> NoticeCard("Not a payment", "What you copied looks like an OTP, not a transaction. Nothing was read or saved.", Muted)
        SmsKind.PROMO -> NoticeCard("Not a payment", "What you copied looks like an offer, not a transaction. Nothing was read or saved.", Muted)
        SmsKind.UNREADABLE -> NoticeCard("Couldn’t read it", "Kortex couldn’t find an amount and a debit or credit in it. Check it’s the whole bank SMS, or enter it manually.", Amber)
        else -> Unit
    }
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 130.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Void)
            .dashedBorder(Edge, 16.dp)
            .padding(16.dp),
    ) {
        if (state.pasteBox.isEmpty()) Text("Paste the bank SMS here", style = MaterialTheme.typography.bodyLarge, color = Muted)
        BasicTextField(
            value = state.pasteBox,
            onValueChange = { onIntent(SmsEntryIntent.PasteBox(it)) },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink),
            cursorBrush = SolidColor(Synapse),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Text("In Messages, long-press the bank SMS → Copy, then come back.", style = MaterialTheme.typography.bodyMedium, color = Muted)
    Text("Or share the SMS to Kortex straight from Messages.", style = MaterialTheme.typography.bodyMedium, color = Muted)
}

@Composable
private fun Review(state: SmsEntryState, onIntent: (SmsEntryIntent) -> Unit) {
    state.duplicate?.let { existing ->
        val where = existing.merchant?.let { " at $it" }.orEmpty()
        NoticeCard(
            "Looks already added",
            "You saved ${FinanceFormat.rupees(existing.amountMinor)}$where on ${dateTimeLabel(existing.occurredOn, null)}" +
                "${state.duplicateAccount?.let { " from $it" }.orEmpty()}. Adding this again would count it twice.",
            Amber,
        )
    }
    val attention = buildSet {
        if (state.unknownLast4 != null) add(SmsField.LAST4)
        if (state.needsName) add(SmsField.PAYEE)
    }
    SmsCard(state.header, state.text, state.spans, attention)
    when (state.type) {
        SmsEntryType.INCOME -> Text(
            buildAnnotatedString {
                append("This SMS is money coming in, so it’s saved as ")
                withStyle(SpanStyle(color = Growth)) { append("income") }
                append(".")
            },
            style = MaterialTheme.typography.bodyMedium,
            color = InkSoft,
        )
        SmsEntryType.CARD_PAYMENT -> Text(
            "A card bill payment: money moves from your account to the card. It isn’t spending; the purchases were counted when you made them.",
            style = MaterialTheme.typography.bodyMedium,
            color = InkSoft,
        )
        SmsEntryType.CARD_REFUND -> {
            NoticeCard("Not saved", "A credit on a card that isn’t a bill payment is a refund, and Kortex doesn’t handle refunds yet.", Amber)
            return
        }
        SmsEntryType.EXPENSE -> Unit
    }
    InputCard(
        label = if (state.type == SmsEntryType.INCOME) "Amount received" else "Amount",
        value = state.amount,
        onValueChange = { onIntent(SmsEntryIntent.Amount(it)) },
        placeholder = "₹0.00",
        keyboardType = KeyboardType.Decimal,
        textStyle = AmountLarge.copy(color = if (state.type == SmsEntryType.INCOME) Growth else Synapse),
    )
    if (state.choosingAccount) {
        AccountChooser(
            last4 = state.unknownLast4!!,
            detail = listOfNotNull(state.sms?.bank, "details from this SMS").joinToString(" · "),
            accounts = state.accounts,
            selected = state.accountUid,
            onAdd = { onIntent(SmsEntryIntent.AddCard) },
            onPick = { onIntent(SmsEntryIntent.Account(it)) },
        )
        return
    }
    if (state.type != SmsEntryType.CARD_PAYMENT) {
        InputCard(
            label = when {
                state.needsName -> "Paid to"
                state.type == SmsEntryType.INCOME -> "From"
                else -> "Merchant"
            },
            value = state.merchant,
            onValueChange = { onIntent(SmsEntryIntent.Merchant(it)) },
            placeholder = if (state.needsName) "Who was this for?" else "Who it was",
            tag = if (state.needsName) "Name it once" else null,
            helper = state.upiId?.takeIf { state.needsName }?.let { "The SMS only has a UPI ID. Name it once and Kortex will use that name for $it next time." },
        )
    }
    var picking by rememberSaveable { mutableStateOf(false) }
    val noCategory = CategoryOption("", "None", "")
    val rows = buildList<@Composable () -> Unit> {
        add { ValueField("Date", dateTimeLabel(state.date, state.time), onClick = { picking = true }) }
        if (state.type == SmsEntryType.CARD_PAYMENT) {
            add {
                ChoiceField(
                    label = "Card",
                    value = state.selectedAccount?.name ?: "Pick one",
                    options = state.accounts,
                    optionLabel = { it.name },
                    optionDetail = { it.detail },
                    onPick = { onIntent(SmsEntryIntent.Account(it.uid)) },
                    valueColor = if (state.selectedAccount == null) Amber else null,
                    selected = { it.uid == state.accountUid },
                )
            }
            add {
                ChoiceField(
                    label = "Paid from",
                    value = state.selectedFrom?.name ?: "Pick one",
                    options = state.payingAccounts,
                    optionLabel = { it.name },
                    optionDetail = { it.detail },
                    onPick = { onIntent(SmsEntryIntent.FromAccount(it.uid)) },
                    valueColor = if (state.selectedFrom == null) Amber else null,
                    selected = { it.uid == state.fromAccountUid },
                )
            }
        } else {
            add {
                ChoiceField(
                    label = if (state.type == SmsEntryType.INCOME) "To account" else "Paid with",
                    value = state.selectedAccount?.name ?: "Pick one",
                    options = state.accounts,
                    optionLabel = { it.name },
                    optionDetail = { it.detail },
                    onPick = { onIntent(SmsEntryIntent.Account(it.uid)) },
                    valueColor = if (state.selectedAccount == null) Amber else null,
                    selected = { it.uid == state.accountUid },
                    footer = "+ Add account" to { onIntent(SmsEntryIntent.AddCard) },
                )
            }
            add {
                ChoiceField(
                    label = "Category",
                    value = state.selectedCategory?.let { it.name + if (state.categorySuggested) " · suggested" else "" } ?: "Pick one",
                    options = listOf(noCategory) + state.categories,
                    optionLabel = { it.name },
                    onPick = { onIntent(SmsEntryIntent.Category(it.uid.ifEmpty { null })) },
                    placeholder = state.selectedCategory == null,
                    selected = { it.uid.ifEmpty { null } == state.categoryUid },
                )
            }
        }
    }
    FieldGroup(rows = rows)
    if (state.duplicate == null) {
        InputCard("Note", state.note, { onIntent(SmsEntryIntent.Note(it)) }, placeholder = "Add a note")
    }
    if (picking) {
        FinanceDatePicker(
            date = state.date,
            latest = state.today,
            onPick = {
                picking = false
                onIntent(SmsEntryIntent.Date(it))
            },
            onDismiss = { picking = false },
        )
    }
}
