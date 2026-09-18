package dev.kortex.myinfo.topics.domain.model

/** Something kept in a topic. Link-backed items carry the [SavedLink] they point at. */
sealed interface TopicItem {
    val id: Long
    val topicId: Long
    val addedAtMillis: Long
    val type: ItemType

    data class Note(
        override val id: Long,
        override val topicId: Long,
        override val addedAtMillis: Long,
        val text: String,
    ) : TopicItem {
        override val type get() = ItemType.Note
    }

    data class Link(
        override val id: Long,
        override val topicId: Long,
        override val addedAtMillis: Long,
        val link: SavedLink,
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
    ) : TopicItem {
        override val type get() = ItemType.Doc
    }

    data class Image(
        override val id: Long,
        override val topicId: Long,
        override val addedAtMillis: Long,
        val file: StoredFile,
        val caption: String?,
    ) : TopicItem {
        override val type get() = ItemType.Image
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
        is TopicItem.Note, is TopicItem.Link, is TopicItem.Doc, is TopicItem.Image -> null
    }

/** The file an item keeps in app storage, if any; a bill's is its invoice. */
val TopicItem.storedFile: StoredFile?
    get() = when (this) {
        is TopicItem.Doc -> file
        is TopicItem.Image -> file
        is TopicItem.Bill -> file
        is TopicItem.Note, is TopicItem.Link, is TopicItem.Article, is TopicItem.Video -> null
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
