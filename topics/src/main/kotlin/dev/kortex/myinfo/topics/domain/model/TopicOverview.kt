package dev.kortex.myinfo.topics.domain.model

/** What a card in the topics list needs to know about one topic (Figma: Topics 1a). */
data class TopicOverview(
    val topic: Topic,
    val itemCount: Int,
    /** Only the types the topic holds, in [ItemType] order. */
    val counts: Map<ItemType, Int>,
    /** Local thumbnails of images, videos and links, newest first. */
    val previews: List<String>,
    /** One total per currency, by currency code. Empty when the topic has no bills. */
    val billTotals: List<Money>,
    /** Read articles out of all articles; null when the topic has none. */
    val reading: Progress?,
) {
    companion object {
        /** [items] must all belong to [topic]; order doesn't matter. */
        fun of(topic: Topic, items: List<TopicItem>): TopicOverview {
            val newestFirst = items.sortedByDescending { it.addedAtMillis }
            val articles = items.filterIsInstance<TopicItem.Article>()
            return TopicOverview(
                topic = topic,
                itemCount = items.size,
                counts = items.countByType(),
                previews = newestFirst.mapNotNull { it.previewPath() },
                billTotals = items.billTotals(),
                reading = if (articles.isEmpty()) null else Progress(done = articles.count { it.read }, total = articles.size),
            )
        }
    }
}

/** One topic with its items, newest first (Figma: Topics 1b). */
data class TopicDetail(
    val topic: Topic,
    val items: List<TopicItem>,
) {
    /** Only the types the topic holds, in [ItemType] order. */
    val counts: Map<ItemType, Int> = items.countByType()

    /** The chosen sections, plus any type the topic holds anyway. */
    val visibleSections: Set<ItemType> = ItemType.entries.filterTo(LinkedHashSet()) { it in topic.sections || it in counts }

    val billTotals: List<Money> = items.billTotals()
}

data class Progress(val done: Int, val total: Int)

enum class TopicSort {
    /** Pinned topics first, then most recently changed. */
    Recent,

    /** Only pinned topics, most recently changed first. */
    Pinned,

    /** By name, ignoring case. */
    Alphabetical,
}

fun List<TopicOverview>.sortedFor(sort: TopicSort): List<TopicOverview> = when (sort) {
    TopicSort.Recent -> sortedWith(
        compareByDescending<TopicOverview> { it.topic.pinned }.thenByDescending { it.topic.updatedAtMillis },
    )
    TopicSort.Pinned -> filter { it.topic.pinned }.sortedByDescending { it.topic.updatedAtMillis }
    TopicSort.Alphabetical -> sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.topic.name })
}

private fun List<TopicItem>.countByType(): Map<ItemType, Int> {
    val counts = groupingBy { it.type }.eachCount()
    return ItemType.entries.filter { it in counts }.associateWith { counts.getValue(it) }
}

private fun List<TopicItem>.billTotals(): List<Money> =
    filterIsInstance<TopicItem.Bill>()
        .groupBy { it.amount.currency }
        .map { (currency, bills) -> Money(bills.sumOf { it.amount.minorUnits }, currency) }
        .sortedBy { it.currency }

private fun TopicItem.previewPath(): String? = when (this) {
    is TopicItem.Image -> file.path
    is TopicItem.Video -> link.thumbnailPath
    is TopicItem.Link -> link.thumbnailPath
    is TopicItem.Article -> link.thumbnailPath
    is TopicItem.Note, is TopicItem.Doc, is TopicItem.Bill -> null
}
