package dev.kortex.finance.domain.usecase

import dev.kortex.finance.domain.FinanceIds
import dev.kortex.finance.domain.calc.RecurringSchedule
import dev.kortex.finance.domain.model.CategoryKind
import dev.kortex.finance.domain.model.Frequency
import dev.kortex.finance.domain.model.Recurring
import dev.kortex.finance.domain.model.RecurringKind
import dev.kortex.finance.domain.model.Transaction
import dev.kortex.finance.domain.model.TransactionSource
import dev.kortex.finance.domain.model.TransactionType
import dev.kortex.finance.domain.port.Clock
import dev.kortex.finance.domain.repository.FinanceRepository
import java.time.LocalDate

/** Add recurring payment / editing one (Figma: Recurring 02). */
data class RecurringDraft(
    val name: String,
    val kind: RecurringKind,
    val amountMinor: Long,
    val frequency: Frequency,
    val nextDueOn: LocalDate,
    val accountUid: String,
    val categoryUid: String? = null,
    val remindDaysBefore: Int = Recurring.NO_REMINDER,
    val autoMarkPaid: Boolean = false,
    val paused: Boolean = false,
    val interval: Int = 1,
)

sealed interface RecurringSaveResult {
    data class Saved(val uid: String) : RecurringSaveResult
    data object BlankName : RecurringSaveResult
    data object InvalidAmount : RecurringSaveResult
    data object UnknownAccount : RecurringSaveResult
    data object WrongCategoryKind : RecurringSaveResult
    data object InvalidReminder : RecurringSaveResult
    data object NotFound : RecurringSaveResult
}

/** Adds a recurring payment when [uid] is null, otherwise edits that one. Nothing is paid by saving. */
class SaveRecurring(private val repository: FinanceRepository, private val clock: Clock) {
    suspend operator fun invoke(uid: String?, draft: RecurringDraft): RecurringSaveResult {
        val name = draft.name.trim().ifEmpty { return RecurringSaveResult.BlankName }
        if (draft.amountMinor <= 0) return RecurringSaveResult.InvalidAmount
        if (draft.remindDaysBefore !in Recurring.NO_REMINDER..MAX_REMIND_DAYS) return RecurringSaveResult.InvalidReminder
        repository.getAccount(draft.accountUid) ?: return RecurringSaveResult.UnknownAccount
        draft.categoryUid?.let { if (repository.getCategory(it)?.kind != CategoryKind.EXPENSE) return RecurringSaveResult.WrongCategoryKind }
        val existing = uid?.let { repository.getRecurring(it) ?: return RecurringSaveResult.NotFound }
        val now = clock.nowMillis()
        // Unchanged dates keep their anchor, so a payment on the 31st that fell on the 30th this month
        // goes back to the 31st next month.
        val keepsAnchor = existing != null && existing.nextDueOn == draft.nextDueOn && existing.frequency == draft.frequency
        val recurring = Recurring(
            uid = existing?.uid ?: FinanceIds.random(),
            name = name,
            kind = draft.kind,
            amountMinor = draft.amountMinor,
            currency = existing?.currency ?: Transaction.DEFAULT_CURRENCY,
            frequency = draft.frequency,
            interval = draft.interval.coerceAtLeast(1),
            anchorDay = if (keepsAnchor) existing!!.anchorDay else anchorDayOf(draft.frequency, draft.nextDueOn),
            nextDueOn = draft.nextDueOn,
            accountUid = draft.accountUid,
            categoryUid = draft.categoryUid,
            remindDaysBefore = draft.remindDaysBefore,
            autoMarkPaid = draft.autoMarkPaid,
            paused = draft.paused,
            createdAtMillis = existing?.createdAtMillis ?: now,
            updatedAtMillis = now,
        )
        repository.upsertRecurring(recurring)
        return RecurringSaveResult.Saved(recurring.uid)
    }

    companion object {
        const val MAX_REMIND_DAYS = 7

        fun anchorDayOf(frequency: Frequency, dueOn: LocalDate): Int =
            if (frequency == Frequency.WEEKLY) dueOn.dayOfWeek.value else dueOn.dayOfMonth
    }
}

/** Deleting a recurring payment keeps the expenses it recorded. */
class DeleteRecurring(private val repository: FinanceRepository) {
    suspend operator fun invoke(uid: String) = repository.deleteRecurring(uid)
}

/** Mark as paid's fields (Figma: Recurring 03); each defaults to the recurring payment's own. */
data class PaymentDraft(
    val amountMinor: Long? = null,
    val accountUid: String? = null,
    /** Kept as the recurring payment's when [keepCategory]; otherwise this, null being Uncategorised. */
    val categoryUid: String? = null,
    val keepCategory: Boolean = true,
    /** Today when null. */
    val paidOn: LocalDate? = null,
)

