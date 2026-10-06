package dev.kortex.finance.ui.accounts

import dev.kortex.finance.domain.calc.Balances
import dev.kortex.finance.domain.calc.DueUrgency
import dev.kortex.finance.domain.calc.Pending
import dev.kortex.finance.domain.calc.Statements
import dev.kortex.finance.domain.model.Account
import dev.kortex.finance.domain.model.AccountKind
import dev.kortex.finance.domain.model.StatementStatus
import dev.kortex.finance.domain.usecase.FinanceSnapshot
import dev.kortex.finance.ui.FinanceRoute
import dev.kortex.finance.ui.common.FinanceFormat
import java.time.LocalDate
import kotlin.math.roundToInt

/** A row on Accounts (Figma: finances-accounts). */
data class AccountRowUi(
    val uid: String,
    val kind: AccountKind,
    val name: String,
    val subtitle: String,
    val balanceMinor: Long,
    val lastActivity: String,
)

data class AccountsState(
    val loading: Boolean = true,
    val totalMinor: Long = 0,
    val rows: List<AccountRowUi> = emptyList(),
)

sealed interface AccountsIntent {
    data object Add : AccountsIntent
    data class Open(val uid: String) : AccountsIntent
}

sealed interface AccountsEffect {
    data class Navigate(val route: FinanceRoute) : AccountsEffect
}

object AccountsUi {
    /** Bank, cash, wallets and debit cards. Credit cards live on their own tab. */
    fun build(snapshot: FinanceSnapshot, today: LocalDate): AccountsState {
        val accounts = snapshot.accounts.filter { it.kind != AccountKind.CREDIT_CARD && !it.archived }
        val byUid = snapshot.accountsByUid
        return AccountsState(
            loading = false,
            totalMinor = Balances.totalBalanceMinor(snapshot.accounts, snapshot.transactions),
            rows = accounts.map { account ->
                val ledger = Balances.ledgerOf(account.uid, byUid)
                val last = snapshot.transactions
                    .filter { Balances.ledgerOf(it.accountUid, byUid) == ledger || it.toAccountUid?.let { to -> Balances.ledgerOf(to, byUid) } == ledger }
                    .maxByOrNull { it.occurredAtMillis }
                AccountRowUi(
                    uid = account.uid,
                    kind = account.kind,
                    name = account.name,
                    subtitle = subtitle(account, byUid),
                    balanceMinor = Balances.balanceMinor(account, snapshot.transactions, byUid),
                    lastActivity = last?.let { "Last transaction ${FinanceFormat.relativeDay(it.occurredOn, today)}" } ?: "No transactions yet",
                )
            },
        )
    }

    fun subtitle(account: Account, accounts: Map<String, Account>): String {
        val number = account.last4?.let { "••$it" }
        return when (account.kind) {
            AccountKind.CASH -> "Cash"
            AccountKind.WALLET -> listOfNotNull(account.institution ?: "Wallet", number).joinToString(" ")
            AccountKind.DEBIT_CARD -> listOfNotNull(
                "Debit card",
                account.linkedAccountUid?.let(accounts::get)?.let { "· from ${it.name}" },
            ).joinToString(" ")
            else -> listOfNotNull(account.institution, number).joinToString(" ").ifEmpty { "Bank account" }
        }
    }
}

/** A credit card's bill (Figma: Credit Cards › Credit limit overview). */
data class StatementUi(
    val statementUid: String,
    val statementOn: String,
    val dueOn: LocalDate,
    val dueLabel: String,
    /** 5 Oct: the All cards sheet's "Overdue · was due 5 Oct". */
    val dueShort: String,
    /** Unpaid and due within [Pending.SOON_DAYS]. */
    val dueSoon: Boolean,
    val unpaidMinor: Long,
    val minDueMinor: Long,
    val status: StatementStatus,
)

data class CardUi(
    val uid: String,
    val name: String,
    val last4: String?,
    /** A full number is kept, encrypted, and can be revealed. */
    val hasSecret: Boolean = false,
    val holder: String?,
    val expiry: String?,
    val network: String?,
    val limitMinor: Long?,
    val outstandingMinor: Long,
    val availableMinor: Long?,
    /** Rounded to one decimal: 2.9. */
    val utilisationPercent: Double?,
    val statement: StatementUi?,
    val spentSinceMinor: Long,
    val billingCycle: String?,
    val dueDay: String?,
)

