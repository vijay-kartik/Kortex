package dev.kortex.myinfo.topics.ui.capture

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kortex.design.Alarm
import dev.kortex.design.Amber
import dev.kortex.design.Edge
import dev.kortex.design.EdgeStrong
import dev.kortex.design.Grotesk
import dev.kortex.design.Ink
import dev.kortex.design.InkSoft
import dev.kortex.design.Mono
import dev.kortex.design.Muted
import dev.kortex.design.Panel
import dev.kortex.design.Sunken
import dev.kortex.design.Synapse
import dev.kortex.design.SynapseDim
import dev.kortex.design.Void
import dev.kortex.design.Well
import dev.kortex.design.dashedBorder
import dev.kortex.mvi.ObserveEffects
import dev.kortex.mvi.ScopedViewModelStore
import dev.kortex.myinfo.topics.domain.model.ItemType
import dev.kortex.myinfo.topics.ui.common.MetaStyle
import dev.kortex.myinfo.topics.ui.common.RowTitleStyle
import dev.kortex.myinfo.topics.ui.common.formatDate
import dev.kortex.myinfo.topics.ui.common.noun
import dev.kortex.myinfo.topics.ui.common.spokenName
import kotlinx.coroutines.launch

/**
 * Quick capture (Figma: Topics 1d): paste or type, or attach a file; keep or change the detected
 * type, fill in a bill's amount and date if that's what it is, pick a topic (preselected:
 * [topicId]) or start one, save. [onSaved] gets the topic it went into.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickCaptureSheet(
    topicId: Long,
    onDismiss: () -> Unit,
    onSaved: (topicId: Long, topicName: String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // Slide the sheet away before reporting, so the caller removes it only once it's gone.
    fun hideThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { action() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Panel,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 17.dp, bottom = 8.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(EdgeStrong),
            )
        },
    ) {
        ScopedViewModelStore(key = "quick-capture") {
            val viewModel = hiltViewModel<QuickCaptureViewModel, QuickCaptureViewModel.Factory>(
                creationCallback = { factory -> factory.create(topicId) },
            )
            QuickCaptureForm(
                viewModel = viewModel,
                onCancel = { hideThen(onDismiss) },
                onSaved = { id, name -> hideThen { onSaved(id, name) } },
            )
        }
    }
}

@Composable
private fun QuickCaptureForm(
    viewModel: QuickCaptureViewModel,
    onCancel: () -> Unit,
    onSaved: (Long, String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    // Once the user types a title, neither the page's nor the file's name overwrites it.
    var titleEdited by rememberSaveable { mutableStateOf(false) }
    var billAmount by rememberSaveable { mutableStateOf("") }
    var newTopicName by rememberSaveable { mutableStateOf("") }

    ObserveEffects(viewModel.effects) { effect ->
        when (effect) {
            is QuickCaptureEffect.Saved -> onSaved(effect.topicId, effect.topicName)
        }
    }
    LaunchedEffect(state.lookup) {
        val pageTitle = state.lookup?.title
        if (!titleEdited && pageTitle != null) title = pageTitle
    }
    LaunchedEffect(state.file) {
        // A doc is almost always kept under the name it arrived with; an image rarely is.
        val name = state.file?.takeUnless { it.isImage }?.name
        if (!titleEdited && name != null) title = name
    }

    val pickDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.onIntent(QuickCaptureIntent.AttachFile(it.toString())) }
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { viewModel.onIntent(QuickCaptureIntent.AttachFile(it.toString())) }
    }

    QuickCaptureContent(
        state = state,
        text = text,
        onTextChange = {
            text = it
            viewModel.onIntent(QuickCaptureIntent.TextChanged(it))
        },
        title = title,
        onTitleChange = {
            title = it
            titleEdited = true
        },
        billAmount = billAmount,
        onBillAmountChange = {
            billAmount = it
            viewModel.onIntent(QuickCaptureIntent.BillAmountEdited)
        },
        newTopicName = newTopicName,
        onNewTopicNameChange = {
            newTopicName = it
            viewModel.onIntent(QuickCaptureIntent.NewTopicNameEdited)
        },
        onIntent = viewModel::onIntent,
        onPickDocument = { pickDocument.launch(arrayOf(ANY_MIME)) },
        onPickImage = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onSave = { viewModel.onIntent(QuickCaptureIntent.Save(text, title, newTopicName, billAmount)) },
        onCancel = onCancel,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuickCaptureContent(
    state: QuickCaptureState,
    text: String,
    onTextChange: (String) -> Unit,
    title: String,
    onTitleChange: (String) -> Unit,
    billAmount: String,
    onBillAmountChange: (String) -> Unit,
    newTopicName: String,
    onNewTopicNameChange: (String) -> Unit,
    onIntent: (QuickCaptureIntent) -> Unit,
    onPickDocument: () -> Unit,
    onPickImage: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 18.dp, end = 18.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.fileMode) {
            Label("FILE", Modifier.padding(top = 8.dp))
            AttachedFile(state, onRemove = { onIntent(QuickCaptureIntent.RemoveFile) })
        } else {
            Label(if (state.type == ItemType.Bill) "BILL" else "PASTED", Modifier.padding(top = 8.dp))
            PastedField(text, onTextChange, placeholder = pastedPlaceholder(state))
            if (state.lookup?.inLinks == true && state.type.isLink) {
                Text("IN YOUR LINKS · THIS TOPIC WILL POINT AT IT", style = MetaStyle, color = Synapse)
            }
        }

        AttachRow(fileMode = state.fileMode, onPickDocument = onPickDocument, onPickImage = onPickImage)

        if (state.hasContent) {
            Row(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TypeChip(state.type, selected = true, onClick = {})
                Text(typeHint(state), style = HintStyle, color = Muted)
            }
            val others = state.types - state.type
            if (others.isNotEmpty()) {
                // No vertical spacing: each chip already stands in a 48dp slot, which is gap enough.
                FlowRow(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    others.forEach { type -> TypeChip(type, selected = false, onClick = { onIntent(QuickCaptureIntent.ChooseType(type)) }) }
                }
            }
        }

        if (state.showTitleField) {
            Label(if (state.type == ItemType.Image) "CAPTION" else "TITLE", Modifier.padding(top = 8.dp))
            InputBox(
                value = title,
                onValueChange = onTitleChange,
                placeholder = titlePlaceholder(state),
                textStyle = RowTitleStyle,
                textColor = Ink,
                singleLine = true,
            )
        }

        if (state.showBillFields) {
            BillFieldsBlock(
                state = state,
                amount = billAmount,
                onAmountChange = onBillAmountChange,
                onIntent = onIntent,
            )
        }

        Label("TOPIC", Modifier.padding(top = 6.dp))
        FlowRow(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            state.topics.forEach { topic ->
                TopicChip(
                    topic.name,
                    selected = !state.creatingTopic && topic.id == state.selectedTopicId,
                    onClick = { onIntent(QuickCaptureIntent.SelectTopic(topic.id)) },
                )
            }
            if (state.creatingTopic) {
                NewTopicField(newTopicName, onNewTopicNameChange)
            } else {
                Text(
                    "+ New topic",
                    style = ChipTextStyle,
                    color = Muted,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clip(ChipShape)
                        .background(Well)
                        .dashedBorder(EdgeStrong, cornerRadius = 8.dp)
                        .clickable(role = Role.Button) { onIntent(QuickCaptureIntent.StartNewTopic) }
                        .padding(horizontal = 13.dp, vertical = 9.dp),
                )
            }
        }

        state.error?.let { error ->
            Text(errorText(error, state), style = HintStyle, color = Alarm)
        }

        Row(
            modifier = Modifier.padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text(
                "Cancel",
                style = RowTitleStyle,
                color = Muted,
                modifier = Modifier
                    .clip(ButtonShape)
                    .border(1.dp, Edge, ButtonShape)
                    .clickable(role = Role.Button, onClick = onCancel)
                    .padding(horizontal = 19.dp, vertical = 14.dp),
            )
            Text(
                "Save to topic",
                style = RowTitleStyle.copy(fontWeight = FontWeight.Medium),
                color = if (state.canSave) Void else Muted,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(ButtonShape)
                    .background(if (state.canSave) Synapse else Sunken)
                    .clickable(enabled = state.canSave, role = Role.Button, onClick = onSave)
                    .padding(vertical = 14.dp),
            )
        }
    }

    if (state.pickingDueDate) {
        DueDatePicker(
            selected = state.billDueAtMillis,
            onPick = { onIntent(QuickCaptureIntent.SetDueDate(it)) },
            onDismiss = { onIntent(QuickCaptureIntent.CloseDueDate) },
        )
    }
}

/** What the sheet is holding instead of pasted text, and the way back out of it. */
@Composable
private fun AttachedFile(state: QuickCaptureState, onRemove: () -> Unit) {
    // No vertical padding: REMOVE stands in a 48dp slot, which sets the row's height.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(FieldShape)
            .background(Well)
            .border(1.dp, Edge, FieldShape)
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val file = state.file
        Text(
            if (file == null) "Keeping the file…" else file.name,
            style = RowTitleStyle,
            color = if (file == null) Muted else InkSoft,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (file != null) {
            Text(
                "REMOVE",
                style = MetaStyle,
                color = Muted,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .semantics { contentDescription = "Remove file" }
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button, onClick = onRemove)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

/** The two ways in from storage: any file as a doc, or a picture from the photo picker. */
@Composable
private fun AttachRow(fileMode: Boolean, onPickDocument: () -> Unit, onPickImage: () -> Unit) {
    Row(
        modifier = Modifier.padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        AttachButton(if (fileMode) "Choose another" else "+ Attach file", onPickDocument)
        AttachButton(if (fileMode) "Another photo" else "+ Photo", onPickImage)
    }
}

@Composable
private fun AttachButton(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = ChipTextStyle,
        color = Muted,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(ChipShape)
            .background(Well)
            .dashedBorder(EdgeStrong, cornerRadius = 8.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 9.dp),
    )
}

/** A bill's own fields: what it costs, when it's due, and whether it's been paid already. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BillFieldsBlock(
    state: QuickCaptureState,
    amount: String,
    onAmountChange: (String) -> Unit,
    onIntent: (QuickCaptureIntent) -> Unit,
) {
    val nowMillis = remember { System.currentTimeMillis() }
    Label("AMOUNT", Modifier.padding(top = 8.dp))
    FlowRow(
        modifier = Modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        QuickCaptureViewModel.currencyChoices(state.billCurrency).forEach { code ->
            SmallChip(
                label = code,
                selected = code == state.billCurrency,
                accent = Amber,
                role = Role.RadioButton,
                onClick = { onIntent(QuickCaptureIntent.ChooseCurrency(code)) },
            )
        }
    }
    InputBox(
        value = amount,
        onValueChange = onAmountChange,
        placeholder = "0",
        textStyle = AmountStyle,
        textColor = Ink,
        singleLine = true,
        keyboardType = KeyboardType.Decimal,
    )
    Row(
        modifier = Modifier.padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SmallChip(
            label = state.billDueAtMillis?.let { "DUE ${formatDate(it, nowMillis)}" } ?: "SET DUE DATE",
            selected = state.billDueAtMillis != null,
            accent = Amber,
            onClick = { onIntent(QuickCaptureIntent.OpenDueDate) },
        )
        if (state.billDueAtMillis != null) {
            Text(
                "CLEAR",
                style = MetaStyle,
                color = Muted,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .semantics { contentDescription = "Clear due date" }
                    .clip(RoundedCornerShape(7.dp))
                    .clickable(role = Role.Button) { onIntent(QuickCaptureIntent.SetDueDate(null)) }
                    .padding(horizontal = 7.dp, vertical = 7.dp),
            )
        }
        SmallChip(
            label = if (state.billPaid) "PAID ✓" else "MARK PAID",
            selected = state.billPaid,
            accent = Amber,
            role = Role.Checkbox,
            // "Paid, checkbox, checked" — the caps and the tick are for the eye.
            spokenLabel = "Paid",
            onClick = { onIntent(QuickCaptureIntent.SetPaid(!state.billPaid)) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DueDatePicker(selected: Long?, onPick: (Long?) -> Unit, onDismiss: () -> Unit) {
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = selected)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onPick(pickerState.selectedDateMillis) }) { Text("Set", color = Synapse) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) }
        },
        colors = DatePickerDefaults.colors(containerColor = Panel),
    ) {
        DatePicker(state = pickerState, colors = DatePickerDefaults.colors(containerColor = Panel))
    }
}

/** Where the text goes; offers the clipboard while it's empty. Focused as the sheet opens. */
@Composable
private fun PastedField(text: String, onTextChange: (String) -> Unit, placeholder: String) {
    val clipboard = LocalClipboardManager.current
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    // hasText doesn't read the clip, so it doesn't trigger Android's "pasted from" notice.
    val canPaste = text.isEmpty() && clipboard.hasText()

    Box {
        InputBox(
            value = text,
            onValueChange = onTextChange,
            placeholder = placeholder,
            textStyle = PastedStyle,
            textColor = InkSoft,
            singleLine = false,
            keyboardType = KeyboardType.Uri,
            modifier = Modifier.focusRequester(focusRequester),
            trailingSpace = canPaste,
        )
        if (canPaste) {
            Text(
                "PASTE",
                style = MetaStyle,
                color = Synapse,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 6.dp)
                    .semantics { contentDescription = "Paste from clipboard" }
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button) { clipboard.getText()?.text?.let(onTextChange) }
                    // As tall as the field it sits in, so the whole right end of it pastes.
                    .padding(horizontal = 8.dp, vertical = 13.dp),
            )
        }
    }
}

