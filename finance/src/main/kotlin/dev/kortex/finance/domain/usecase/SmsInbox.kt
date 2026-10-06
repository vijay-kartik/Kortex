package dev.kortex.finance.domain.usecase

import dev.kortex.finance.domain.FinanceIds
import dev.kortex.finance.domain.calc.Balances
import dev.kortex.finance.domain.model.InboxSms
import dev.kortex.finance.domain.model.InboxStatus
import dev.kortex.finance.domain.model.ReviewReason
import dev.kortex.finance.domain.model.TransactionSource
import dev.kortex.finance.domain.port.Clock
import dev.kortex.finance.domain.read.SenderGate
import dev.kortex.finance.domain.repository.SmsInboxRepository
import kotlinx.coroutines.flow.first

/**
 * The one thing SMS ingestion calls (docs/SMS_AUTO_PLAN.md): an SMS from a bank-looking sender is
 * kept in the inbox before anything reads it, so a stopped job or a failed model call leaves a row
 * to try again rather than a lost message. Anything else is dropped unread and never stored.
 */
class ReceiveSms(private val inbox: SmsInboxRepository) {
    /** True when [body] was queued: a sender that may be a bank, and not received before. */
    suspend operator fun invoke(sender: String, body: String, receivedAtMillis: Long): Boolean =
        inbox.keep(sender, body, receivedAtMillis, imported = false)
}

/** An SMS already on the phone, as its SMS store has it. */
data class StoredSms(val sender: String, val body: String, val receivedAtMillis: Long)

/**
 * Settings › Add earlier SMS: SMS that arrived before the feature was on, read from the phone's
 * store. The same gate as [ReceiveSms]; kept apart ([InboxSms.imported]) so a big import is read in
 * its own runs and tells the user once, at the end, rather than per entry. An SMS already kept (heard
 * live, or imported before) isn't added again.
 */
class ImportSms(private val inbox: SmsInboxRepository) {
    /** How many were kept to be read. */
    suspend operator fun invoke(messages: List<StoredSms>): Int =
        messages.count { inbox.keep(it.sender, it.body, it.receivedAtMillis, imported = true) }
}

private suspend fun SmsInboxRepository.keep(sender: String, body: String, receivedAtMillis: Long, imported: Boolean): Boolean {
    val text = body.trim()
    if (text.isEmpty() || !SenderGate.mayRead(sender)) return false
    return add(InboxSms(FinanceIds.smsTransaction("", text), sender.trim(), text, receivedAtMillis, imported = imported))
}

/** An entry saved on its own, for its notification. */
data class AutoSaved(
    val smsId: String,
    val transactionUid: String,
    val plan: SmsEntryPlan,
    /** The category it was saved under; null when there was no sure pick. */
    val categoryName: String?,
)

/**
 * What one run did: the entries it saved on its own, and how many SMS it sent to review. [more]
 * when it stopped at its limit with SMS still waiting.
 */
data class InboxRun(val saved: List<AutoSaved>, val toReview: Int, val more: Boolean = false, val read: Int = 0)

/**
 * Reads every received SMS waiting in the inbox (docs/SMS_AUTO_PLAN.md › Rules): patterns first,
 * then saved on its own when every auto-save rule holds, else sent to review with the reason.
 * Each run also forgets handled SMS older than 30 days.
 */
