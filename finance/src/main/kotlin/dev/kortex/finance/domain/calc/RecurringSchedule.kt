package dev.kortex.finance.domain.calc

import dev.kortex.finance.domain.model.Frequency
import dev.kortex.finance.domain.model.Recurring
import dev.kortex.finance.domain.model.RecurringKind
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

/** What recurring payments add up to (Figma: Recurring 01). */
data class RecurringTotals(
    val perMonthMinor: Long,
    val subscriptionsPerMonthMinor: Long,
    val subscriptionCount: Int,
    val fixedPerMonthMinor: Long,
    val fixedCount: Int,
) {
    val perYearMinor: Long get() = perMonthMinor * 12
}

/** When recurring payments fall due. */
object RecurringSchedule {

    /** The occurrence after [dueOn], keeping to the anchor day (a 31st falls on the 30th, then back on the 31st). */
    fun nextAfter(recurring: Recurring, dueOn: LocalDate): LocalDate {
        val step = recurring.interval.coerceAtLeast(1).toLong()
        return when (recurring.frequency) {
            Frequency.WEEKLY -> dueOn.plusWeeks(step)
                .with(TemporalAdjusters.nextOrSame(DayOfWeek.of(recurring.anchorDay.coerceIn(1, 7))))
            Frequency.MONTHLY -> Statements.dayIn(YearMonth.from(dueOn).plusMonths(step), recurring.anchorDay)
            Frequency.YEARLY -> Statements.dayIn(YearMonth.from(dueOn).plusYears(step), recurring.anchorDay)
        }
    }

    /**
     * Unpaid occurrences from [Recurring.nextDueOn] up to and including [until]; the first may be
     * overdue. None while paused.
     */
    fun occurrencesUntil(recurring: Recurring, until: LocalDate, limit: Int = 60): List<LocalDate> {
        if (recurring.paused) return emptyList()
        return generateSequence(recurring.nextDueOn) { nextAfter(recurring, it) }
            .takeWhile { !it.isAfter(until) }
            .take(limit)
            .toList()
    }

    /** Monthly as is, weekly × 52 ÷ 12, yearly ÷ 12, each divided by its interval. */
    fun perMonthMinor(recurring: Recurring): Long {
        val interval = recurring.interval.coerceAtLeast(1).toLong()
        return when (recurring.frequency) {
            Frequency.WEEKLY -> roundDiv(recurring.amountMinor * 52, 12 * interval)
            Frequency.MONTHLY -> roundDiv(recurring.amountMinor, interval)
            Frequency.YEARLY -> roundDiv(recurring.amountMinor, 12 * interval)
        }
    }

    /** Paused payments aren't counted. */
    fun totals(recurring: List<Recurring>): RecurringTotals {
        val active = recurring.filterNot { it.paused }
        val subscriptions = active.filter { it.kind == RecurringKind.SUBSCRIPTION }
        val fixed = active.filter { it.kind == RecurringKind.FIXED }
        val subsMonthly = subscriptions.sumOf(::perMonthMinor)
        val fixedMonthly = fixed.sumOf(::perMonthMinor)
        return RecurringTotals(
            perMonthMinor = subsMonthly + fixedMonthly,
            subscriptionsPerMonthMinor = subsMonthly,
            subscriptionCount = subscriptions.size,
            fixedPerMonthMinor = fixedMonthly,
            fixedCount = fixed.size,
        )
    }
}
