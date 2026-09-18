package dev.kortex.myinfo.topics.domain.model

import java.util.Calendar

/** How a topic arranges its items (Figma: Topics 1c). */
enum class TopicViewMode {
    /** Everything newest first, one run of cards. */
    Feed,

    /** Newest first, cut into today, yesterday, this week, this month and earlier. */
    Timeline,

    /** Grouped by item type, busiest type first; newest first inside each. */
    ByType,
}

/** A run of items under one heading. */
data class FeedSection(val group: FeedGroup, val items: List<TopicItem>)

/** What a [FeedSection] gathers. The UI names them; the domain only says which is which. */
sealed interface FeedGroup {
    /** Pinned items, above everything, in every mode. */
    data object Pinned : FeedGroup

    /** The whole feed under no heading at all ([TopicViewMode.Feed]). */
    data object Everything : FeedGroup

    data class Period(val period: TimePeriod) : FeedGroup

    data class Type(val type: ItemType) : FeedGroup
}

enum class TimePeriod { Today, Yesterday, ThisWeek, ThisMonth, Earlier }

/**
 * Cuts a topic's items into the sections its current view mode shows. [items] arrive newest
 * first and stay that way inside every section; empty sections are left out, so a mode never
 * shows a heading with nothing under it.
 */
object TopicFeed {
    fun sections(items: List<TopicItem>, mode: TopicViewMode, nowMillis: Long): List<FeedSection> {
        if (items.isEmpty()) return emptyList()
        val (pinned, rest) = items.partition { it.pinned }
        val sections = when (mode) {
            TopicViewMode.Feed -> listOfNotNull(rest.section(FeedGroup.Everything))
            TopicViewMode.Timeline -> TimePeriod.entries.mapNotNull { period ->
                rest.filter { periodOf(it.addedAtMillis, nowMillis) == period }.section(FeedGroup.Period(period))
            }
            TopicViewMode.ByType -> rest.groupBy { it.type }
                .entries
                // Busiest type first; a tie keeps them in the order the types are declared.
                .sortedWith(compareByDescending<Map.Entry<ItemType, List<TopicItem>>> { it.value.size }.thenBy { it.key.ordinal })
                .mapNotNull { (type, ofType) -> ofType.section(FeedGroup.Type(type)) }
        }
        return listOfNotNull(pinned.section(FeedGroup.Pinned)) + sections
    }

    /**
     * Which stretch of time [atMillis] falls in, by calendar day rather than by elapsed hours, so
     * something saved last night is "yesterday" even an hour later.
     */
    fun periodOf(atMillis: Long, nowMillis: Long): TimePeriod {
        val days = daysBetween(atMillis, nowMillis)
        return when {
            days <= 0 -> TimePeriod.Today
            days == 1 -> TimePeriod.Yesterday
            days < 7 -> TimePeriod.ThisWeek
            days < 30 -> TimePeriod.ThisMonth
            else -> TimePeriod.Earlier
        }
    }

    /** Whole calendar days from [atMillis] to [nowMillis]; negative for a date in the future. */
    private fun daysBetween(atMillis: Long, nowMillis: Long): Int =
        ((startOfDay(nowMillis) - startOfDay(atMillis)) / DAY_MS).toInt()

    private fun startOfDay(atMillis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = atMillis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun List<TopicItem>.section(group: FeedGroup): FeedSection? =
        if (isEmpty()) null else FeedSection(group, this)

    private const val DAY_MS = 86_400_000L
}