class ProcessSmsInbox(
    private val inbox: SmsInboxRepository,
    private val readSms: ReadSms,
    private val prepare: PrepareSmsEntry,
    private val addTransaction: AddTransaction,
    private val observeFinance: ObserveFinance,
    private val clock: Clock,
) {
    /**
     * [autoSave] is the "Save clear ones without asking" switch; off, everything readable goes to
     * review. [imported] reads the earlier SMS from an import instead of the ones heard live, at most
     * [limit] of them, so a long import goes in runs that each finish well inside a job's time.
     */
    suspend operator fun invoke(autoSave: Boolean, imported: Boolean = false, limit: Int = Int.MAX_VALUE): InboxRun {
        val saved = mutableListOf<AutoSaved>()
        var toReview = 0
        val rows = inbox.pending(imported, limit)
        for (row in rows) {
            val (next, auto) = process(row, autoSave)
            inbox.update(next)
            auto?.let(saved::add)
            if (next.status == InboxStatus.REVIEW) toReview++
        }
        inbox.deleteHandledBefore(clock.nowMillis() - KEEP_MILLIS)
        return InboxRun(saved, toReview, more = inbox.pending(imported, limit = 1).isNotEmpty(), read = rows.size)
    }

    private suspend fun process(row: InboxSms, autoSave: Boolean): Pair<InboxSms, AutoSaved?> {
        val body = row.body ?: return row.handled(InboxStatus.DISMISSED) to null
        val sms = when (val result = readSms(body, SmsMode.INCOMING)) {
            is SmsResult.NotAPayment ->
                return (if (result.certain) row.handled(InboxStatus.NOT_PAYMENT) else row.toReview(ReviewReason.UNREADABLE)) to null
            is SmsResult.Read -> result.sms
        }
        val snapshot = observeFinance().first()
        val plan = prepare(body, sms, snapshot)
        // Pasted before it was read here: it's the entry already there.
        plan.duplicate?.takeIf { it.uid == plan.uid }?.let { return row.handled(InboxStatus.SAVED, it.uid) to null }
        val account = plan.account
        // Where the money really moves: a linked debit card's spends are its bank account's.
        val ledger = account?.let { snapshot.accountsByUid[Balances.ledgerOf(it.uid, snapshot.accountsByUid)] ?: it }
        val reason = when {
            sms.fromModel -> ReviewReason.MODEL_READ
            plan.type != SmsEntryType.EXPENSE && plan.type != SmsEntryType.INCOME -> ReviewReason.CARD
            account == null || ledger == null || snapshot.accounts.count { !it.archived && it.last4 == sms.last4 } != 1 -> ReviewReason.NO_ACCOUNT
            ledger != null && plan.occurredAtMillis < ledger.createdAtMillis -> ReviewReason.BEFORE_ACCOUNT
            plan.duplicate != null -> ReviewReason.DUPLICATE
            // Entered by hand before the import: its time is when it was typed, not when it was paid.
            row.imported && snapshot.transactions.any { tx ->
                tx.accountUid == account?.uid && tx.amountMinor == sms.amountMinor && tx.occurredOn == plan.date &&
                    tx.type == plan.type.transactionType
            } -> ReviewReason.DUPLICATE
            plan.date < clock.dayOf(row.receivedAtMillis).minusDays(LATE_DAYS) -> ReviewReason.LATE
            !autoSave -> ReviewReason.AUTO_OFF
            else -> null
        }
        if (reason != null || account == null) return row.toReview(reason ?: ReviewReason.NO_ACCOUNT) to null
        val draft = TransactionDraft(
            type = plan.type.transactionType,
            amountMinor = sms.amountMinor,
            accountUid = account.uid,
            categoryUid = plan.merchant.categoryUid,
            // A UPI id nobody has named yet is the merchant until someone does.
            merchant = plan.merchant.name ?: sms.payee?.trim()?.takeIf { it.isNotEmpty() },
            payeeKey = sms.payee?.takeIf { sms.payeeIsUpiId },
            occurredAtMillis = plan.occurredAtMillis,
            source = TransactionSource.SMS,
            sourceRef = sms.ref,
            uid = plan.uid,
        )
        return when (val result = addTransaction(draft)) {
            is TransactionSaveResult.Saved -> row.handled(InboxStatus.SAVED, result.uid) to
                AutoSaved(row.id, result.uid, plan, plan.merchant.categoryUid?.let(snapshot.categoriesByUid::get)?.name)
            is TransactionSaveResult.AlreadySaved -> row.handled(InboxStatus.SAVED, result.uid) to null
            else -> row.toReview(ReviewReason.NOT_SAVED) to null
        }
    }

    private fun InboxSms.toReview(reason: ReviewReason) = copy(status = InboxStatus.REVIEW, reason = reason)

    internal companion object {
        /** Older than this, an SMS is only checked in review: delayed delivery, or the phone was off. */
        const val LATE_DAYS = 3L
        const val KEEP_MILLIS = 30L * 24 * 60 * 60 * 1000
    }
}

/** Done with: the text goes, the entry it became (if any) is kept. */
internal fun InboxSms.handled(status: InboxStatus, transactionUid: String? = null) =
    copy(status = status, body = null, reason = null, transactionUid = transactionUid)

/**
 * What happens to a received SMS after it's read: saved from review, swiped away (and put back),
 * or an entry saved on its own taken back from its notification.
 */
class ResolveInboxSms(
    private val inbox: SmsInboxRepository,
    private val deleteTransaction: DeleteTransaction,
) {
    /** Saved from the Paste SMS review it opened, as [transactionUid]. */
    suspend fun saved(id: String, transactionUid: String) {
        val row = inbox.get(id) ?: return
        inbox.update(row.handled(InboxStatus.SAVED, transactionUid))
    }

    /** Swiped away in To review. Returns the row as it was, for Undo. */
    suspend fun dismiss(id: String): InboxSms? {
        val row = inbox.get(id)?.takeIf { it.status == InboxStatus.REVIEW } ?: return null
        inbox.update(row.handled(InboxStatus.DISMISSED))
        return row
    }

    /** Undo for [dismiss]: puts the row back as it was. */
    suspend fun restore(row: InboxSms) = inbox.update(row)

    /** Undo on an auto-saved entry's notification: the entry goes, the SMS is done with. */
    suspend fun undoAutoSaved(id: String) {
        val row = inbox.get(id)?.takeIf { it.status == InboxStatus.SAVED } ?: return
        row.transactionUid?.let { deleteTransaction(it) }
        inbox.update(row.handled(InboxStatus.DISMISSED))
    }
}
