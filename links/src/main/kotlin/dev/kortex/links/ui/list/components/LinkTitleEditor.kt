package dev.kortex.links.ui.list.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kortex.design.Edge
import dev.kortex.design.Ink
import dev.kortex.design.Mono
import dev.kortex.design.Muted
import dev.kortex.design.Synapse
import dev.kortex.design.SynapseDim

/**
 * The open card's title field (Figma: Links / Rename · 2 Editing … 4 Cleared): one line, filled
 * with the current title and the cursor at its end. Left blank, the card shows [domain] instead,
 * which the placeholder and helper line say. Nothing is written until the editor closes.
 */
@Composable
internal fun LinkTitleEditor(
    /** The title to start from; later edits flow out through [onTitleChange]. */
    title: String,
    domain: String,
    edited: Boolean,
    onTitleChange: (String) -> Unit,
    onDone: () -> Unit,
) {
    // Held here, not just in the draft, so the cursor starts at the end of the title.
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(title, selection = TextRange(title.length)))
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val textStyle = MaterialTheme.typography.labelLarge

    Column {
        HorizontalDivider(thickness = 1.dp, color = Edge)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            EditorHeader(label = "TITLE", edited = edited, doneLabel = "Save title", onDone = onDone)
            BasicTextField(
                value = value,
                onValueChange = {
                    value = it
                    onTitleChange(it.text)
                },
                singleLine = true,
                textStyle = textStyle.copy(color = Ink),
                cursorBrush = SolidColor(Synapse),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                decorationBox = { innerTextField ->
                    Box(
                        Modifier
                            .clip(TitleFieldShape)
                            .background(SynapseDim)
                            .border(1.dp, Synapse, TitleFieldShape)
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                    ) {
                        if (value.text.isEmpty()) Text(domain, style = textStyle, color = Muted, maxLines = 1)
                        innerTextField()
                    }
                },
            )
            Text(
                "blank shows the domain · $domain",
                style = HelperStyle,
                color = Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val TitleFieldShape = RoundedCornerShape(8.dp)
private val HelperStyle = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp)
