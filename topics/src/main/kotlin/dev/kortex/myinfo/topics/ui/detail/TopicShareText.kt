package dev.kortex.myinfo.topics.ui.detail

import dev.kortex.myinfo.topics.domain.model.TopicDetail
import dev.kortex.myinfo.topics.domain.model.TopicItem
import dev.kortex.myinfo.topics.ui.common.formatMoney

/**
 * A topic as plain text for the share sheet: name, purpose, then one line per item, oldest first
 * so it reads in the order things were collected. Files are named, not attached.
 */
internal fun topicShareText(detail: TopicDetail): String = buildString {
    appendLine(detail.topic.name)
    detail.topic.purpose?.let { appendLine(it) }
    if (detail.items.isNotEmpty()) appendLine()
    detail.items.asReversed().forEach { appendLine("• ${it.shareLine()}") }
}.trimEnd()

private fun TopicItem.shareLine(): String = when (this) {
    is TopicItem.Note -> text
    is TopicItem.Link -> "${link.title} — ${link.url}"
    is TopicItem.Article -> "${link.title} — ${link.url}"
    is TopicItem.Video -> "${link.title} — ${link.url}"
    is TopicItem.Doc -> title
    is TopicItem.Image -> caption ?: "Image"
    is TopicItem.Bill -> "$title — ${formatMoney(amount)}${if (paid) "" else " (due)"}"
    is TopicItem.Email -> "${email.subject} — from ${email.senderName}"
}
