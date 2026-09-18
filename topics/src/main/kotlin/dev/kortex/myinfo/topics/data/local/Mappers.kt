package dev.kortex.myinfo.topics.data.local

import dev.kortex.myinfo.topics.domain.model.ItemType
import dev.kortex.myinfo.topics.domain.model.Money
import dev.kortex.myinfo.topics.domain.model.NewItem
import dev.kortex.myinfo.topics.domain.model.SavedLink
import dev.kortex.myinfo.topics.domain.model.StoredFile
import dev.kortex.myinfo.topics.domain.model.Topic
import dev.kortex.myinfo.topics.domain.model.TopicDraft
import dev.kortex.myinfo.topics.domain.model.TopicItem

internal fun TopicEntity.toDomain() = Topic(
    id = id,
    name = name,
    purpose = purpose,
    pinned = pinned,
    sections = sections.toItemTypes(),
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

internal fun TopicDraft.toEntity(nowMillis: Long) = TopicEntity(
    name = name,
    purpose = purpose,
    pinned = pinned,
    sections = sections.toColumn(),
    createdAtMillis = nowMillis,
    updatedAtMillis = nowMillis,
)

internal fun Set<ItemType>.toColumn(): String = sortedBy { it.ordinal }.joinToString(",") { it.name }

// Names this build doesn't know are skipped rather than failing the whole topic.
private fun String.toItemTypes(): Set<ItemType> =
    split(',').mapNotNullTo(LinkedHashSet()) { name -> ItemType.entries.firstOrNull { it.name == name } }

/** [linkId] must be set for link types; it is ignored for the rest. */
internal fun NewItem.toEntity(topicId: Long, linkId: Long?, nowMillis: Long): TopicItemEntity {
    val row = TopicItemEntity(topicId = topicId, type = type.name, addedAtMillis = nowMillis)
    return when (this) {
        is NewItem.Note -> row.copy(text = text)
        // The title belongs to the saved link, not the topic.
        is NewItem.Link -> row.copy(linkId = requireNotNull(linkId) { "A link item needs its saved link" })
        is NewItem.Doc -> row.copy(title = title, filePath = file.path, mimeType = file.mimeType, pageCount = pageCount)
        is NewItem.Image -> row.copy(text = caption, filePath = file.path, mimeType = file.mimeType)
        is NewItem.Bill -> row.copy(
            title = title,
            amountMinor = amount.minorUnits,
            currency = amount.currency,
            issuedAtMillis = issuedAtMillis,
            dueAtMillis = dueAtMillis,
            done = paid,
            filePath = file?.path,
            mimeType = file?.mimeType,
        )
    }
}

/**
 * Null when the row can't be shown: its link is no longer in [links], its type is unknown to this
 * build, or a column its type needs is missing.
 */
internal fun TopicItemEntity.toDomain(links: Map<Long, SavedLink>): TopicItem? {
    val type = ItemType.entries.firstOrNull { it.name == type } ?: return null
    val link = linkId?.let(links::get)
    val file = if (filePath != null && mimeType != null) StoredFile(filePath, mimeType) else null
    return when (type) {
        ItemType.Note -> TopicItem.Note(id, topicId, addedAtMillis, text ?: return null, pinned)
        ItemType.Link -> TopicItem.Link(id, topicId, addedAtMillis, link ?: return null, pinned)
        ItemType.Article -> TopicItem.Article(id, topicId, addedAtMillis, link ?: return null, readingMinutes, read = done, pinned = pinned)
        ItemType.Video -> TopicItem.Video(id, topicId, addedAtMillis, link ?: return null, durationSeconds, watched = done, pinned = pinned)
        ItemType.Doc -> TopicItem.Doc(id, topicId, addedAtMillis, title ?: return null, file ?: return null, pageCount, pinned)
        ItemType.Image -> TopicItem.Image(id, topicId, addedAtMillis, file ?: return null, caption = text, pinned = pinned)
        ItemType.Bill -> TopicItem.Bill(
            id = id,
            topicId = topicId,
            addedAtMillis = addedAtMillis,
            title = title ?: return null,
            amount = Money(amountMinor ?: return null, currency ?: return null),
            issuedAtMillis = issuedAtMillis,
            dueAtMillis = dueAtMillis,
            paid = done,
            file = file,
            pinned = pinned,
        )
    }
}
