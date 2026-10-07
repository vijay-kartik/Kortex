package dev.kortex.finance.ui.read

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import dev.kortex.design.Alarm
import dev.kortex.design.Amber
import dev.kortex.design.Edge
import dev.kortex.design.Muted
import dev.kortex.design.Panel
import dev.kortex.design.Synapse
import dev.kortex.design.Void
import dev.kortex.design.Well
import dev.kortex.finance.ui.FinanceRoute
import dev.kortex.finance.ui.common.AmountLarge
import dev.kortex.finance.ui.common.CheckRow
import dev.kortex.finance.ui.common.ChoiceField
import dev.kortex.finance.ui.common.FieldGroup
import dev.kortex.finance.ui.common.FinanceDatePicker
import dev.kortex.finance.ui.common.FinanceFormat
import dev.kortex.finance.ui.common.FinanceSheet
import dev.kortex.finance.ui.common.InputCard
import dev.kortex.finance.ui.common.NoticeCard
import dev.kortex.finance.ui.common.PrimaryButton
import dev.kortex.finance.ui.common.RadioRow
import dev.kortex.finance.ui.common.SecondaryButton
import dev.kortex.finance.ui.common.SectionLabel
import dev.kortex.finance.ui.common.ValueField
import dev.kortex.finance.ui.entry.CategoryOption
import dev.kortex.mvi.ObserveEffects
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ReceiptEntryRoute(onNavigate: (FinanceRoute) -> Unit, onClose: () -> Unit, viewModel: ReceiptEntryViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val scanner = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val pages = result.takeIf { it.resultCode == Activity.RESULT_OK }
            ?.let { GmsDocumentScanningResult.fromActivityResultIntent(it.data) }
            ?.pages?.map { it.imageUri.toString() }.orEmpty()
        viewModel.onIntent(if (pages.isEmpty()) ReceiptEntryIntent.Cancelled else ReceiptEntryIntent.Scanned(pages))
    }
    LaunchedEffect(Unit) { viewModel.start() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    ObserveEffects(viewModel.effects) { effect ->
        when (effect) {
            ReceiptEntryEffect.LaunchScanner -> {
                val activity = context.findActivity()
                if (activity == null) {
                    viewModel.onIntent(ReceiptEntryIntent.ScannerUnavailable)
                } else {
                    GmsDocumentScanning.getClient(ReceiptScanning.options).getStartScanIntent(activity)
                        .addOnSuccessListener { scanner.launch(IntentSenderRequest.Builder(it).build()) }
                        .addOnFailureListener { viewModel.onIntent(ReceiptEntryIntent.ScannerUnavailable) }
                }
            }
            ReceiptEntryEffect.Close -> onClose()
            is ReceiptEntryEffect.Navigate -> onNavigate(effect.route)
            is ReceiptEntryEffect.Replace -> {
                onClose()
                onNavigate(effect.route)
            }
        }
    }
    ReceiptEntryScreen(state, viewModel::onIntent, onClose)
}

@Composable
fun ReceiptEntryScreen(state: ReceiptEntryState, onIntent: (ReceiptEntryIntent) -> Unit, onClose: () -> Unit) {
    FinanceSheet(title = "Add Expense", onClose = onClose, footer = { Footer(state, onIntent) }) {
        when (state.stage) {
            ReceiptStage.Scanning, ReceiptStage.Reading -> Reading(state)
            ReceiptStage.NoScanner -> NoticeCard(
                "No document scanner",
                "This phone can’t open Google’s document scanner, which needs Google Play services. Enter the expense manually instead.",
                Amber,
            )
            ReceiptStage.Unreadable -> Unreadable(state)
            ReceiptStage.PickTotal -> PickTotal(state, onIntent)
            ReceiptStage.Duplicate -> Duplicate(state, onIntent)
            ReceiptStage.Review -> Review(state, onIntent)
        }
    }
    if (state.viewingPhoto) {
        Dialog(onDismissRequest = { onIntent(ReceiptEntryIntent.ViewPhoto(false)) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Void).clickable { onIntent(ReceiptEntryIntent.ViewPhoto(false)) }, contentAlignment = Alignment.Center) {
                PageImage(state.pages.firstOrNull(), Modifier.fillMaxWidth().padding(16.dp), ContentScale.Fit)
            }
        }
    }
}

