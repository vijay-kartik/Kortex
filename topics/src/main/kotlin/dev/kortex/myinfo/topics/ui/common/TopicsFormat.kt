package dev.kortex.myinfo.topics.ui.common

import dev.kortex.myinfo.topics.domain.model.ItemType
import dev.kortex.myinfo.topics.domain.model.Money
import java.math.BigDecimal
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Currency
import java.util.Date
import java.util.Locale

/** UPDATED JUST NOW, UPDATED 3H AGO, UPDATED YESTERDAY… (see [ageLabel]). */
internal fun updatedLabel(updatedAtMillis: Long, nowMillis: Long): String = "UPDATED ${ageLabel(updatedAtMillis, nowMillis)}"

/** JUST NOW, 5M AGO, 3H AGO, YESTERDAY, 3D AGO, 2W AGO, 4MO AGO, 1Y AGO. */
internal fun ageLabel(atMillis: Long, nowMillis: Long): String {
    val minutes = (nowMillis - atMillis).coerceAtLeast(0) / 60_000
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> "JUST NOW"
        hours < 1 -> "${minutes}M AGO"
        days < 1 -> "${hours}H AGO"
        days < 2 -> "YESTERDAY"
        days < 7 -> "${days}D AGO"
        days < 30 -> "${days / 7}W AGO"
        days < 365 -> "${days / 30}MO AGO"
        else -> "${days / 365}Y AGO"
    }
}

/** 14:02, or 1:02:03 past an hour. */
internal fun formatDuration(seconds: Int): String {
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** "gov.uk" from "https://www.gov.uk/check-sponsor"; the address itself if it has no host. */
internal fun hostOf(url: String): String =
    runCatching { java.net.URI(url).host }.getOrNull()?.removePrefix("www.") ?: url

/** "9 links · 5 videos · 2 notes": most common first, ties in type order. */
internal fun countsLabel(counts: Map<ItemType, Int>): String =
    counts.entries
        .sortedWith(compareByDescending<Map.Entry<ItemType, Int>> { it.value }.thenBy { it.key.ordinal })
        .joinToString(" · ") { (type, count) -> "$count ${type.noun(count)}" }

internal fun ItemType.noun(count: Int): String {
    val singular = when (this) {
        ItemType.Note -> "note"
        ItemType.Link -> "link"
        ItemType.Article -> "article"
        ItemType.Video -> "video"
        ItemType.Doc -> "doc"
        ItemType.Image -> "image"
        ItemType.Bill -> "bill"
        ItemType.Email -> "email"
    }
    return if (count == 1) singular else "${singular}s"
}

/** "14 MAR", with the year once the date isn't in the twelve months around today. */
internal fun formatDate(atMillis: Long, nowMillis: Long, locale: Locale = Locale.getDefault()): String {
    val far = kotlin.math.abs(atMillis - nowMillis) > 365L * 24 * 3_600_000
    return SimpleDateFormat(if (far) "d MMM yyyy" else "d MMM", locale).format(Date(atMillis)).uppercase(locale)
}

/** "14 Mar 2025, 09:41": when an email was sent, in full, as its header reads. */
internal fun formatDateTime(atMillis: Long, locale: Locale = Locale.getDefault()): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", locale).format(Date(atMillis))

/** "840 B", "12 KB", "3.4 MB": a file's size, as an attachment row gives it. */
internal fun formatSize(bytes: Long): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_024 * 1_024 -> "${bytes / 1_024} KB"
    else -> "%.1f MB".format(bytes / (1_024.0 * 1_024.0))
}

/** "DUE IN 3 DAYS", "DUE TOMORROW", "DUE TODAY", "3 DAYS OVERDUE" — how a bill's date reads. */
internal fun dueLabel(dueAtMillis: Long, nowMillis: Long): String {
    // Whole days apart on the calendar, so "tomorrow" doesn't depend on the time of day.
    val days = ((startOfDay(dueAtMillis) - startOfDay(nowMillis)) / 86_400_000L).toInt()
    return when {
        days == 0 -> "DUE TODAY"
        days == 1 -> "DUE TOMORROW"
        days == -1 -> "1 DAY OVERDUE"
        days < -1 -> "${-days} DAYS OVERDUE"
        days < 30 -> "DUE IN $days DAYS"
        else -> "DUE ${formatDate(dueAtMillis, nowMillis)}"
    }
}

private fun startOfDay(atMillis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = atMillis
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** "AED 4,280", or "AED 4,280.50" when there are minor units to show. */
internal fun formatMoney(money: Money, locale: Locale = Locale.getDefault()): String {
    // Unknown codes fall back to two decimals; a few currencies report -1 (no minor unit).
    val digits = runCatching { Currency.getInstance(money.currency).defaultFractionDigits }.getOrDefault(2).coerceAtLeast(0)
    val amount = BigDecimal.valueOf(money.minorUnits, digits)
    val shownDigits = if (amount.stripTrailingZeros().scale() <= 0) 0 else digits
    val number = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = shownDigits
        maximumFractionDigits = shownDigits
    }
    return "${money.currency} ${number.format(amount)}"
}
