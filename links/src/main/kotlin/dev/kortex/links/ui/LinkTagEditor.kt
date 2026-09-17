package dev.kortex.links.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kortex.design.Edge
import dev.kortex.design.Ink
import dev.kortex.design.Mono
import dev.kortex.design.Muted
import dev.kortex.design.R
import dev.kortex.design.Synapse
import dev.kortex.design.SynapseDim
import dev.kortex.links.ui.components.NewTagChip
import dev.kortex.links.ui.components.TagChip

/**
 * The open card's tag editor (Figma: Links / Edit tags · 2 Editing, 3 New tag): every tag as a
 * toggle, the link's own selected, and "+ new tag" opening into a name field in place. Nothing is
 * written until the editor closes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LinkTagEditor(
    tags: List<String>,
    selectedTags: List<String>,
    edited: Boolean,
    addingTag: Boolean,
    newTagName: String,
    onTagToggle: (String) -> Unit,
    onStartNewTag: () -> Unit,
    onNewTagNameChange: (String) -> Unit,
    onAddTag: () -> Unit,
    onDone: () -> Unit,
) {
    Column {
        HorizontalDivider(thickness = 1.dp, color = Edge)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    buildAnnotatedString {
                        append("TAGS")
                        if (edited) withStyle(SpanStyle(color = Synapse)) { append(" · EDITED") }
                    },
                    style = TagsLabelStyle,
                    color = Muted,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "DONE",
                    style = LinkMetaStyle,
                    color = Synapse,
                    modifier = Modifier
                        .clip(DoneShape)
                        .border(1.dp, Synapse, DoneShape)
                        .clickable(onClickLabel = "Save tags", role = Role.Button, onClick = onDone)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tags.forEach { tag ->
                    TagChip(
                        text = tag,
                        selected = tag in selectedTags,
                        onSelectedChange = { onTagToggle(tag) },
                    )
                }
                if (addingTag) {
                    NewTagNameField(value = newTagName, onValueChange = onNewTagNameChange, onAdd = onAddTag)
                } else {
                    NewTagChip(onClick = onStartNewTag, color = Muted)
                }
            }
        }
    }
}

/** Sits in the chip row, sized to its text; takes focus on arrival so the keyboard comes up. */
@Composable
private fun NewTagNameField(value: String, onValueChange: (String) -> Unit, onAdd: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val textStyle = MaterialTheme.typography.labelLarge

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = textStyle.copy(color = Ink),
        cursorBrush = SolidColor(Synapse),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        // Adding an empty name just closes the field.
        keyboardActions = KeyboardActions(onDone = { onAdd() }),
        modifier = Modifier.focusRequester(focusRequester),
        decorationBox = { innerTextField ->
            Row(
                Modifier
                    .clip(TagFieldShape)
                    .background(SynapseDim)
                    .border(1.dp, Synapse, TagFieldShape)
                    .padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Box {
                    if (value.isEmpty()) Text("tag name", style = textStyle, color = Muted)
                    innerTextField()
                }
                Icon(
                    painterResource(R.drawable.ic_check_small),
                    contentDescription = "Add tag",
                    tint = Synapse,
                    modifier = Modifier
                        .size(20.dp)
                        .clip(TagFieldShape)
                        .clickable(role = Role.Button, onClick = onAdd),
                )
            }
        },
    )
}

private val DoneShape = RoundedCornerShape(12.dp)
private val TagFieldShape = RoundedCornerShape(8.dp)
private val TagsLabelStyle = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 1.5.sp)