@Composable
private fun Footer(state: ReceiptEntryState, onIntent: (ReceiptEntryIntent) -> Unit) {
    state.error?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = Alarm)
        Spacer(Modifier.height(8.dp))
    }
    when (state.stage) {
        ReceiptStage.Scanning, ReceiptStage.Reading -> Unit
        ReceiptStage.NoScanner -> PrimaryButton("Enter manually", { onIntent(ReceiptEntryIntent.EnterManually) })
        ReceiptStage.Unreadable -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Enter manually", { onIntent(ReceiptEntryIntent.EnterManually) }, Modifier.weight(1f))
            PrimaryButton("Retake", { onIntent(ReceiptEntryIntent.Retake) }, Modifier.weight(1f))
        }
        ReceiptStage.PickTotal -> PrimaryButton(
            state.pickedTotalMinor?.let { "Use ${FinanceFormat.rupees(it)}" } ?: "Pick the total",
            { onIntent(ReceiptEntryIntent.UseTotal) },
            enabled = state.pickedTotalMinor != null,
        )
        ReceiptStage.Duplicate -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Add as new", { onIntent(ReceiptEntryIntent.AddAsNew) }, Modifier.weight(1f))
            PrimaryButton("Attach receipt", { onIntent(ReceiptEntryIntent.Attach) }, Modifier.weight(1f), enabled = !state.saving)
        }
        ReceiptStage.Review ->
            if (state.choosingAccount) PrimaryButton("Choose how you paid", {}, enabled = false)
            else PrimaryButton("Save expense", { onIntent(ReceiptEntryIntent.Save) }, enabled = !state.saving)
    }
}

@Composable
private fun Reading(state: ReceiptEntryState) {
    if (state.pages.isNotEmpty()) PhotoPanel(state.pages.first())
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(Synapse, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(if (state.stage == ReceiptStage.Scanning) "Opening the scanner…" else "Reading the receipt…", style = MaterialTheme.typography.labelSmall, color = Synapse)
    }
}

@Composable
private fun Unreadable(state: ReceiptEntryState) {
    state.pages.firstOrNull()?.let { PhotoPanel(it) }
    NoticeCard("Couldn’t read this photo", "It’s too blurry, or too dark, to read the amounts or the date.", Amber)
    listOf(
        "• Lay the receipt flat, in good light.",
        "• Keep the bottom with the total in view.",
        "• Long receipt? Take it in two photos — Kortex joins them.",
    ).forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = Muted) }
}

@Composable
private fun PickTotal(state: ReceiptEntryState, onIntent: (ReceiptEntryIntent) -> Unit) {
    state.pages.firstOrNull()?.let { PhotoPanel(it) }
    Text(
        "The total isn’t clear on this receipt, so more than one amount could be it. Pick the one you paid.",
        style = MaterialTheme.typography.bodyMedium,
        color = Muted,
    )
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Well).border(1.dp, Edge, shape)) {
        state.receipt.candidates.forEachIndexed { index, candidate ->
            if (index > 0) HorizontalDivider(color = Edge)
            RadioRow(FinanceFormat.rupees(candidate.amountMinor), candidate.label, state.pickedTotal == index, { onIntent(ReceiptEntryIntent.PickTotal(index)) })
        }
        HorizontalDivider(color = Edge)
        RadioRow("Another amount", "Type it in", state.pickedTotal < 0, { onIntent(ReceiptEntryIntent.PickTotal(-1)) })
        if (state.pickedTotal < 0) {
            InputCard(
                "Amount", state.otherTotal, { onIntent(ReceiptEntryIntent.OtherTotal(it)) },
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), placeholder = "₹0.00", keyboardType = KeyboardType.Decimal,
            )
        }
    }
}

@Composable
private fun Duplicate(state: ReceiptEntryState, onIntent: (ReceiptEntryIntent) -> Unit) {
    val existing = state.duplicate ?: return
    val source = when (existing.source) {
        dev.kortex.finance.domain.model.TransactionSource.SMS -> "an SMS"
        dev.kortex.finance.domain.model.TransactionSource.STATEMENT -> "an account statement"
        else -> "by hand"
    }
    NoticeCard(
        "Already in your expenses",
        "${FinanceFormat.rupees(existing.amountMinor)}${existing.merchant?.let { " at $it" }.orEmpty()} on ${dateTimeLabel(existing.occurredOn, null)} was added from $source.",
        Synapse,
    )
    ReceiptCard(state, "This receipt", onIntent)
    SectionLabel("Existing expense")
    FieldGroup(
        rows = listOfNotNull<@Composable () -> Unit>(
            { ValueField("Merchant", existing.merchant ?: "—", onClick = null) },
            { ValueField("Date", dateTimeLabel(existing.occurredOn, null), onClick = null) },
            { ValueField("Paid with", state.duplicateAccount ?: "—", onClick = null) },
            state.duplicateCategory?.let { name -> { ValueField("Category", name, onClick = null) } },
        ),
    )
    Text("Attaching adds the photo and items to that expense — nothing is counted twice.", style = MaterialTheme.typography.bodySmall, color = Muted)
}