sealed interface OccurrenceResult {
    /** [previous] is the recurring payment as it was, for Undo. */
    data class Paid(val transactionUid: String, val nextDueOn: LocalDate, val previous: Recurring) : OccurrenceResult
    data class Skipped(val nextDueOn: LocalDate, val previous: Recurring) : OccurrenceResult

    /** Already paid, here or on another device; the due date has been moved past it. */
    data class AlreadyPaid(val nextDueOn: LocalDate) : OccurrenceResult
    data object NotFound : OccurrenceResult
    data class Failed(val reason: TransactionSaveResult) : OccurrenceResult
}

/**
 * Pays one occurrence of a recurring payment: records the expense (`rec_…`, so paying it on two
 * phones is one entry) and moves the next due date on, as one change.
 */
class MarkPaid(
    private val repository: FinanceRepository,
    private val addTransaction: AddTransaction,
    private val clock: Clock,
) {
    suspend operator fun invoke(recurringUid: String, dueOn: LocalDate, payment: PaymentDraft = PaymentDraft()): OccurrenceResult {
        val recurring = repository.getRecurring(recurringUid) ?: return OccurrenceResult.NotFound
        val uid = FinanceIds.recurringOccurrence(recurringUid, dueOn)
        val moved = recurring.copy(nextDueOn = Occurrences.nextUnpaid(repository, recurring, dueOn))
        if (repository.getTransaction(uid) != null) {
            if (moved.nextDueOn != recurring.nextDueOn) repository.saveRecurringChange(moved)
            return OccurrenceResult.AlreadyPaid(moved.nextDueOn)
        }
        val draft = TransactionDraft(
            type = TransactionType.EXPENSE,
            amountMinor = payment.amountMinor ?: recurring.amountMinor,
            accountUid = payment.accountUid ?: recurring.accountUid,
            categoryUid = if (payment.keepCategory) recurring.categoryUid else payment.categoryUid,
            merchant = recurring.name,
            occurredAtMillis = clock.millisOn(payment.paidOn ?: clock.today()),
            source = TransactionSource.RECURRING,
            recurringUid = recurring.uid,
            dueOn = dueOn,
            uid = uid,
        )
        return when (val prepared = addTransaction.prepare(draft)) {
            is AddTransaction.Prepared.Rejected -> OccurrenceResult.Failed(prepared.result)
            is AddTransaction.Prepared.Ready -> {
                repository.saveRecurringChange(moved, prepared.transaction, prepared.merchant)
                OccurrenceResult.Paid(prepared.transaction.uid, moved.nextDueOn, recurring)
            }
        }
    }
}

/** Skip this time: moves the next due date on without recording anything. */
class SkipOccurrence(private val repository: FinanceRepository) {
    suspend operator fun invoke(recurringUid: String, dueOn: LocalDate): OccurrenceResult {
        val recurring = repository.getRecurring(recurringUid) ?: return OccurrenceResult.NotFound
        val moved = recurring.copy(nextDueOn = Occurrences.nextUnpaid(repository, recurring, dueOn))
        repository.saveRecurringChange(moved)
        return OccurrenceResult.Skipped(moved.nextDueOn, recurring)
    }
}

/** Undo after Mark as paid or Skip: puts the recurring payment back and removes what was recorded. */
class UndoOccurrence(private val repository: FinanceRepository) {
    suspend operator fun invoke(previous: Recurring, paymentUid: String?) {
        // Edited or deleted since: leave it, and only take the payment back.
        val current = repository.getRecurring(previous.uid)
        if (current == null) {
            paymentUid?.let { repository.deleteTransaction(it) }
            return
        }
        repository.saveRecurringChange(current.copy(nextDueOn = previous.nextDueOn), removePaymentUid = paymentUid)
    }
}

internal object Occurrences {
    /**
     * The first occurrence after [dueOn] (or the current next due date, if that's later) that
     * isn't paid yet; another phone may already have paid the following ones.
     */
    suspend fun nextUnpaid(repository: FinanceRepository, recurring: Recurring, dueOn: LocalDate): LocalDate {
        var next = if (dueOn.isBefore(recurring.nextDueOn)) recurring.nextDueOn else RecurringSchedule.nextAfter(recurring, dueOn)
        repeat(MAX_CATCH_UP) {
            if (repository.getTransaction(FinanceIds.recurringOccurrence(recurring.uid, next)) == null) return next
            next = RecurringSchedule.nextAfter(recurring, next)
        }
        return next
    }

    const val MAX_CATCH_UP = 60
}