@Composable
private fun InputBox(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    textStyle: TextStyle,
    textColor: Color,
    singleLine: Boolean,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    trailingSpace: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        maxLines = if (singleLine) 1 else 4,
        textStyle = textStyle.copy(color = textColor),
        cursorBrush = SolidColor(Synapse),
        keyboardOptions = KeyboardOptions(
            capitalization = if (keyboardType == KeyboardType.Text) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
            keyboardType = keyboardType,
            imeAction = if (singleLine) ImeAction.Done else ImeAction.Default,
        ),
        interactionSource = interactionSource,
        modifier = modifier.fillMaxWidth(),
        decorationBox = { innerTextField ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(FieldShape)
                    .background(Well)
                    .border(1.dp, if (focused) Synapse else Edge, FieldShape)
                    .padding(start = 14.dp, end = if (trailingSpace) 72.dp else 14.dp, top = 13.dp, bottom = 13.dp),
            ) {
                if (value.isEmpty()) Text(placeholder, style = textStyle, color = Muted, maxLines = 1)
                innerTextField()
            }
        },
    )
}

/** The chip for a new topic's name: typed into in place, focused as it appears. */
@Composable
private fun NewTopicField(name: String, onNameChange: (String) -> Unit) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    BasicTextField(
        value = name,
        onValueChange = onNameChange,
        singleLine = true,
        textStyle = ChipTextStyle.copy(color = Synapse),
        cursorBrush = SolidColor(Synapse),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        modifier = Modifier
            .widthIn(min = 120.dp)
            .focusRequester(focusRequester),
        decorationBox = { innerTextField ->
            Box(
                Modifier
                    .clip(ChipShape)
                    .background(SynapseDim)
                    .border(1.dp, Synapse, ChipShape)
                    .padding(horizontal = 13.dp, vertical = 9.dp),
            ) {
                if (name.isEmpty()) Text("New topic name", style = ChipTextStyle, color = Muted)
                innerTextField()
            }
        },
    )
}

