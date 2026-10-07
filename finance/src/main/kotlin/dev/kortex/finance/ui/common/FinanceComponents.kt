package dev.kortex.finance.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/** Big money figures: ₹24,850.12 on a tile, ₹3,180.50 on a hero card. */
val AmountLarge = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 34.sp, lineHeight = 40.sp)
val AmountMedium = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp)

/** Amounts in lists and charts, where digits should line up. */
val AmountMono = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp)

/** Bottom padding for a scrolling Finance tab, so its last card clears the floating bottom bar. */
val BottomBarClearance: Dp = 140.dp

private val CardShape = RoundedCornerShape(16.dp)

/** A raised Panel card with an Edge hairline, the building block of every Finance screen. */
@Composable
fun FinanceCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    highlighted: Boolean = false,
    padding: Dp = 16.dp,
    /** Overrides the hairline: Alarm for an overdue card bill. */
    borderColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(if (highlighted) SynapseDim else Panel)
            .border(1.dp, borderColor ?: if (highlighted) Synapse.copy(alpha = 0.45f) else Edge, CardShape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

/** Mono, tracked out, quiet: "TOTAL BALANCE", "WHERE IT WENT". */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = Muted) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = color, modifier = modifier)
}

/** Full-width accent pill at the foot of a screen or card. */
@Composable
fun PrimaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = Synapse) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp),
        color = if (enabled) Void else Muted,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (enabled) color else Sunken)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 14.dp),
    )
}

/** Outlined companion to [PrimaryButton]: Skip, Cancel, Enter manually. */
@Composable
fun SecondaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp),
        color = Ink,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Well)
            .border(1.dp, EdgeStrong, RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 14.dp),
    )
}

/** A row of the grouped field list (Figma: Paste SMS 02): label on the left, value on the right. */
data class FieldRow(
    val label: String,
    val value: String,
    val placeholder: Boolean = false,
    val valueColor: Color? = null,
    val onClick: (() -> Unit)? = null,
)

@Composable
fun FieldList(rows: List<FieldRow>, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(Well)
            .border(1.dp, Edge, CardShape),
    ) {
        rows.forEachIndexed { index, row ->
            if (index > 0) HorizontalDivider(color = Edge, thickness = 1.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (row.onClick != null) Modifier.clickable(role = Role.Button, onClick = row.onClick) else Modifier)
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(row.label, style = MaterialTheme.typography.bodyMedium, color = Muted)
                Spacer(Modifier.width(16.dp))
                Text(
                    row.value,
                    style = MaterialTheme.typography.bodyLarge,
                    color = row.valueColor ?: if (row.placeholder) Muted else Ink,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * A labelled input card (Figma: Add account fields): small label, the value as typed, an optional
 * tag on the right ("FROM SMS") and helper line underneath.
 */
@Composable
fun InputCard(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    helper: String? = null,
    tag: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    enabled: Boolean = true,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    /** An invalid value: the border and the helper turn Alarm. */
    error: Boolean = false,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(Well)
            .border(1.dp, if (error) Alarm else Edge, CardShape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.weight(1f))
            if (tag != null) SectionLabel(tag, color = Synapse)
        }
        Spacer(Modifier.height(6.dp))
        Box {
            if (value.isEmpty()) Text(placeholder, style = textStyle, color = Muted)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = textStyle.copy(color = if (enabled) Ink else Muted),
                cursorBrush = SolidColor(Synapse),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (helper != null) {
            Spacer(Modifier.height(4.dp))
            Text(helper, style = MaterialTheme.typography.bodySmall, color = if (error) Alarm else Muted)
        }
    }
}

/** A category or filter chip; [dashed] for the "+ New category" one. */
@Composable
fun FinanceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dashed: Boolean = false,
    dot: Color? = null,
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier
            .clip(shape)
            .background(if (selected) SynapseDim else if (dashed) Color.Transparent else Well)
            .then(
                when {
                    dashed -> Modifier.dashedBorder(EdgeStrong, 10.dp)
                    selected -> Modifier.border(BorderStroke(1.dp, Synapse.copy(alpha = 0.6f)), shape)
                    else -> Modifier.border(BorderStroke(1.dp, EdgeStrong), shape)
                },
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (dot != null) Box(Modifier.size(8.dp).background(dot, CircleShape))
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) Synapse else if (dashed) Muted else Ink)
    }
}

/** Two or three options side by side: Daily / Monthly / Yearly, Subscription / Fixed expense. */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Panel)
            .border(1.dp, Edge, shape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { index, option ->
            val active = index == selected
            Text(
                option,
                style = MaterialTheme.typography.labelLarge,
                color = if (active) Synapse else Muted,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (active) SynapseDim else Color.Transparent)
                    .then(if (active) Modifier.border(1.dp, Synapse.copy(alpha = 0.6f), RoundedCornerShape(10.dp)) else Modifier)
                    .clickable(role = Role.Tab) { onSelect(index) }
                    .padding(vertical = 9.dp),
            )
        }
    }
}

/** A radio choice inside a card (Figma: Which total?, Pay card bill, Delete category). */
@Composable
fun RadioRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(if (selected) SynapseDim.copy(alpha = 0.6f) else Color.Transparent)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(18.dp)
                .border(2.dp, if (selected) Synapse else Muted, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(8.dp).background(Synapse, CircleShape))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Ink)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
}

/** An outlined, tinted notice: amber for "Looks already added", Synapse for information. */
@Composable
fun NoticeCard(label: String, body: String, accent: Color, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(accent.copy(alpha = 0.08f))
            .border(1.dp, accent.copy(alpha = 0.45f), shape)
            .padding(14.dp),
    ) {
        SectionLabel(label, color = accent)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = InkSoft)
    }
}

/** A line of muted text with one part picked out in [highlight] colour: "You’ve spent ₹396 less than…". */
@Composable
fun InsightLine(before: String, highlight: String, after: String, highlightColor: Color, modifier: Modifier = Modifier) {
    Text(
        androidx.compose.ui.text.buildAnnotatedString {
            append(before)
            pushStyle(androidx.compose.ui.text.SpanStyle(color = highlightColor))
            append(highlight)
            pop()
            append(after)
        },
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp),
        color = InkSoft,
        modifier = modifier,
    )
}
