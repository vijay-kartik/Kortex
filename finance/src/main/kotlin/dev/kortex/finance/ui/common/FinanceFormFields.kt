package dev.kortex.finance.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kortex.design.Edge
import dev.kortex.design.Ink
import dev.kortex.design.InkSoft
import dev.kortex.design.Muted
import dev.kortex.design.Panel
import dev.kortex.design.Synapse
import dev.kortex.design.Void
import dev.kortex.design.Well
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private val GroupShape = RoundedCornerShape(16.dp)

/** Grouped field rows in one Well card, hairlines between (Figma: Recurring 02's field list). */
@Composable
fun FieldGroup(modifier: Modifier = Modifier, rows: List<@Composable () -> Unit>) {
    Column(modifier.fillMaxWidth().clip(GroupShape).background(Well).border(1.dp, Edge, GroupShape)) {
        rows.forEachIndexed { index, row ->
            if (index > 0) HorizontalDivider(color = Edge, thickness = 1.dp)
            row()
        }
    }
}

/** A label on the left and its value on the right; tapping it does [onClick]. */
@Composable
fun ValueField(label: String, value: String, onClick: (() -> Unit)?, placeholder: Boolean = false, valueColor: Color? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Muted)
        Spacer(Modifier.width(16.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            color = valueColor ?: if (placeholder) Muted else Ink,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** A field whose value is picked from [options] in a menu under it. */
@Composable
fun <T> ChoiceField(
    label: String,
    value: String,
    options: List<T>,
    optionLabel: (T) -> String,
    onPick: (T) -> Unit,
    placeholder: Boolean = false,
    valueColor: Color? = null,
    optionDetail: ((T) -> String?)? = null,
    selected: ((T) -> Boolean)? = null,
    footer: Pair<String, () -> Unit>? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        ValueField(label, value, onClick = { open = true }, placeholder = placeholder, valueColor = valueColor)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Panel, modifier = Modifier.align(Alignment.TopEnd)) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(optionLabel(option), style = MaterialTheme.typography.bodyLarge, color = if (selected?.invoke(option) == true) Synapse else Ink)
                            optionDetail?.invoke(option)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Muted) }
                        }
                    },
                    onClick = {
                        open = false
                        onPick(option)
                    },
                )
            }
            footer?.let { (text, action) ->
                DropdownMenuItem(
                    text = { Text(text, style = MaterialTheme.typography.labelLarge, color = Synapse) },
                    onClick = {
                        open = false
                        action()
                    },
                )
            }
        }
    }
}

/** A date field that opens the date picker; [latest] caps it (today for things already paid). */
@Composable
fun DateField(label: String, date: LocalDate, today: LocalDate, onPick: (LocalDate) -> Unit, latest: LocalDate? = null, earliest: LocalDate? = null) {
    var open by rememberSaveable { mutableStateOf(false) }
    // "Today, 29 Sep" or "Sat, 3 Oct 2026".
    val value = if (date == today) "Today, ${FinanceFormat.day(date)}" else "${FinanceFormat.weekdayDay(date)} ${date.year}"
    ValueField(label, value, onClick = { open = true })
    if (open) {
        FinanceDatePicker(
            date = date,
            earliest = earliest,
            latest = latest,
            onPick = {
                open = false
                onPick(it)
            },
            onDismiss = { open = false },
        )
    }
}

/** Material's date picker in Finance colours. It works in UTC midnights, so dates are converted at the edges. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinanceDatePicker(date: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit, earliest: LocalDate? = null, latest: LocalDate? = null) {
    fun LocalDate.utcMillis() = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val picker = rememberDatePickerState(
        initialSelectedDateMillis = date.utcMillis(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                val day = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
                return (earliest == null || !day.isBefore(earliest)) && (latest == null || !day.isAfter(latest))
            }
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onPick(picker.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() } ?: date)
            }) { Text("OK", color = Synapse) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) } },
    ) {
        DatePicker(state = picker)
    }
}

/** A checkbox with its explanation (Figma: Recurring 02, "Mark it paid automatically…"). */
@Composable
fun CheckRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(role = Role.Checkbox) { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            colors = CheckboxDefaults.colors(checkedColor = Synapse, uncheckedColor = Muted, checkmarkColor = Void),
            modifier = Modifier.padding(top = 2.dp, end = 12.dp),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, color = InkSoft)
    }
}