@Composable
private fun TypeChip(type: ItemType, selected: Boolean, onClick: () -> Unit) {
    SmallChip(
        label = type.noun(1).uppercase(),
        selected = selected,
        accent = Synapse,
        role = Role.RadioButton,
        spokenLabel = type.spokenName,
        onClick = onClick,
    )
}

/**
 * The uppercase pill the sheet picks everything with: a type, a currency, a date, paid or not.
 * [role] says what kind of pick it is, and screen readers hear it that way — a radio button
 * reports whether it's the one chosen, a checkbox whether it's ticked. [spokenLabel] replaces a
 * label written for the eye (caps, a tick) with one written for the ear.
 */
@Composable
private fun SmallChip(
    label: String,
    selected: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    role: Role = Role.Button,
    spokenLabel: String? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(7.dp)
    Text(
        label,
        style = MetaStyle.copy(letterSpacing = 0.sp),
        color = if (selected) accent else Muted,
        maxLines = 1,
        modifier = modifier
            .minimumInteractiveComponentSize()
            .then(if (spokenLabel != null) Modifier.semantics { contentDescription = spokenLabel } else Modifier)
            .clip(shape)
            .background(if (selected) SynapseDim else Well)
            .border(1.dp, if (selected) accent else Edge, shape)
            .then(pickModifier(selected, role, onClick))
            .padding(horizontal = 11.dp, vertical = 7.dp),
    )
}

