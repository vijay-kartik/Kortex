package dev.kortex.finance.agent

import dev.kortex.finance.domain.calc.Pending
import dev.kortex.finance.domain.calc.PendingKind
import dev.kortex.finance.domain.calc.Spending
import dev.kortex.finance.domain.model.Account
import dev.kortex.finance.domain.model.AccountKind
import dev.kortex.finance.domain.model.CategoryKind
import dev.kortex.finance.domain.model.TransactionSource
import dev.kortex.finance.domain.model.TransactionType
import dev.kortex.finance.domain.port.Clock
import dev.kortex.finance.domain.usecase.AddTransaction
import dev.kortex.finance.domain.usecase.FinanceSnapshot
import dev.kortex.finance.domain.usecase.MarkPaid
import dev.kortex.finance.domain.usecase.ObserveFinance
import dev.kortex.finance.domain.usecase.OccurrenceResult
import dev.kortex.finance.domain.usecase.PaymentDraft
import dev.kortex.finance.domain.usecase.TransactionDraft
import dev.kortex.finance.domain.usecase.TransactionSaveResult
import dev.kortex.finance.ui.common.FinanceFormat
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.flow.first

/** What a finance tool tells the agent: [ok] false is a problem it should relay or fix. */
data class AgentAnswer(val ok: Boolean, val text: String)

/**
 * What the agent's finance tools do (docs/FINANCE_PLAN.md › Agent tools), on the same use cases
 * the screens use, so validation, merchant learning and sync behave the same. Answers are short
 * plain text for the model to read; amounts are in rupees.
 */
