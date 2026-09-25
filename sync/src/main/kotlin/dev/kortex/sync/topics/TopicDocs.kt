package dev.kortex.sync.topics

import dev.kortex.myinfo.topics.data.local.DirtyTopic
import dev.kortex.myinfo.topics.data.local.RemoteTopic
import dev.kortex.myinfo.topics.data.local.RemoteTopicItem
import dev.kortex.myinfo.topics.data.local.RemoteTopicSummary
import dev.kortex.myinfo.topics.data.local.TopicItemEntity
import dev.kortex.sync.SERVER_UPDATED_AT

/**
 * A topic's document, `users/{uid}/topics/{topicUid}` (docs/CLOUD_SYNC_PLAN.md › Firestore layout).
 * The browser extension reads and writes the same fields, so renaming one breaks it.
 */
internal object TopicFields {
    const val NAME = "name"
    const val PURPOSE = "purpose"
    const val PINNED = "pinned"
    const val SECTIONS = "sections"
    const val CREATED_AT = "createdAt"
    const val UPDATED_AT = "updatedAt"
    const val DELETED = "deleted"
    /** A map of [SUMMARY_TEXT], [SUMMARY_GENERATED_AT] and [SUMMARY_FINGERPRINT], or null. */
    const val SUMMARY = "summary"
    const val SUMMARY_TEXT = "text"
    const val SUMMARY_GENERATED_AT = "generatedAt"
    const val SUMMARY_FINGERPRINT = "fingerprint"
}

/** An item's document, `users/{uid}/topicItems/{itemUid}`. Flat, so moving it to another topic is one field. */
internal object ItemFields {
    const val TOPIC_UID = "topicUid"
    const val TYPE = "type"
    const val ADDED_AT = "addedAt"
    const val TITLE = "title"
    const val TEXT = "text"
    const val LINK_UID = "linkUid"
    /**
     * A map of [FILE_DEVICE_PATH] and [FILE_MIME_TYPE], or null. Files aren't uploaded: the path
     * names the file on the device that added it, and on any other device nothing is there.
     */
    const val FILE = "file"
    const val FILE_DEVICE_PATH = "devicePath"
    const val FILE_MIME_TYPE = "mimeType"
    const val PAGE_COUNT = "pageCount"
    const val AMOUNT_MINOR = "amountMinor"
    const val CURRENCY = "currency"
    const val ISSUED_AT = "issuedAt"
    const val DUE_AT = "dueAt"
    const val DURATION_SECONDS = "durationSeconds"
    const val READING_MINUTES = "readingMinutes"
    const val DONE = "done"
    const val PINNED = "pinned"
    const val MESSAGE_ID = "messageId"
    const val THREAD_ID = "threadId"
    const val RFC822_MESSAGE_ID = "rfc822MessageId"
    const val FROM_ADDRESS = "fromAddress"
    const val ACCOUNT_EMAIL = "accountEmail"
    const val SENT_AT = "sentAt"
    const val UPDATED_AT = "updatedAt"
    const val DELETED = "deleted"
}

/** [serverTime] is `FieldValue.serverTimestamp()`; taken as a parameter so this stays testable. */
internal fun topicDoc(dirty: DirtyTopic, serverTime: Any): Map<String, Any?> {
    val topic = dirty.topic
    return mapOf(
        TopicFields.NAME to topic.name,
        TopicFields.PURPOSE to topic.purpose,
        TopicFields.PINNED to topic.pinned,
        TopicFields.SECTIONS to topic.sections.split(',').filter { it.isNotBlank() },
        TopicFields.CREATED_AT to topic.createdAtMillis,
        TopicFields.UPDATED_AT to topic.updatedAtMillis,
        // Written even when null, so a merge clears a summary the topic no longer has.
        TopicFields.SUMMARY to dirty.summary?.let {
            mapOf(
                TopicFields.SUMMARY_TEXT to it.text,
                TopicFields.SUMMARY_GENERATED_AT to it.generatedAtMillis,
                TopicFields.SUMMARY_FINGERPRINT to it.fingerprint,
            )
        },
        SERVER_UPDATED_AT to serverTime,
        TopicFields.DELETED to false,
    )
}

/**
 * [topicUid] and [linkUid] stand in for the local ids, which mean nothing on another device.
 * Every field is written, null or not, so a merge clears what the item no longer has.
 */
internal fun itemDoc(item: TopicItemEntity, topicUid: String, linkUid: String?, serverTime: Any): Map<String, Any?> = mapOf(
    ItemFields.TOPIC_UID to topicUid,
    ItemFields.TYPE to item.type,
    ItemFields.ADDED_AT to item.addedAtMillis,
    ItemFields.TITLE to item.title,
    ItemFields.TEXT to item.text,
    ItemFields.LINK_UID to linkUid,
    ItemFields.FILE to item.filePath?.let { path ->
        mapOf(ItemFields.FILE_DEVICE_PATH to path, ItemFields.FILE_MIME_TYPE to item.mimeType)
    },
    ItemFields.PAGE_COUNT to item.pageCount,
    ItemFields.AMOUNT_MINOR to item.amountMinor,
    ItemFields.CURRENCY to item.currency,
    ItemFields.ISSUED_AT to item.issuedAtMillis,
    ItemFields.DUE_AT to item.dueAtMillis,
    ItemFields.DURATION_SECONDS to item.durationSeconds,
    ItemFields.READING_MINUTES to item.readingMinutes,
    ItemFields.DONE to item.done,
    ItemFields.PINNED to item.pinned,
    ItemFields.MESSAGE_ID to item.messageId,
    ItemFields.THREAD_ID to item.threadId,
    ItemFields.RFC822_MESSAGE_ID to item.rfc822MessageId,
    ItemFields.FROM_ADDRESS to item.fromAddress,
    ItemFields.ACCOUNT_EMAIL to item.accountEmail,
    ItemFields.SENT_AT to item.sentAtMillis,
    ItemFields.UPDATED_AT to item.updatedAtMillis,
    SERVER_UPDATED_AT to serverTime,
    ItemFields.DELETED to false,
)