@Composable
private fun Review(state: ReceiptEntryState, onIntent: (ReceiptEntryIntent) -> Unit) {
    ReceiptCard(state, "Receipt photo", onIntent)
    InputCard(
        label = "Amount",
        value = state.amount,
        onValueChange = { onIntent(ReceiptEntryIntent.Amount(it)) },
        placeholder = "₹0.00",
        keyboardType = KeyboardType.Decimal,
        textStyle = AmountLarge.copy(color = Synapse),
    )
    if (state.choosingAccount) {
        AccountChooser(
            last4 = state.unknownLast4!!,
            detail = "From this receipt",
            accounts = state.accounts,
            selected = state.accountUid,
            onAdd = { onIntent(ReceiptEntryIntent.AddCard) },
            onPick = { onIntent(ReceiptEntryIntent.Account(it)) },
        )
        return
    }
    InputCard("Merchant", state.merchant, { onIntent(ReceiptEntryIntent.Merchant(it)) }, placeholder = "Who it was")
    var picking by rememberSaveable { mutableStateOf(false) }
    val noCategory = CategoryOption("", "None", "")
    FieldGroup(
        rows = listOf(
            { ValueField("Date", dateTimeLabel(state.date, state.time), onClick = { picking = true }) },
            {
                ChoiceField(
                    label = "Paid with",
                    value = state.selectedAccount?.name ?: "Pick one",
                    options = state.accounts,
                    optionLabel = { it.name },
                    optionDetail = { it.detail },
                    onPick = { onIntent(ReceiptEntryIntent.Account(it.uid)) },
                    valueColor = if (state.selectedAccount == null) Amber else null,
                    selected = { it.uid == state.accountUid },
                )
            },
            {
                ChoiceField(
                    label = "Category",
                    value = state.selectedCategory?.let { it.name + if (state.categorySuggested) " · suggested" else "" } ?: "Pick one",
                    options = listOf(noCategory) + state.categories,
                    optionLabel = { it.name },
                    onPick = { onIntent(ReceiptEntryIntent.Category(it.uid.ifEmpty { null })) },
                    placeholder = state.selectedCategory == null,
                    selected = { it.uid.ifEmpty { null } == state.categoryUid },
                )
            },
        ),
    )
    InputCard("Note", state.note, { onIntent(ReceiptEntryIntent.Note(it)) }, placeholder = "Add a note")
    CheckRow("Keep the receipt photo with this expense", state.keepPhoto, { onIntent(ReceiptEntryIntent.KeepPhoto(it)) })
    if (picking) {
        FinanceDatePicker(
            date = state.date,
            latest = state.today,
            onPick = {
                picking = false
                onIntent(ReceiptEntryIntent.Date(it))
            },
            onDismiss = { picking = false },
        )
    }
}

/** "Receipt photo · 5 items · ₹59 GST included · View" (Figma: Scan receipt 03). */
@Composable
private fun ReceiptCard(state: ReceiptEntryState, title: String, onIntent: (ReceiptEntryIntent) -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Panel).border(1.dp, Edge, shape).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PageImage(state.pages.firstOrNull(), Modifier.size(44.dp, 56.dp).clip(RoundedCornerShape(6.dp)), ContentScale.Crop)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = dev.kortex.design.Ink)
            Text(state.receiptSummary, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        if (state.pages.isNotEmpty()) {
            Text(
                "View",
                style = MaterialTheme.typography.labelLarge,
                color = Synapse,
                modifier = Modifier.clickable(role = Role.Button) { onIntent(ReceiptEntryIntent.ViewPhoto(true)) }.padding(8.dp),
            )
        }
    }
}

@Composable
private fun PhotoPanel(page: String) {
    val shape = RoundedCornerShape(16.dp)
    Box(Modifier.fillMaxWidth().height(200.dp).clip(shape).background(Void).border(1.dp, Edge, shape), contentAlignment = Alignment.Center) {
        PageImage(page, Modifier.fillMaxSize(), ContentScale.Fit)
    }
}

/** A scanned page, decoded off the main thread at a size that fits. */
@Composable
private fun PageImage(page: String?, modifier: Modifier, scale: ContentScale) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, page) {
        value = page?.let { withContext(Dispatchers.IO) { decode(context, Uri.parse(it)) } }
    }
    val image = bitmap
    if (image != null) {
        Image(image.asImageBitmap(), contentDescription = "Receipt photo", modifier = modifier, contentScale = scale)
    } else {
        Box(modifier.background(Panel))
    }
}

private fun decode(context: Context, uri: Uri, maxSide: Int = 1600): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
}.getOrNull()

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