class FinanceAgent(
    private val observeFinance: ObserveFinance,
    private val addTransaction: AddTransaction,
    private val markPaid: MarkPaid,
    private val clock: Clock,
) {
    /** add_expense / add_income. [account] is a name or last 4 digits; with one account it may be left out. */
    suspend fun addEntry(
        income: Boolean,
        amountMinor: Long,
        account: String?,
        category: String?,
        merchant: String?,
        note: String?,
        date: LocalDate?,
    ): AgentAnswer {
        val snapshot = observeFinance().first()
        val picked = pickAccount(snapshot, account, allowCards = !income) ?: return accountProblem(snapshot, account, allowCards = !income)
        val kind = if (income) CategoryKind.INCOME else CategoryKind.EXPENSE
        val categoryUid = category?.let { name ->
            snapshot.categories.firstOrNull { it.kind == kind && (it.uid == name || it.name.equals(name.trim(), ignoreCase = true)) }?.uid
                ?: return AgentAnswer(false, "No ${kind.name.lowercase()} category is called \"$name\". Categories: ${snapshot.categories.filter { it.kind == kind }.joinToString { it.name }}.")
        }
        val day = date?.let { minOf(it, clock.today()) }
        val result = addTransaction(
            TransactionDraft(
                type = if (income) TransactionType.INCOME else TransactionType.EXPENSE,
                amountMinor = amountMinor,
                accountUid = picked.uid,
                categoryUid = categoryUid,
                merchant = merchant,
                note = note,
                occurredAtMillis = day?.let(clock::millisOn),
                source = TransactionSource.AGENT,
            ),
        )
        if (result !is TransactionSaveResult.Saved) return AgentAnswer(false, "Not saved: ${result::class.simpleName}.")
        val what = if (income) "income of ${money(amountMinor)}" else money(amountMinor)
        val where = merchant?.trim()?.takeIf { it.isNotEmpty() }?.let { if (income) " from $it" else " at $it" }.orEmpty()
        val categoryName = categoryUid?.let { uid -> snapshot.categoriesByUid[uid]?.name }?.let { " ($it)" }.orEmpty()
        return AgentAnswer(true, "Saved $what$where ${if (income) "into" else "from"} ${label(picked)} on ${FinanceFormat.day(day ?: clock.today())}$categoryName.")
    }

    /** mark_paid: the named recurring payment's next occurrence. */
    suspend fun markPaid(name: String, amountMinor: Long?, account: String?): AgentAnswer {
        val snapshot = observeFinance().first()
        val matches = snapshot.recurring.filter { !it.paused && it.name.contains(name.trim(), ignoreCase = true) }
        val recurring = matches.singleOrNull() ?: matches.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
            ?: return AgentAnswer(
                false,
                if (matches.isEmpty()) "No recurring payment matches \"$name\". Yours: ${snapshot.recurring.joinToString { it.name }.ifEmpty { "none" }}."
                else "Several match \"$name\": ${matches.joinToString { it.name }}. Say which.",
            )
        val accountUid = account?.let { pickAccount(snapshot, it, allowCards = true)?.uid ?: return accountProblem(snapshot, it, allowCards = true) }
        return when (val result = markPaid(recurring.uid, recurring.nextDueOn, PaymentDraft(amountMinor = amountMinor, accountUid = accountUid))) {
            is OccurrenceResult.Paid -> AgentAnswer(
                true,
                "Marked ${recurring.name} (due ${FinanceFormat.day(recurring.nextDueOn)}) paid: ${money(amountMinor ?: recurring.amountMinor)}. Next due ${FinanceFormat.day(result.nextDueOn)}.",
            )
            is OccurrenceResult.AlreadyPaid -> AgentAnswer(true, "${recurring.name} was already paid; next due ${FinanceFormat.day(result.nextDueOn)}.")
            is OccurrenceResult.Failed -> AgentAnswer(false, "Not marked paid: ${result.reason::class.simpleName}. Its account may have been deleted; give one.")
            else -> AgentAnswer(false, "Couldn't mark ${recurring.name} paid.")
        }
    }

    /**
     * spending_summary. [period] is "today", "this_week", "this_month", "last_month", "this_year",
     * or a month as "2026-09"; this month when null.
     */
    suspend fun spendingSummary(period: String?, category: String?): AgentAnswer {
        val today = clock.today()
        val (label, from, to) = periodOf(period, today) ?: return AgentAnswer(false, "Unknown period \"$period\". Use today, this_week, this_month, last_month, this_year or yyyy-MM.")
        val snapshot = observeFinance().first()
        val txs = snapshot.transactions
        if (category != null) {
            val match = snapshot.categories.firstOrNull { it.name.equals(category.trim(), ignoreCase = true) || it.uid == category }
                ?: return AgentAnswer(false, "No category is called \"$category\". Categories: ${snapshot.categories.joinToString { it.name }}.")
            val spent = Spending.expenses(txs, from, to).filter { it.categoryUid == match.uid }
            return AgentAnswer(true, "$label: ${money(spent.sumOf { it.amountMinor })} on ${match.name} across ${spent.size} ${if (spent.size == 1) "entry" else "entries"}.")
        }
        val period = Spending.period(txs, from, to)
        val shares = Spending.whereItWent(txs, snapshot.categories, from, to, top = 5)
        return AgentAnswer(
            true,
            buildString {
                append("$label: spent ${money(period.spentMinor)}, income ${money(period.incomeMinor)}")
                if (period.incomeMinor > 0) append(", kept ${money(period.savingsMinor)}")
                append(".")
                if (shares.isNotEmpty()) {
                    append(" Where it went: ")
                    append(shares.joinToString { "${it.category?.name ?: "Other"} ${money(it.amountMinor)} (${it.percent}%)" })
                    append(".")
                }
            },
        )
    }

    /** find_transactions: matches merchant, note or category name; newest first. */
    suspend fun findTransactions(query: String?, from: LocalDate?, to: LocalDate?, limit: Int = 20): AgentAnswer {
        val snapshot = observeFinance().first()
        val needle = query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val found = snapshot.transactions
            .filter { it.type != TransactionType.OPENING }
            .filter { (from == null || !it.occurredOn.isBefore(from)) && (to == null || !it.occurredOn.isAfter(to)) }
            .filter { tx ->
                needle == null || listOfNotNull(tx.merchant, tx.note, tx.categoryUid?.let { snapshot.categoriesByUid[it]?.name })
                    .any { it.lowercase().contains(needle) }
            }
            .sortedByDescending { it.occurredAtMillis }
        if (found.isEmpty()) return AgentAnswer(true, "No entries match.")
        val shown = found.take(limit.coerceIn(1, 50))
        val lines = shown.joinToString("\n") { tx ->
            val sign = when (tx.type) {
                TransactionType.INCOME -> "+"
                TransactionType.EXPENSE -> "-"
                else -> ""
            }
            val kind = when (tx.type) {
                TransactionType.TRANSFER -> " (transfer)"
                TransactionType.CARD_PAYMENT -> " (card bill payment)"
                else -> ""
            }
            val account = snapshot.accountsByUid[tx.accountUid]?.let(::label) ?: "a deleted account"
            "${FinanceFormat.day(tx.occurredOn)}: $sign${money(tx.amountMinor)} ${tx.merchant ?: "—"}$kind · $account" +
                (tx.categoryUid?.let { snapshot.categoriesByUid[it]?.name }?.let { " · $it" } ?: "")
        }
        val more = if (found.size > shown.size) "\n…and ${found.size - shown.size} more." else ""
        return AgentAnswer(true, "${found.size} ${if (found.size == 1) "entry" else "entries"}, ${money(found.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amountMinor })} spent:\n$lines$more")
    }

    /** list_pending: card bills and recurring payments due in the next 30 days. */
    suspend fun listPending(): AgentAnswer {
        val snapshot = observeFinance().first()
        val today = clock.today()
        val pending = Pending.summary(today, snapshot.statements, snapshot.recurring, snapshot.transactions)
        if (pending.items.isEmpty()) return AgentAnswer(true, "Nothing is due in the next 30 days.")
        val lines = pending.items.joinToString("\n") { item ->
            val name = if (item.kind == PendingKind.CARD_BILL) {
                snapshot.statements.find { it.uid == item.sourceUid }?.cardUid?.let(snapshot.accountsByUid::get)?.let { "${label(it)} bill" } ?: "Card bill"
            } else {
                item.title
            }
            val overdue = if (item.dueOn.isBefore(today)) " (overdue)" else ""
            "$name: ${money(item.amountMinor)} due ${FinanceFormat.weekdayDay(item.dueOn)}$overdue"
        }
        return AgentAnswer(true, "${money(pending.totalMinor)} due in the next 30 days:\n$lines")
    }

    /** The account [ref] names (uid, last 4, or name, exactly or in part); the only one when [ref] is null. */
    private fun pickAccount(snapshot: FinanceSnapshot, ref: String?, allowCards: Boolean): Account? {
        val accounts = snapshot.accounts.filter { !it.archived && (allowCards || it.kind != AccountKind.CREDIT_CARD) }
        if (ref.isNullOrBlank()) return accounts.singleOrNull()
        val text = ref.trim()
        accounts.firstOrNull { it.uid == text }?.let { return it }
        if (text.length == 4 && text.all(Char::isDigit)) return accounts.singleOrNull { it.last4 == text }
        return accounts.singleOrNull { it.name.equals(text, ignoreCase = true) }
            ?: accounts.filter { it.name.contains(text, ignoreCase = true) }.singleOrNull()
    }

    private fun accountProblem(snapshot: FinanceSnapshot, ref: String?, allowCards: Boolean): AgentAnswer {
        val accounts = snapshot.accounts.filter { !it.archived && (allowCards || it.kind != AccountKind.CREDIT_CARD) }
        if (accounts.isEmpty()) return AgentAnswer(false, "There are no accounts yet. Ask the user to add one in Finances › Accounts.")
        val list = accounts.joinToString { label(it) }
        return AgentAnswer(false, if (ref.isNullOrBlank()) "Which account? ${list}." else "No single account matches \"$ref\". Accounts: $list.")
    }

    private fun label(account: Account) = listOfNotNull(account.name, account.last4?.let { "••$it" }).joinToString(" ")

    private fun money(minor: Long) = FinanceFormat.rupees(minor)

    companion object {
        /** A period's label and its first and last day. */
        fun periodOf(period: String?, today: LocalDate): Triple<String, LocalDate, LocalDate>? {
            val month = YearMonth.from(today)
            return when (val p = period?.trim()?.lowercase()?.replace(' ', '_')) {
                null, "", "this_month", "month" -> Triple(FinanceFormat.monthYear(month) + " so far", month.atDay(1), today)
                "today" -> Triple("Today", today, today)
                "this_week", "week" -> Triple("The last 7 days", today.minusDays(6), today)
                "last_month" -> month.minusMonths(1).let { Triple(FinanceFormat.monthYear(it), it.atDay(1), it.atEndOfMonth()) }
                "this_year", "year" -> Triple("${today.year} so far", today.withDayOfYear(1), today)
                else -> runCatching { YearMonth.parse(p) }.getOrNull()?.let { Triple(FinanceFormat.monthYear(it), it.atDay(1), minOf(it.atEndOfMonth(), today)) }
            }
        }
    }
}
