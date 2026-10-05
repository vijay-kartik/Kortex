package dev.kortex.finance.ui.read

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.kortex.design.Amber
import dev.kortex.design.Edge
import dev.kortex.design.InkSoft
import dev.kortex.design.Muted
import dev.kortex.design.Panel
import dev.kortex.design.Synapse
import dev.kortex.design.Well
import dev.kortex.finance.domain.read.SmsField
import dev.kortex.finance.ui.common.RadioRow
import dev.kortex.finance.ui.common.SectionLabel
import dev.kortex.finance.ui.entry.AccountOption
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val CardShape = RoundedCornerShape(16.dp)

/**
 * The SMS as pasted, with the parts that were read underlined (Figma: Paste SMS 02): Synapse for
 * what matched, Amber for [attention] — a card that isn't yours, a UPI id with no name.
 */
@Composable
fun SmsCard(header: String, text: String, spans: Map<SmsField, IntRange>, attention: Set<SmsField> = emptySet(), modifier: Modifier = Modifier) {
    val ordered = spans.entries.filter { it.value.first >= 0 && it.value.last < text.length }.sortedBy { it.value.first }
    val styled = buildAnnotatedString {
        var at = 0
        for ((field, range) in ordered) {
            if (range.first < at) continue
            append(text.substring(at, range.first))
            withStyle(SpanStyle(color = if (field in attention) Amber else Synapse, textDecoration = TextDecoration.Underline)) {
                append(text.substring(range.first, range.last + 1))
            }
            at = range.last + 1
        }
        append(text.substring(at))
    }
    Column(modifier.fillMaxWidth().clip(CardShape).background(Panel).border(1.dp, Edge, CardShape).padding(16.dp)) {
        if (header.isNotEmpty()) {
            SectionLabel(header)
            Spacer(Modifier.height(8.dp))
        }
        Text(styled, style = MaterialTheme.typography.bodyMedium, color = InkSoft)
    }
}

/**
 * Paste SMS 08: the card or account in the message isn't one of yours. Add it from the message,
 * or pick how it was paid.
 */
@Composable
fun AccountChooser(
    last4: String,
    detail: String,
    accounts: List<AccountOption>,
    selected: String?,
    onAdd: () -> Unit,
    onPick: (String) -> Unit,
) {
    Column {
        SectionLabel("Paid with")
        Spacer(Modifier.height(6.dp))
        Text(
            buildAnnotatedString {
                append("Card ending ")
                withStyle(SpanStyle(color = Amber)) { append(last4) }
                append(" isn’t in your accounts. Pick how you paid, or add it.")
            },
            style = MaterialTheme.typography.bodyMedium,
            color = InkSoft,
        )
        Spacer(Modifier.height(10.dp))
        Column(Modifier.fillMaxWidth().clip(CardShape).background(Well).border(1.dp, Edge, CardShape)) {
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onAdd).padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("+", style = MaterialTheme.typography.titleMedium, color = Synapse)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Add card ending $last4", style = MaterialTheme.typography.bodyLarge, color = Synapse)
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }
            accounts.forEach { account ->
                HorizontalDivider(color = Edge)
                RadioRow(account.name, account.detail, account.uid == selected, { onPick(account.uid) })
            }
        }
    }
}

/** "28 Sep 2026, 6:42 PM", or the date alone when the time isn't known. */
fun dateTimeLabel(date: LocalDate, time: LocalTime?): String =
    date.format(DateFormat) + (time?.let { ", " + it.format(TimeFormat) } ?: "")

private val DateFormat = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
private val TimeFormat = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
