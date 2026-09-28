package dev.kortex.links.ui.common

import dev.kortex.links.domain.model.Link
import dev.kortex.links.domain.model.linkDomain

/** The title, or the address's host when the page gave none. */
internal fun Link.displayTitle(): String = title.ifBlank { linkDomain(url) ?: url }

/** Compact, uppercase age: NOW, 5M AGO, 3H AGO, 3D AGO, 2W AGO, 4MO AGO, 1Y AGO. */
internal fun relativeAge(createdAtMillis: Long, nowMillis: Long): String {
    val minutes = (nowMillis - createdAtMillis).coerceAtLeast(0) / 60_000
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> "NOW"
        hours < 1 -> "${minutes}M AGO"
        days < 1 -> "${hours}H AGO"
        days < 7 -> "${days}D AGO"
        days < 30 -> "${days / 7}W AGO"
        days < 365 -> "${days / 30}MO AGO"
        else -> "${days / 365}Y AGO"
    }
}