@Composable
private fun TopicChip(name: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        if (selected) "$name ✓" else name,
        style = ChipTextStyle,
        color = if (selected) Synapse else Muted,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            // The tick is for the eye; a radio button already says it's the one chosen.
            .semantics { contentDescription = name }
            .clip(ChipShape)
            .background(if (selected) SynapseDim else Well)
            .border(1.dp, if (selected) Synapse else Edge, ChipShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 9.dp),
    )
}

/** Tap handling that reports the right state for the kind of pick [role] is. */
private fun pickModifier(selected: Boolean, role: Role, onClick: () -> Unit): Modifier = when (role) {
    Role.RadioButton, Role.Tab -> Modifier.selectable(selected = selected, role = role, onClick = onClick)
    Role.Checkbox, Role.Switch -> Modifier.toggleable(value = selected, role = role, onValueChange = { onClick() })
    else -> Modifier.clickable(role = role, onClick = onClick)
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MetaStyle.copy(letterSpacing = 1.4.sp), color = Muted, modifier = modifier)
}

private fun typeHint(state: QuickCaptureState): String = when {
    state.types.size == 1 -> "saved as a note"
    state.typeIsDetected -> "detected — tap to change"
    else -> "tap to change"
}

/** Without a file, a bill has no title field of its own, so this field is where its name goes. */
private fun pastedPlaceholder(state: QuickCaptureState): String =
    if (state.type == ItemType.Bill) "What is this bill for?" else "Paste a link, or write a note"

