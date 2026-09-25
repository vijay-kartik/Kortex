package dev.kortex.sync.links

import dev.kortex.links.data.LinkWithTags
import dev.kortex.links.data.RemoteLink

/**
 * A link's document, `users/{uid}/links/{linkUid}` (docs/CLOUD_SYNC_PLAN.md › Firestore layout).
 * The browser extension reads and writes the same fields, so renaming one breaks it.
 */
internal object LinkFields {
    const val URL = "url"
    const val URL_KEY = "urlKey"
    const val TITLE = "title"
    const val TAGS = "tags"
    const val IMAGE_URL = "imageUrl"
    const val IMAGE_HIDDEN = "imageHidden"
    const val CREATED_AT = "createdAt"
    /** Client millis of the last change; the last writer wins on it. */
    const val UPDATED_AT = "updatedAt"
    /** Server time of the last write; pulls page through it. */
    const val SERVER_UPDATED_AT = "serverUpdatedAt"
    const val DELETED = "deleted"
}

/** [serverTime] is `FieldValue.serverTimestamp()`; taken as a parameter so this stays testable. */
internal fun linkDoc(link: LinkWithTags, serverTime: Any): Map<String, Any?> = mapOf(
    LinkFields.URL to link.link.url,
    LinkFields.URL_KEY to link.link.urlKey,
    LinkFields.TITLE to link.link.title,
    LinkFields.TAGS to link.tagNames,
    // Written even when null, so a merge clears an image the link no longer has.
    LinkFields.IMAGE_URL to link.link.imageUrl,
    LinkFields.IMAGE_HIDDEN to link.link.imageHidden,
    LinkFields.CREATED_AT to link.link.createdAtMillis,
    LinkFields.UPDATED_AT to link.link.updatedAtMillis,
    LinkFields.SERVER_UPDATED_AT to serverTime,
    LinkFields.DELETED to false,
)

/** Merged over the link's document, so the rest of its fields stay for whoever still has the link. */
internal fun deletedLinkDoc(deletedAtMillis: Long, serverTime: Any): Map<String, Any?> = mapOf(
    LinkFields.UPDATED_AT to deletedAtMillis,
    LinkFields.SERVER_UPDATED_AT to serverTime,
    LinkFields.DELETED to true,
)

/**
 * Reads a pulled document, or null when it can't be applied: no `updatedAt`, or a live link with
 * no address. A deleted one needs neither address nor title, since only its uid is looked up.
 */
internal fun remoteLink(uid: String, data: Map<String, Any?>): RemoteLink? {
    val deleted = data[LinkFields.DELETED] as? Boolean ?: false
    val url = (data[LinkFields.URL] as? String)?.trim()?.takeIf { it.isNotEmpty() }
    if (url == null && !deleted) return null
    val updatedAt = (data[LinkFields.UPDATED_AT] as? Number)?.toLong() ?: return null
    return RemoteLink(
        uid = uid,
        url = url.orEmpty(),
        title = (data[LinkFields.TITLE] as? String)?.takeIf { it.isNotBlank() } ?: url.orEmpty(),
        tags = (data[LinkFields.TAGS] as? List<*>)?.filterIsInstance<String>().orEmpty(),
        imageUrl = (data[LinkFields.IMAGE_URL] as? String)?.takeIf { it.isNotBlank() },
        imageHidden = data[LinkFields.IMAGE_HIDDEN] as? Boolean ?: false,
        createdAtMillis = (data[LinkFields.CREATED_AT] as? Number)?.toLong() ?: updatedAt,
        updatedAtMillis = updatedAt,
        deleted = deleted,
    )
}
