package dev.kortex.myinfo.topics.domain.model

/** Something kept in a topic. Link-backed items carry the [SavedLink] they point at. */
sealed interface TopicItem {
    val id: Long
    val topicId: Long
    val addedAtMillis: Long
    val type: ItemType

    /** Kept at the top of its topic, whichever way the feed is arranged (Figma: Topics 1e). */
    val pinned: Boolean

    data class Note(
        override val id: Long,
        override val topicId: Long,
        override val addedAtMillis: Long,
        val text: String,
        override val pinned: Boolean = false,
    ) : TopicItem {
        override val type get() = ItemType.Note
    }

    data class Link(
        override val id: Long,
        override val topicId: Long,
        override val addedAtMillis: Long,
        val link: SavedLink,
        override val pinned: Boolean = false,
    ) : TopicItem {
        override val type get() = ItemType.Link
    }

    data class Article(
        override val id: Long,
        override val topicId: Long,
        override val addedAtMillis: Long,
        val link: SavedLink,
        val readingMinutes: Int?,
        val read: Boolean,
        override val pinned: Boolean = false,
    ) : TopicItem {
        override val type get() = ItemType.Article
    }

    data class Video(
        override val id: Long,
        override val topicId: Long,
        override val addedAtMillis: Long,
        val link: SavedLink,
        val durationSeconds: Int?,
        val watched: Boolean,
        override val pinned: Boolean = false,
    ) : TopicItem {
        override val type get() = ItemType.Video
    }

    data class Doc(
        override val id: Long,
        override val topicId: Long,
        override val addedAtMillis: Long,
        val title: String,
        val file: StoredFile,
        val pageCount: Int?,
        override val pinned: Boolean = false,
    ) : TopicItem {
        override val type get() = ItemType.Doc
    }

    data class Image(
        override val id: Long,
        override val topicId: Long,
        override val addedAtMillis: Long,
        val file: StoredFile,
        val caption: String?,
        override val pinned: Boolean = false,
    ) : TopicItem {
        override val type get() = ItemType.Image
    }

    /**
     * A message in the user's mailbox. The topic keeps what it needs to show the mail and to
     * find it again; the mail itself stays where it is.
     */
    data class Email(
        override val id: Long,
        override val topicId: Long,
        override val addedAtMillis: Long,
        val email: SavedEmail,
        override val pinned: Boolean = false,
    ) : TopicItem {
        override val type get() = ItemType.Email
    }

    data class Bill(
        override val id: Long,
        override val topicId: Long,
        override val addedAtMillis: Long,
        val title: String,
        val amount: Money,
        val issuedAtMillis: Long?,
        val dueAtMillis: Long?,
        val paid: Boolean,
        /** The invoice or receipt, when one was attached. */
        val file: StoredFile?,
        override val pinned: Boolean = false,
    ) : TopicItem {
        override val type get() = ItemType.Bill
    }
}

/**
 * Whether the item is done with — an article read, a video watched, a bill paid. Null for the
 * types that are never "done": notes, links, docs and images.
 */
val TopicItem.done: Boolean?
    get() = when (this) {
        is TopicItem.Article -> read
        is TopicItem.Video -> watched
        is TopicItem.Bill -> paid
        is TopicItem.Note, is TopicItem.Link, is TopicItem.Doc, is TopicItem.Image, is TopicItem.Email -> null
    }

/** The file an item keeps in app storage, if any; a bill's is its invoice. */
val TopicItem.storedFile: StoredFile?
    get() = when (this) {
        is TopicItem.Doc -> file
        is TopicItem.Image -> file
        is TopicItem.Bill -> file
        is TopicItem.Note, is TopicItem.Link, is TopicItem.Article, is TopicItem.Video, is TopicItem.Email -> null
    }

/** A link in the user's Links library, as a topic shows it. */
data class SavedLink(
    val id: Long,
    val url: String,
    val title: String,
    /** Local thumbnail; null while none has downloaded or the user hid it. */
    val thumbnailPath: String?,
    /** The link's tags in the Links library; they seed suggested topics. */
    val tags: List<String> = emptyList(),
)

/**
 * An email a topic points at. [messageId] is the mail provider's own id, which the deep link
 * uses; [rfc822MessageId] is the `Message-ID` header, which identifies the same mail even if
 * the provider's id can't be used, so the mail can still be found by searching for it.
 */
data class SavedEmail(
    val messageId: String,
    val threadId: String?,
    val subject: String,
    /** The sender as the mail gives it: "Ada Lovelace <ada@example.com>" or just an address. */
    val from: String,
    val snippet: String,
    val sentAtMillis: Long?,
    val rfc822MessageId: String?,
    /** The mailbox it was read from, so the link opens the right account. */
    val accountEmail: String?,
) {
    /** "Ada Lovelace" from "Ada Lovelace <ada@example.com>"; the address when it has no name. */
    val senderName: String
        get() = from.substringBefore('<').trim().removeSurrounding("\"").ifBlank {
            from.substringAfter('<').substringBefore('>').trim().ifBlank { from }
        }
}

/** A file copied into app storage. */
data class StoredFile(
    val path: String,
    val mimeType: String,
)

/**
 * An amount in [currency]'s minor unit (cents, fils, paise), so sums stay exact.
 * [currency] is an ISO 4217 code.
 */
data class Money(
    val minorUnits: Long,
    val currency: String,
)
