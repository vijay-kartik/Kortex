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

    /** Null when the topic holds no bills. */
    val bills: BillSummary? = BillSummary.of(items.filterIsInstance<TopicItem.Bill>())
}

data class Progress(val done: Int, val total: Int)

/** What a topic's bills add up to (Figma: Topics 1b, bills card). */
data class BillSummary(
    /** One total per currency of the bills still to pay; empty once they all are. */
    val outstanding: List<Money>,
    val paid: Progress,
    /** The soonest due date still to pay; null when none of them has one. */
    val nextDueAtMillis: Long?,
) {
    companion object {
        fun of(bills: List<TopicItem.Bill>): BillSummary? {
            if (bills.isEmpty()) return null
            val unpaid = bills.filterNot { it.paid }
            return BillSummary(
                outstanding = unpaid.map { it.amount }.totalPerCurrency(),
                paid = Progress(done = bills.size - unpaid.size, total = bills.size),
                nextDueAtMillis = unpaid.mapNotNull { it.dueAtMillis }.minOrNull(),
            )
        }
    }
}

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
    filterIsInstance<TopicItem.Bill>().map { it.amount }.totalPerCurrency()

private fun List<Money>.totalPerCurrency(): List<Money> =
    groupBy { it.currency }
        .map { (currency, amounts) -> Money(amounts.sumOf { it.minorUnits }, currency) }
        .sortedBy { it.currency }

private fun TopicItem.previewPath(): String? = when (this) {
    is TopicItem.Image -> file.path
    is TopicItem.Video -> link.thumbnailPath
    is TopicItem.Link -> link.thumbnailPath
    is TopicItem.Article -> link.thumbnailPath
    is TopicItem.Note, is TopicItem.Doc, is TopicItem.Bill, is TopicItem.Email -> null
}
