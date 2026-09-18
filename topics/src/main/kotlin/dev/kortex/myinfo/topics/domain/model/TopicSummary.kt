package dev.kortex.myinfo.topics.domain.model

import dev.kortex.myinfo.topics.domain.usecase.MoneyAmount
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId

/** The agent's short read of a topic (Figma: Topics 1b), as it was when it was written. */
data class TopicSummary(
    val topicId: Long,
    val text: String,
    val generatedAtMillis: Long,
    /** [SummaryDigest.fingerprint] of the topic the text was written from. */
    val fingerprint: String,
)

/**
 * A topic as the summarizer reads it: plain text, newest items first, bounded so a large topic
 * doesn't become a large prompt. [fingerprint] changes whenever anything in the text does, so a
 * summary written from an older digest is out of date — whatever changed and wherever it
 * changed from.
 */
data class SummaryDigest(
    val topicName: String,
    val purpose: String?,
    /** One line per item, newest first; see [of]. */
    val lines: List<String>,
    /** How many items the topic holds, which can be more than [lines] shows. */
    val itemCount: Int,
    val fingerprint: String,
) {
    val empty: Boolean get() = itemCount == 0

    companion object {
        /** The most items a digest reads; beyond this the newest are what matter. */
        const val MAX_ITEMS = 60

        /** Long notes are cut here; the start of a note says what it is about. */
        const val MAX_LINE = 240

        fun of(detail: TopicDetail, zone: ZoneId = ZoneId.systemDefault()): SummaryDigest {
            val topic = detail.topic
            val lines = detail.items
                .sortedWith(compareByDescending<TopicItem> { it.pinned }.thenByDescending { it.addedAtMillis })
                .take(MAX_ITEMS)
                .map { it.digestLine(zone).clipped() }
            val purpose = topic.purpose?.trim()?.takeIf { it.isNotEmpty() }
            val fingerprint = sha256(
                buildString {
                    append(topic.name).append('\n')
                    append(purpose.orEmpty()).append('\n')
                    append(detail.items.size).append('\n')
                    lines.forEach { append(it).append('\n') }
                },
            )
            return SummaryDigest(topic.name, purpose, lines, detail.items.size, fingerprint)
        }

        private fun String.clipped(): String =
            replace(WHITESPACE, " ").trim().let { if (it.length <= MAX_LINE) it else it.take(MAX_LINE - 1) + "…" }

        private fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray())
                .take(FINGERPRINT_BYTES)
                .joinToString("") { "%02x".format(it) }

        private const val FINGERPRINT_BYTES = 12
        private val WHITESPACE = Regex("""\s+""")
    }
}

/** What an item says about the topic, in a form a model reads without guessing. */
private fun TopicItem.digestLine(zone: ZoneId): String {
    val pin = if (pinned) " [pinned]" else ""
    return when (this) {
        is TopicItem.Note -> "Note$pin: $text"
        is TopicItem.Link -> "Link$pin: ${link.title.ifBlank { link.url }} (${link.url})"
        is TopicItem.Article -> "Article, ${if (read) "read" else "unread"}$pin: ${link.title.ifBlank { link.url }} (${link.url})"
        is TopicItem.Video -> "Video, ${if (watched) "watched" else "not watched"}$pin: ${link.title.ifBlank { link.url }} (${link.url})"
        is TopicItem.Doc -> "Document$pin: $title" + (pageCount?.let { if (it == 1) " (1 page)" else " ($it pages)" } ?: "")
        is TopicItem.Image -> "Image$pin: ${caption ?: "no caption"}"
        is TopicItem.Bill -> {
            val status = when {
                paid -> "paid"
                dueAtMillis != null -> "unpaid, due ${Instant.ofEpochMilli(dueAtMillis).atZone(zone).toLocalDate()}"
                else -> "unpaid"
            }
            "Bill$pin: $title — ${amount.plain()} — $status"
        }
    }
}

/** "AED 4280.50": the amount as a model should read it, without locale grouping. */
private fun Money.plain(): String =
    "$currency ${BigDecimal.valueOf(minorUnits, MoneyAmount.fractionDigits(currency)).toPlainString()}"