private fun titlePlaceholder(state: QuickCaptureState): String = when {
    state.type == ItemType.Image -> "Caption (optional)"
    state.type == ItemType.Bill -> "What is this bill for?"
    state.lookingUp -> "Reading the page…"
    else -> "Title (optional)"
}

private fun errorText(error: CaptureError, state: QuickCaptureState): String = when (error) {
    CaptureError.AlreadyInTopic -> {
        val name = state.topics.firstOrNull { it.id == state.selectedTopicId }?.name ?: "this topic"
        "Already in $name."
    }
    CaptureError.BillTitleBlank -> "Say what the bill is for."
    CaptureError.BillAmountInvalid -> "Type the amount as a number, like 128.50."
    CaptureError.NewTopicNameBlank -> "Name the new topic."
    CaptureError.NewTopicNameTaken -> "You already have a topic with this name — pick it above."
    CaptureError.FileUnreadable -> "That file couldn't be read. Try another."
}

private const val ANY_MIME = "*/*"

private val PastedStyle = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 17.sp)
private val AmountStyle = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 22.sp)
private val ChipTextStyle = TextStyle(fontFamily = Grotesk, fontSize = 13.sp, lineHeight = 17.sp)
private val HintStyle = TextStyle(fontFamily = Grotesk, fontSize = 12.sp, lineHeight = 16.sp)
private val FieldShape = RoundedCornerShape(11.dp)
private val ChipShape = RoundedCornerShape(8.dp)
private val ButtonShape = RoundedCornerShape(11.dp)
