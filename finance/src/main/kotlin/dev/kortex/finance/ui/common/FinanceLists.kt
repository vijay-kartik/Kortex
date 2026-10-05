package dev.kortex.finance.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kortex.design.Alarm
import dev.kortex.design.Amber
import dev.kortex.design.Edge
import dev.kortex.design.Ink
import dev.kortex.design.InkSoft
import dev.kortex.design.Muted
import dev.kortex.design.Panel
import dev.kortex.finance.domain.calc.DueUrgency

private val GroupShape = RoundedCornerShape(16.dp)

/** One line of a [BreakdownCard]: a coloured dot, what it is and how much. */
data class BreakdownLine(val label: String, val amountMinor: Long, val color: Color)

/**
 * The summary at the top of Pending payments and Recurring payments: a big figure, a split bar,
 * its parts, then [footer] rows under a hairline (the last one emphasised).
 */
@Composable
fun BreakdownCard(label: String, amountMinor: Long, lines: List<BreakdownLine>, footer: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    FinanceCard(modifier, padding = 20.dp) {
        SectionLabel(label)
        Spacer(Modifier.height(10.dp))
        Text(FinanceFormat.rupees(amountMinor, paise = false), style = AmountLarge, color = Ink)
        Spacer(Modifier.height(14.dp))
        SplitBar(lines.map { it.amountMinor to it.color })
        Spacer(Modifier.height(12.dp))
        lines.forEach { line ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(line.color, CircleShape))
                Spacer(Modifier.width(10.dp))
                Text(line.label, style = MaterialTheme.typography.bodyMedium, color = InkSoft, modifier = Modifier.weight(1f))
                Text(FinanceFormat.rupees(line.amountMinor, paise = false), style = AmountMono, color = Ink)
            }
        }
        if (footer.isNotEmpty()) {
            HorizontalDivider(color = Edge, modifier = Modifier.padding(vertical = 10.dp))
            footer.forEachIndexed { index, (name, value) ->
                val last = index == footer.lastIndex && footer.size > 1
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name,
                        style = if (last) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                        color = if (last) Ink else Muted,
                        modifier = Modifier.weight(1f),
                    )
                    Text(value, style = AmountMono.copy(fontWeight = if (last) FontWeight.SemiBold else FontWeight.Medium), color = if (last) Ink else Muted)
                }
            }
        }
    }
}

/** The filter chips under a summary: "All 5", "Cards 1"… */
@Composable
fun <T> FilterChips(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option -> FinanceChip(label(option), selected = option == selected, onClick = { onSelect(option) }) }
    }
}

/** A labelled group of rows in one card: "Next 7 days", "Subscriptions". */
@Composable
fun <T> RowGroup(label: String, rows: List<T>, key: (T) -> Any, modifier: Modifier = Modifier, row: @Composable (T) -> Unit) {
    Column(modifier) {
        SectionLabel(label)
        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth().clip(GroupShape).background(Panel).border(1.dp, Edge, GroupShape)) {
            rows.forEachIndexed { index, item ->
                androidx.compose.runtime.key(key(item)) {
                    if (index > 0) HorizontalDivider(color = Edge, modifier = Modifier.padding(horizontal = 16.dp))
                    row(item)
                }
            }
        }
    }
}

/** A payment in a [RowGroup]: name and detail on the left, amount and date on the right. */
@Composable
fun PaymentRow(
    title: String,
    subtitle: String,
    amountMinor: Long,
    date: String,
    urgency: DueUrgency,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitleColor: Color = Muted,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Panel)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = subtitleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(FinanceFormat.rupees(amountMinor, paise = false), style = AmountMono, color = Ink)
            Text(date, style = MaterialTheme.typography.bodySmall, color = urgencyColor(urgency))
        }
    }
}

fun urgencyColor(urgency: DueUrgency): Color = when (urgency) {
    DueUrgency.OVERDUE -> Alarm
    DueUrgency.SOON -> Amber
    DueUrgency.LATER -> Muted
}
