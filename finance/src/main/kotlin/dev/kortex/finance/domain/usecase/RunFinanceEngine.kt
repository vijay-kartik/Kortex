package dev.kortex.finance.domain.usecase

import dev.kortex.finance.domain.FinanceIds
import dev.kortex.finance.domain.calc.Statements
import dev.kortex.finance.domain.model.AccountKind
import dev.kortex.finance.domain.port.Clock
import dev.kortex.finance.domain.repository.FinanceRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class EngineRun(val statementsCreated: Int, val autoPaid: Int, val caughtUp: Int)

/**
 * The recurring & statement engine, run when Finances opens (docs/FINANCE_PLAN.md › How entries
 * get in). Everything it writes has a derived id, so running it twice, or on two phones, writes
 * each record once:
 * - each credit card whose statement day passed gets an AUTO statement for what it owed that day;
 * - each auto-debit recurring payment due by today is marked paid on its due day;
 * - a recurring payment whose next occurrence was paid elsewhere moves its due date past it.
 */
class RunFinanceEngine(
    private val repository: FinanceRepository,
    private val markPaid: MarkPaid,
    private val clock: Clock,
) {
    private val running = Mutex()

    suspend operator fun invoke(): EngineRun = running.withLock {
        EngineRun(
            statementsCreated = createStatements(),
            autoPaid = payAutoDebits(),
            caughtUp = catchUp(),
        )
    }

    private suspend fun createStatements(): Int {
        val today = clock.today()
        val accounts = repository.observeAccounts().first()
        val transactions = repository.observeTransactions().first()
        val statements = repository.observeStatements().first()
        val byUid = accounts.associateBy { it.uid }
        val created = accounts.filter { it.kind == AccountKind.CREDIT_CARD && !it.archived }.flatMap { card ->
            var previous = Statements.latest(card.uid, statements)?.statementOn
            Statements.statementDaysToCreate(card, previous, today).mapNotNull { on ->
                Statements.autoStatement(card, on, previous, transactions, byUid, clock.nowMillis()).also { previous = on }
            }
        }
        repository.addStatementsIfAbsent(created)
        return created.size
    }

    private suspend fun payAutoDebits(): Int {
        val today = clock.today()
        var paid = 0
        repository.observeRecurring().first().filter { it.autoMarkPaid && !it.paused }.forEach { start ->
            var dueOn = start.nextDueOn
            repeat(Occurrences.MAX_CATCH_UP) {
                if (dueOn.isAfter(today)) return@forEach
                when (val result = markPaid(start.uid, dueOn, PaymentDraft(paidOn = dueOn))) {
                    is OccurrenceResult.Paid -> {
                        paid++
                        dueOn = result.nextDueOn
                    }
                    is OccurrenceResult.AlreadyPaid -> dueOn = result.nextDueOn
                    // No account to pay from (deleted): it waits in Pending, "needs an account".
                    else -> return@forEach
                }
            }
        }
        return paid
    }

    private suspend fun catchUp(): Int {
        var moved = 0
        repository.observeRecurring().first().filterNot { it.autoMarkPaid || it.paused }.forEach { recurring ->
            if (repository.getTransaction(FinanceIds.recurringOccurrence(recurring.uid, recurring.nextDueOn)) == null) return@forEach
            val next = Occurrences.nextUnpaid(repository, recurring, recurring.nextDueOn)
            repository.saveRecurringChange(recurring.copy(nextDueOn = next))
            moved++
        }
        return moved
    }
}
