package dev.kortex.myinfo.topics.ui.capture

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kortex.design.Alarm
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
import dev.kortex.myinfo.topics.ui.common.noun
import kotlinx.coroutines.launch

/**
 * Quick capture (Figma: Topics 1d): paste or type, keep or change the detected type, pick a topic
 * (preselected: [topicId]) or start one, save. [onSaved] gets the topic it went into.
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
    // Once the user types a title, the page's own no longer overwrites it.
    var titleEdited by rememberSaveable { mutableStateOf(false) }
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
        newTopicName = newTopicName,
        onNewTopicNameChange = {
            newTopicName = it
            viewModel.onIntent(QuickCaptureIntent.NewTopicNameEdited)
        },
        onIntent = viewModel::onIntent,
        onSave = { viewModel.onIntent(QuickCaptureIntent.Save(text, title, newTopicName)) },
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
    newTopicName: String,
    onNewTopicNameChange: (String) -> Unit,
    onIntent: (QuickCaptureIntent) -> Unit,
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
        Label("PASTED", Modifier.padding(top = 8.dp))
        PastedField(text, onTextChange)
        if (state.lookup?.inLinks == true && state.type.isLink) {
            Text("IN YOUR LINKS · THIS TOPIC WILL POINT AT IT", style = MetaStyle, color = Synapse)
        }

        if (!state.blank) {
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
                FlowRow(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    others.forEach { type -> TypeChip(type, selected = false, onClick = { onIntent(QuickCaptureIntent.ChooseType(type)) }) }
                }
            }
        }

        if (state.type.isLink && state.detection.url != null) {
            Label("TITLE", Modifier.padding(top = 8.dp))
            InputBox(
                value = title,
                onValueChange = onTitleChange,
                placeholder = if (state.lookingUp) "Reading the page…" else "Title (optional)",
                textStyle = RowTitleStyle,
                textColor = Ink,
                singleLine = true,
            )
        }

        Label("TOPIC", Modifier.padding(top = 6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
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
}

/** Where the text goes; offers the clipboard while it's empty. Focused as the sheet opens. */
@Composable
private fun PastedField(text: String, onTextChange: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    // hasText doesn't read the clip, so it doesn't trigger Android's "pasted from" notice.
    val canPaste = text.isEmpty() && clipboard.hasText()

    Box {
        InputBox(
            value = text,
            onValueChange = onTextChange,
            placeholder = "Paste a link, or write a note",
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
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button) { clipboard.getText()?.text?.let(onTextChange) }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
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
            capitalization = if (keyboardType == KeyboardType.Uri) KeyboardCapitalization.None else KeyboardCapitalization.Sentences,
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
    val shape = RoundedCornerShape(7.dp)
    Text(
        type.noun(1).uppercase(),
        style = MetaStyle.copy(letterSpacing = 0.sp),
        color = if (selected) Synapse else Muted,
        modifier = Modifier
            .clip(shape)
            .background(if (selected) SynapseDim else Well)
            .border(1.dp, if (selected) Synapse else Edge, shape)
            .clickable(enabled = !selected, role = Role.RadioButton, onClick = onClick)
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
            .clip(ChipShape)
            .background(if (selected) SynapseDim else Well)
            .border(1.dp, if (selected) Synapse else Edge, ChipShape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 9.dp),
    )
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

private fun errorText(error: CaptureError, state: QuickCaptureState): String = when (error) {
    CaptureError.AlreadyInTopic -> {
        val name = state.topics.firstOrNull { it.id == state.selectedTopicId }?.name ?: "this topic"
        "Already in $name."
    }
    CaptureError.NewTopicNameBlank -> "Name the new topic."
    CaptureError.NewTopicNameTaken -> "You already have a topic with this name — pick it above."
}

private val PastedStyle = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 17.sp)
private val ChipTextStyle = TextStyle(fontFamily = Grotesk, fontSize = 13.sp, lineHeight = 17.sp)
private val HintStyle = TextStyle(fontFamily = Grotesk, fontSize = 12.sp, lineHeight = 16.sp)
private val FieldShape = RoundedCornerShape(11.dp)
private val ChipShape = RoundedCornerShape(8.dp)
private val ButtonShape = RoundedCornerShape(11.dp)
