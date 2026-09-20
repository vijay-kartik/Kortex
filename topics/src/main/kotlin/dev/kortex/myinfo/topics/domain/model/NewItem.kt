package dev.kortex.myinfo.topics.domain.model

/** An item as the user submits it, before it is stored. */
sealed interface NewItem {
    val type: ItemType

    data class Note(val text: String) : NewItem {
        override val type get() = ItemType.Note
    }

    /**
     * A link, article or video. It goes into the Links library too, unless the address is
     * already saved there, in which case the topic points at that link instead.
     */
    data class Link(
        val url: String,
        val title: String? = null,
        override val type: ItemType = ItemType.Link,
    ) : NewItem {
        init {
            require(type.isLink) { "$type is not a link type" }
        }
    }

    data class Doc(val title: String, val file: StoredFile, val pageCount: Int? = null) : NewItem {
        override val type get() = ItemType.Doc
    }

    data class Image(val file: StoredFile, val caption: String? = null) : NewItem {
        override val type get() = ItemType.Image
    }

    /** An email the user picked from their mailbox; nothing is copied but what it takes to show it. */
    data class Email(val email: SavedEmail) : NewItem {
        override val type get() = ItemType.Email
    }

    data class Bill(
        val title: String,
        val amount: Money,
        val issuedAtMillis: Long? = null,
        val dueAtMillis: Long? = null,
        val paid: Boolean = false,
        val file: StoredFile? = null,
    ) : NewItem {
        override val type get() = ItemType.Bill
    }
}

/** Outcome of adding an item to a topic. */
sealed interface AddItemResult {
    data class Added(val itemId: Long) : AddItemResult

    /** Nothing valid to save: a blank note or title, or a link that isn't a web address. */
    data object Invalid : AddItemResult

    /** The topic already holds this link. */
    data object AlreadyInTopic : AddItemResult
}
