package dev.kortex.myinfo.topics.ui.create

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kortex.design.Alarm
import dev.kortex.design.Edge
import dev.kortex.design.Grotesk
import dev.kortex.design.Ink
import dev.kortex.design.KortexTheme
import dev.kortex.design.Muted
import dev.kortex.design.Panel
import dev.kortex.design.Synapse
import dev.kortex.design.SynapseDim
import dev.kortex.design.Void
import dev.kortex.mvi.ObserveEffects
import dev.kortex.mvi.ScopedViewModelStore
import dev.kortex.myinfo.topics.domain.model.ItemType
import dev.kortex.myinfo.topics.ui.common.BodyStyle
import dev.kortex.myinfo.topics.ui.common.ChipStyle
import dev.kortex.myinfo.topics.ui.common.MetaStyle
import dev.kortex.myinfo.topics.ui.common.PrimaryButton
import dev.kortex.myinfo.topics.ui.common.RowTitleStyle
import dev.kortex.myinfo.topics.ui.common.SectionOrder
import dev.kortex.myinfo.topics.ui.common.noun

/**
 * Full-screen new-topic form. [onCreated] gets the new topic's id; [onClose] discards the form.
 * Every opening gets a fresh form: its ViewModel lives only as long as the route.
 */
@Composable
fun NewTopicRoute(
    onClose: () -> Unit,
    onCreated: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    ScopedViewModelStore(key = "new-topic") {
        NewTopicForm(onClose, onCreated, modifier, viewModel = hiltViewModel())
    }
}

/** Wires [NewTopicScreen] to its ViewModel; the text fields live here. */
@Composable
private fun NewTopicForm(
    onClose: () -> Unit,
    onCreated: (Long) -> Unit,
    modifier: Modifier,
    viewModel: NewTopicViewModel,
) {
    BackHandler(onBack = onClose)
    val state by viewModel.state.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf("") }
    var purpose by rememberSaveable { mutableStateOf("") }

    ObserveEffects(viewModel.effects) { effect ->
        when (effect) {
            is NewTopicEffect.Created -> onCreated(effect.topicId)
        }
    }

    NewTopicScreen(
        state = state,
        name = name,
        onNameChange = {
            name = it
            viewModel.onIntent(NewTopicIntent.NameEdited)
        },
        purpose = purpose,
        onPurposeChange = { purpose = it },
        onIntent = viewModel::onIntent,
        onClose = onClose,
        modifier = modifier,
    )
}

@Composable
fun NewTopicScreen(
    state: NewTopicState,
    name: String,
    onNameChange: (String) -> Unit,
    purpose: String,
    onPurposeChange: (String) -> Unit,
    onIntent: (NewTopicIntent) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val submit = { onIntent(NewTopicIntent.Submit(name, purpose)) }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { FormHeader(saveEnabled = !state.saving, onClose = onClose, onSave = submit) },
        bottomBar = {
            PrimaryButton(
                "Create topic",
                onClick = submit,
                enabled = !state.saving,
                modifier = Modifier
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 20.dp),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            NameField(name, onNameChange, state.nameError)
            PurposeField(purpose, onPurposeChange)
            SectionPicker(state.sections, onToggle = { onIntent(NewTopicIntent.ToggleSection(it)) })
            PinRow(state.pinned, onPinnedChange = { onIntent(NewTopicIntent.SetPinned(it)) })
        }
    }
}

@Composable
private fun FormHeader(saveEnabled: Boolean, onClose: () -> Unit, onSave: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Icon(
            Icons.Default.Close,
            contentDescription = "Close",
            tint = Muted,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .minimumInteractiveComponentSize()
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClick = onClose)
                .padding(12.dp)
                .size(20.dp),
        )
        Text(
            "NEW TOPIC",
            style = MetaStyle.copy(letterSpacing = 1.2.sp),
            color = Muted,
            modifier = Modifier.align(Alignment.Center),
        )
        Text(
            "SAVE",
            style = ChipStyle,
            color = if (saveEnabled) Synapse else Muted,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .minimumInteractiveComponentSize()
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = saveEnabled, role = Role.Button, onClick = onSave)
                .padding(12.dp),
        )
    }
}