data class CardsState(
    val loading: Boolean = true,
    val cards: List<CardUi> = emptyList(),
    /** Full numbers shown after the screen lock, by card uid; each hides again after a while. */
    val revealed: Map<String, String> = emptyMap(),
    /** The card in front of the rail; the rail reopens on it. */
    val frontUid: String? = null,
    /** Unpaid statement amounts across every card (Figma: Many cards › Due across cards). */
    val dueMinor: Long = 0,
    val overdueCount: Int = 0,
    /** The soonest unpaid bill that isn't overdue yet: Thu, 15 Oct. */
    val nextDueLabel: String? = null,
) {
    val frontIndex: Int get() = cards.indexOfFirst { it.uid == frontUid }.coerceAtLeast(0)
}

/** The All cards sheet's order: overdue, then soonest due, then paid or not yet billed. */
fun List<CardUi>.byUrgency(): List<CardUi> = sortedWith(
    compareBy<CardUi> {
        when (it.statement?.status) {
            StatementStatus.OVERDUE -> 0
            StatementStatus.DUE, StatementStatus.PARTLY_PAID -> 1
            else -> 2
        }
    }.thenBy { it.statement?.takeIf { s -> s.status != StatementStatus.PAID }?.dueOn },
)

sealed interface CardsIntent {
    data object AddCard : CardsIntent
    data class Edit(val uid: String) : CardsIntent
    data class PayBill(val statementUid: String) : CardsIntent

    /** The rail settled on this card. */
    data class Front(val uid: String) : CardsIntent

    /** The screen lock was passed: show this card's full number. */
    data class Reveal(val uid: String) : CardsIntent
    data class Hide(val uid: String) : CardsIntent
}

sealed interface CardsEffect {
    data class Navigate(val route: FinanceRoute) : CardsEffect
}

object CardsUi {
    fun build(snapshot: FinanceSnapshot, today: LocalDate): CardsState {
        val byUid = snapshot.accountsByUid
        val cards = snapshot.accounts.filter { it.kind == AccountKind.CREDIT_CARD && !it.archived }.map { card ->
            val position = Balances.cardPosition(card, snapshot.transactions, byUid)
            val statement = Statements.latest(card.uid, snapshot.statements)
            CardUi(
                uid = card.uid,
                name = card.name,
                last4 = card.last4,
                hasSecret = card.hasSecret,
                holder = card.holder,
                expiry = card.expiry,
                network = card.network,
                limitMinor = card.creditLimitMinor,
                outstandingMinor = position.outstandingMinor,
                availableMinor = position.availableMinor,
                utilisationPercent = position.utilisation?.let { (it * 1000).roundToInt() / 10.0 },
                statement = statement?.let {
                    StatementUi(
                        statementUid = it.uid,
                        statementOn = FinanceFormat.day(it.statementOn),
                        dueOn = it.dueOn,
                        dueLabel = FinanceFormat.weekdayDay(it.dueOn),
                        dueShort = FinanceFormat.day(it.dueOn),
                        dueSoon = Pending.urgency(it.dueOn, today) == DueUrgency.SOON,
                        unpaidMinor = Statements.unpaidMinor(it, snapshot.transactions),
                        minDueMinor = it.minDueMinor,
                        status = Statements.status(it, snapshot.transactions, today),
                    )
                },
                spentSinceMinor = statement?.let { Statements.spentSinceMinor(card, it, snapshot.transactions, byUid) } ?: position.outstandingMinor,
                billingCycle = card.statementDay?.let { day ->
                    val start = if (day >= 28) 1 else day + 1
                    "${FinanceFormat.ordinal(start)} – ${FinanceFormat.ordinal(day)} of every month"
                },
                dueDay = card.dueDay?.let { "${FinanceFormat.ordinal(it)} of every month" },
            )
        }
        val unpaid = cards.mapNotNull { it.statement }.filter { it.status != StatementStatus.PAID }
        return CardsState(
            loading = false,
            cards = cards,
            dueMinor = unpaid.sumOf { it.unpaidMinor },
            overdueCount = unpaid.count { it.status == StatementStatus.OVERDUE },
            nextDueLabel = unpaid.filter { it.status != StatementStatus.OVERDUE }.minByOrNull { it.dueOn }?.dueLabel,
        )
    }
}
