package dev.kortex.myinfo.topics.domain.model

/** What kind of thing a topic item is. Also the unit of a topic's sections and type filters. */
enum class ItemType {
    Note,
    Link,
    Article,
    Video,
    Doc,
    Image,
    Bill;

    /** Backed by a link in the user's Links library rather than stored in the topic itself. */
    val isLink: Boolean get() = this == Link || this == Article || this == Video

    companion object {
        /** Preselected sections for a new topic (Figma: Topics 1g). */
        val DefaultSections: Set<ItemType> = setOf(Note, Image, Doc)
    }
}