@Composable
private fun NameField(name: String, onNameChange: (String) -> Unit, error: NameError?) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    // The form opens ready to type the name.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel("NAME")
        BasicTextField(
            value = name,
            onValueChange = onNameChange,
            singleLine = true,
            textStyle = NameStyle.copy(color = Ink),
            cursorBrush = SolidColor(Synapse),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
            decorationBox = { innerTextField ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .drawBehind {
                            val stroke = 1.dp.toPx()
                            drawLine(
                                color = if (error != null) Alarm else Synapse,
                                start = Offset(0f, size.height - stroke / 2),
                                end = Offset(size.width, size.height - stroke / 2),
                                strokeWidth = stroke,
                            )
                        }
                        .padding(bottom = 10.dp),
                ) {
                    if (name.isEmpty()) Text("e.g. Trip to Dubai", style = NameStyle, color = Muted)
                    innerTextField()
                }
            },
        )
        if (error != null) {
            Text(
                when (error) {
                    NameError.Blank -> "Give the topic a name."
                    NameError.Taken -> "You already have a topic with this name."
                },
                style = HintStyle,
                color = Alarm,
            )
        }
    }
}

@Composable
private fun PurposeField(purpose: String, onPurposeChange: (String) -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel("WHY YOU'RE KEEPING IT")
        BasicTextField(
            value = purpose,
            onValueChange = onPurposeChange,
            textStyle = RowTitleStyle.copy(color = Ink),
            cursorBrush = SolidColor(Synapse),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            interactionSource = interactionSource,
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { innerTextField ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 78.dp)
                        .clip(FieldShape)
                        .background(Panel)
                        .border(1.dp, if (focused) Synapse else Edge, FieldShape)
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                ) {
                    if (purpose.isEmpty()) {
                        Text("Optional — helps the agent summarise later", style = RowTitleStyle, color = Muted)
                    }
                    innerTextField()
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SectionPicker(sections: Set<ItemType>, onToggle: (ItemType) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        FieldLabel("SECTIONS TO SHOW")
        // No vertical spacing: each chip stands in a 48dp slot, which is gap enough.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionOrder.forEach { type ->
                SectionChip(type, selected = type in sections, onClick = { onToggle(type) })
            }
        }
        Text("Hidden sections appear automatically when you add that kind of item.", style = HintStyle, color = Muted)
    }
}

@Composable
private fun SectionChip(type: ItemType, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    val label = type.noun(2).uppercase()
    // A checkbox to TalkBack — "Notes, checkbox, checked" — rather than the caps and the tick.
    val spoken = type.noun(2).replaceFirstChar { it.titlecase() }
    Text(
        if (selected) "$label ✓" else label,
        style = MetaStyle.copy(letterSpacing = 0.sp),
        color = if (selected) Synapse else Muted,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .semantics { contentDescription = spoken }
            .clip(shape)
            .background(if (selected) SynapseDim else Panel)
            .border(1.dp, if (selected) Synapse else Edge, shape)
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onClick() })
            .padding(horizontal = 13.dp, vertical = 9.dp),
    )
}

@Composable
private fun PinRow(pinned: Boolean, onPinnedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(FieldShape)
            .background(Panel)
            .border(1.dp, Edge, FieldShape)
            // The whole row is one switch to TalkBack: its two lines, then "switch, on" or "off".
            .toggleable(value = pinned, role = Role.Switch, onValueChange = onPinnedChange)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Pin to top of Topics", style = RowTitleStyle, color = Ink)
            Text("Keeps it above Recent", style = HintStyle, color = Muted)
        }
        Switch(
            checked = pinned,
            // The row handles the tap, so the switch isn't a second target for it.
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Void,
                checkedTrackColor = Synapse,
                checkedBorderColor = Synapse,
                uncheckedThumbColor = Muted,
                uncheckedTrackColor = Void,
                uncheckedBorderColor = Edge,
            ),
        )
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = MetaStyle.copy(letterSpacing = 1.4.sp), color = Muted)
}

private val NameStyle = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp)
private val HintStyle = BodyStyle.copy(fontSize = 12.sp, lineHeight = 16.sp)
private val FieldShape = RoundedCornerShape(11.dp)

@Preview
@Composable
private fun NewTopicScreenPreview() {
    KortexTheme {
        NewTopicScreen(
            state = NewTopicState(pinned = true),
            name = "An incident to remember",
            onNameChange = {},
            purpose = "",
            onPurposeChange = {},
            onIntent = {},
            onClose = {},
        )
    }
}
