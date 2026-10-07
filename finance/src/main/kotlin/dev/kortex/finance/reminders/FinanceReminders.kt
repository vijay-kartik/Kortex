package dev.kortex.finance.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.kortex.finance.R
import dev.kortex.finance.domain.calc.PendingKind
import dev.kortex.finance.domain.calc.Reminder
import dev.kortex.finance.domain.calc.Reminders
import dev.kortex.finance.domain.calc.roundDiv
import dev.kortex.finance.domain.port.Clock
import dev.kortex.finance.domain.usecase.ObserveBudgets
import dev.kortex.finance.domain.usecase.ObserveFinance
import dev.kortex.finance.ui.FinanceRoute
import dev.kortex.finance.ui.common.FinanceFormat
import java.time.Duration
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/**
 * Payment reminders (docs/FINANCE_PLAN.md › How entries get in): once a day a job posts
 * "Netflix ₹649 due in 2 days" for whatever [Reminders] says is due. It only reads; the engine
 * that writes runs when Finances opens.
 */
object FinanceReminders {
    /** On the launch intent: an encoded [FinanceRoute] for the host to open. */
    const val EXTRA_OPEN_ROUTE = "dev.kortex.finance.OPEN_ROUTE"

    private const val WORK_NAME = "finance-reminders"
    private const val CHANNEL_ID = "finance-reminders"
    private val PostAt: LocalTime = LocalTime.of(9, 0)

    /** Schedules the daily job, around 9 in the morning; calling it again keeps the existing one. */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<FinanceReminderWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(untilNext(PostAt).toMinutes(), TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    internal fun untilNext(time: LocalTime, now: ZonedDateTime = ZonedDateTime.now()): Duration {
        var next = now.with(time)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next)
    }

    /** "Netflix ₹649 due in 2 days", "KORTEX bill ₹1,400 due today", "Food: 85 % of ₹8,000 used". */
    fun title(reminder: Reminder): String {
        if (reminder.kind == PendingKind.BUDGET) {
            return if (reminder.threshold == 100) {
                "${reminder.name} is ${FinanceFormat.rupees(reminder.spentMinor - reminder.amountMinor, paise = false)} over budget"
            } else {
                val percent = roundDiv(reminder.spentMinor * 100, reminder.amountMinor)
                "${reminder.name}: $percent % of ${FinanceFormat.rupees(reminder.amountMinor, paise = false)} used"
            }
        }
        val amount = FinanceFormat.rupees(reminder.amountMinor, paise = false)
        val what = if (reminder.kind == PendingKind.CARD_BILL) "${reminder.name} bill $amount" else "${reminder.name} $amount"
        val `when` = when (reminder.daysLeft) {
            0 -> "today"
            1 -> "tomorrow"
            else -> "in ${reminder.daysLeft} days"
        }
        return if (reminder.automatic) "$what will be auto-debited ${`when`}" else "$what due ${`when`}"
    }

    fun text(reminder: Reminder): String = when {
        reminder.kind == PendingKind.BUDGET -> {
            val month = FinanceFormat.monthName(YearMonth.from(reminder.dueOn))
            val budget = FinanceFormat.rupees(reminder.amountMinor, paise = false)
            if (reminder.spentMinor > reminder.amountMinor) {
                "${FinanceFormat.rupees(reminder.spentMinor, paise = false)} spent of $budget in $month"
            } else {
                "${FinanceFormat.rupees(reminder.amountMinor - reminder.spentMinor, paise = false)} left for $month"
            }
        }
        reminder.kind == PendingKind.CARD_BILL -> "Due ${FinanceFormat.weekdayDay(reminder.dueOn)} · tap to pay it"
        reminder.automatic -> "Kortex will mark it paid on ${FinanceFormat.weekdayDay(reminder.dueOn)}"
        else -> "Due ${FinanceFormat.weekdayDay(reminder.dueOn)} · tap to mark it paid"
    }

    internal fun post(context: Context, reminders: List<Reminder>) {
        if (reminders.isEmpty()) return
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Payment reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Subscriptions, fixed expenses and card bills coming up, and budgets running out."
            },
        )
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_ROUTE, FinanceRoute.Pending.encode())
        }
        val contentIntent = open?.let { PendingIntent.getActivity(context, WORK_NAME.hashCode(), it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
        reminders.forEach { reminder ->
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_fin_wallet)
                .setContentTitle(title(reminder))
                .setContentText(text(reminder))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .build()
            // Without the permission (Android 13+, declined) the reminder is simply not shown.
            runCatching { manager.notify(reminder.key.hashCode(), notification) }
        }
    }
}

/** The daily job; Hilt dependencies come through [FinanceReminderEntryPoint], so no worker factory is needed. */
class FinanceReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, FinanceReminderEntryPoint::class.java)
        val snapshot = deps.observeFinance()().first()
        val budgets = deps.observeBudgets()().first()
        val reminders = Reminders.due(
            deps.clock().today(),
            snapshot.accounts,
            snapshot.recurring,
            snapshot.statements,
            snapshot.transactions,
            snapshot.categories,
            budgets,
        )
        FinanceReminders.post(applicationContext, reminders)
        return Result.success()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface FinanceReminderEntryPoint {
    fun observeFinance(): ObserveFinance
    fun observeBudgets(): ObserveBudgets
    fun clock(): Clock
}