/** Merged over a topic's or an item's document; the rest of its fields stay for whoever still has it. */
internal fun deletedDoc(deletedAtMillis: Long, serverTime: Any): Map<String, Any?> = mapOf(
    // Topics and items name it the same.
    TopicFields.UPDATED_AT to deletedAtMillis,
    SERVER_UPDATED_AT to serverTime,
    TopicFields.DELETED to true,
)

/**
 * Reads a pulled topic document, or null when it can't be applied: no `updatedAt`, or a live
 * topic with no name. A deleted one needs neither, since only its uid is looked up.
 */
internal fun remoteTopic(uid: String, data: Map<String, Any?>): RemoteTopic? {
    val deleted = data[TopicFields.DELETED] as? Boolean ?: false
    val name = (data[TopicFields.NAME] as? String)?.trim()?.takeIf { it.isNotEmpty() }
    if (name == null && !deleted) return null
    val updatedAt = data.long(TopicFields.UPDATED_AT) ?: return null
    return RemoteTopic(
        uid = uid,
        name = name.orEmpty(),
        purpose = data.string(TopicFields.PURPOSE),
        pinned = data[TopicFields.PINNED] as? Boolean ?: false,
        sections = (data[TopicFields.SECTIONS] as? List<*>)?.filterIsInstance<String>().orEmpty(),
        createdAtMillis = data.long(TopicFields.CREATED_AT) ?: updatedAt,
        updatedAtMillis = updatedAt,
        summary = (data[TopicFields.SUMMARY] as? Map<*, *>)?.let { summary ->
            val text = summary[TopicFields.SUMMARY_TEXT] as? String ?: return@let null
            RemoteTopicSummary(
                text = text,
                generatedAtMillis = (summary[TopicFields.SUMMARY_GENERATED_AT] as? Number)?.toLong() ?: updatedAt,
                fingerprint = summary[TopicFields.SUMMARY_FINGERPRINT] as? String ?: "",
            )
        },
        deleted = deleted,
    )
}

/**
 * Reads a pulled item document, or null when it can't be applied: no `updatedAt`, or a live item
 * missing its topic, type or time added. A deleted one needs none of them.
 */
internal fun remoteTopicItem(uid: String, data: Map<String, Any?>): RemoteTopicItem? {
    val deleted = data[ItemFields.DELETED] as? Boolean ?: false
    val updatedAt = data.long(ItemFields.UPDATED_AT) ?: return null
    val topicUid = data.string(ItemFields.TOPIC_UID)
    val type = data.string(ItemFields.TYPE)
    val addedAt = data.long(ItemFields.ADDED_AT)
    if (!deleted && (topicUid == null || type == null || addedAt == null)) return null
    val file = data[ItemFields.FILE] as? Map<*, *>
    return RemoteTopicItem(
        uid = uid,
        topicUid = topicUid.orEmpty(),
        type = type.orEmpty(),
        addedAtMillis = addedAt ?: updatedAt,
        title = data.string(ItemFields.TITLE),
        text = data.string(ItemFields.TEXT),
        linkUid = data.string(ItemFields.LINK_UID),
        filePath = (file?.get(ItemFields.FILE_DEVICE_PATH) as? String)?.takeIf { it.isNotBlank() },
        mimeType = (file?.get(ItemFields.FILE_MIME_TYPE) as? String)?.takeIf { it.isNotBlank() },
        pageCount = data.long(ItemFields.PAGE_COUNT)?.toInt(),
        amountMinor = data.long(ItemFields.AMOUNT_MINOR),
        currency = data.string(ItemFields.CURRENCY),
        issuedAtMillis = data.long(ItemFields.ISSUED_AT),
        dueAtMillis = data.long(ItemFields.DUE_AT),
        durationSeconds = data.long(ItemFields.DURATION_SECONDS)?.toInt(),
        readingMinutes = data.long(ItemFields.READING_MINUTES)?.toInt(),
        done = data[ItemFields.DONE] as? Boolean ?: false,
        pinned = data[ItemFields.PINNED] as? Boolean ?: false,
        messageId = data.string(ItemFields.MESSAGE_ID),
        threadId = data.string(ItemFields.THREAD_ID),
        rfc822MessageId = data.string(ItemFields.RFC822_MESSAGE_ID),
        fromAddress = data.string(ItemFields.FROM_ADDRESS),
        accountEmail = data.string(ItemFields.ACCOUNT_EMAIL),
        sentAtMillis = data.long(ItemFields.SENT_AT),
        updatedAtMillis = updatedAt,
        deleted = deleted,
    )
}

// Numbers written from JavaScript can arrive as doubles.
private fun Map<String, Any?>.long(key: String): Long? = (this[key] as? Number)?.toLong()

private fun Map<String, Any?>.string(key: String): String? = (this[key] as? String)?.takeIf { it.isNotBlank() }
