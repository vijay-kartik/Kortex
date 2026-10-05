package dev.kortex.finance.ui.recurring

import dev.kortex.finance.domain.calc.DueUrgency
import dev.kortex.finance.domain.calc.Pending
import dev.kortex.finance.domain.calc.RecurringSchedule
import dev.kortex.finance.domain.model.RecurringKind
import dev.kortex.finance.domain.usecase.FinanceSnapshot
import dev.kortex.finance.ui.FinanceRoute
import dev.kortex.finance.ui.common.FinanceFormat
import java.time.LocalDate

enum class RecurringFilter(val label: String) { ALL("All"), SUBS("Subs"), FIXED("Fixed") }

data class RecurringRowUi(
    val uid: String,
    val name: String,
    /** "Monthly · KORTEX ••8824", "Needs an account", "Paused". */
    val subtitle: String,
    val amountMinor: Long,
    /** "Next 3 Oct". */
    val next: String,
    val urgency: DueUrgency,
    val warning: Boolean = false,
)

data class RecurringGroupUi(val label: String, val rows: List<RecurringRowUi>)

/** Recurring payments (Figma: Recurring 01). */
data class RecurringListState(
    val loading: Boolean = true,
    val perMonthMinor: Long = 0,
    val subscriptionsMinor: Long = 0,
    val subscriptionCount: Int = 0,
    val fixedMinor: Long = 0,
    val fixedCount: Int = 0,
    val perYearMinor: Long = 0,
    /** "Netflix · 3 Oct". */
    val nextDue: String? = null,
    val filter: RecurringFilter = RecurringFilter.ALL,
    val counts: Map<RecurringFilter, Int> = emptyMap(),
    val groups: List<RecurringGroupUi> = emptyList(),
)

sealed interface RecurringListIntent {
    data class Filter(val filter: RecurringFilter) : RecurringListIntent
    data object Add : RecurringListIntent
    data class Open(val uid: String) : RecurringListIntent
}

sealed interface RecurringListEffect {
    data class Navigate(val route: FinanceRoute) : RecurringListEffect
}

object RecurringListUi {
    fun build(snapshot: FinanceSnapshot, today: LocalDate, filter: RecurringFilter = RecurringFilter.ALL): RecurringListState {
        val totals = RecurringSchedule.totals(snapshot.recurring)
        val sorted = snapshot.recurring.sortedWith(compareBy({ it.paused }, { it.nextDueOn }, { it.name.lowercase() }))
        val rows = sorted.associateWith { recurring ->
            val account = snapshot.accountsByUid[recurring.accountUid]
            RecurringRowUi(
                uid = recurring.uid,
                name = recurring.name,
                subtitle = when {
                    recurring.paused -> "Paused"
                    account == null -> "Needs an account"
                    else -> "${RecurringLabels.frequency(recurring.frequency, recurring.interval).replaceFirstChar { it.uppercase() }} · ${RecurringLabels.account(account)}"
                },
                amountMinor = recurring.amountMinor,
                next = if (recurring.paused) "—" else "Next ${FinanceFormat.day(recurring.nextDueOn)}",
                urgency = if (recurring.paused) DueUrgency.LATER else Pending.urgency(recurring.nextDueOn, today),
                warning = !recurring.paused && account == null,
            )
        }
        val visible = sorted.filter {
            when (filter) {
                RecurringFilter.ALL -> true
                RecurringFilter.SUBS -> it.kind == RecurringKind.SUBSCRIPTION
                RecurringFilter.FIXED -> it.kind == RecurringKind.FIXED
            }
        }
        val next = snapshot.recurring.filterNot { it.paused }.minWithOrNull(compareBy({ it.nextDueOn }, { it.name }))
        return RecurringListState(
            loading = false,
            perMonthMinor = totals.perMonthMinor,
            subscriptionsMinor = totals.subscriptionsPerMonthMinor,
            subscriptionCount = totals.subscriptionCount,
            fixedMinor = totals.fixedPerMonthMinor,
            fixedCount = totals.fixedCount,
            perYearMinor = totals.perYearMinor,
            nextDue = next?.let { "${it.name} · ${FinanceFormat.day(it.nextDueOn)}" },
            filter = filter,
            counts = mapOf(
                RecurringFilter.ALL to snapshot.recurring.size,
                RecurringFilter.SUBS to snapshot.recurring.count { it.kind == RecurringKind.SUBSCRIPTION },
                RecurringFilter.FIXED to snapshot.recurring.count { it.kind == RecurringKind.FIXED },
            ),
            groups = listOf(
                RecurringGroupUi("Subscriptions", visible.filter { it.kind == RecurringKind.SUBSCRIPTION }.map(rows::getValue)),
                RecurringGroupUi("Fixed expenses", visible.filter { it.kind == RecurringKind.FIXED }.map(rows::getValue)),
            ).filter { it.rows.isNotEmpty() },
        )
    }
}
