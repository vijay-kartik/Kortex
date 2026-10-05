package dev.kortex.finance.ui.recurring

import dev.kortex.finance.domain.model.Account
import dev.kortex.finance.domain.model.Frequency
import dev.kortex.finance.domain.model.Recurring
import dev.kortex.finance.domain.model.RecurringKind

/** The words the recurring screens share. */
object RecurringLabels {
    /** "monthly", "every 2 weeks". */
    fun frequency(frequency: Frequency, interval: Int = 1): String = when {
        interval <= 1 -> when (frequency) {
            Frequency.WEEKLY -> "weekly"
            Frequency.MONTHLY -> "monthly"
            Frequency.YEARLY -> "yearly"
        }
        else -> "every $interval ${unit(frequency)}s"
    }

    /** "Every month", "Every 2 weeks" (Figma: Recurring 02 › Repeats). */
    fun repeats(frequency: Frequency, interval: Int = 1): String =
        if (interval <= 1) "Every ${unit(frequency)}" else "Every $interval ${unit(frequency)}s"

    fun kind(kind: RecurringKind): String = if (kind == RecurringKind.SUBSCRIPTION) "Subscription" else "Fixed"

    /** "Subscription · monthly". */
    fun summary(recurring: Recurring): String = "${kind(recurring.kind)} · ${frequency(recurring.frequency, recurring.interval)}"

    /** "KORTEX ••8824", "Personal Cash". */
    fun account(account: Account): String = listOfNotNull(account.name, account.last4?.let { "••$it" }).joinToString(" ")

    /** Remind me's choices: days before, or [Recurring.NO_REMINDER]. */
    val reminderChoices: List<Int> = listOf(Recurring.NO_REMINDER, 0, 1, 2, 3, 7)

    fun reminder(days: Int): String = when (days) {
        Recurring.NO_REMINDER -> "Off"
        0 -> "On the day"
        1 -> "1 day before"
        else -> "$days days before"
    }

    private fun unit(frequency: Frequency) = when (frequency) {
        Frequency.WEEKLY -> "week"
        Frequency.MONTHLY -> "month"
        Frequency.YEARLY -> "year"
    }
}
