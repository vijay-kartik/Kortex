package dev.kortex.finance

import dev.kortex.finance.domain.model.Account
import dev.kortex.finance.domain.model.AccountKind
import dev.kortex.finance.domain.model.CardStatement
import dev.kortex.finance.domain.model.Frequency
import dev.kortex.finance.domain.model.Recurring
import dev.kortex.finance.domain.model.RecurringKind
import dev.kortex.finance.domain.model.Transaction
import dev.kortex.finance.domain.model.TransactionType
import dev.kortex.finance.domain.port.Clock
import java.time.LocalDate
import java.time.ZoneId

/** The sample data on the Figma Finances screens: "today" is Tuesday 29 Sep 2026. */
object Fixtures {
    val TODAY: LocalDate = LocalDate.of(2026, 9, 29)
    val IST: ZoneId = ZoneId.of("Asia/Kolkata")

    val checking = account("checking", AccountKind.BANK, "Checking Account", last4 = "4471")
    val savings = account("savings", AccountKind.BANK, "Savings Vault", last4 = "2093")
    val cash = account("cash", AccountKind.CASH, "Personal Cash")
    val kortex = account("kortex", AccountKind.CREDIT_CARD, "KORTEX", last4 = "8824").copy(
        creditLimitMinor = 50_000_00,
        statementDay = 25,
        dueDay = 15,
    )

    fun account(uid: String, kind: AccountKind, name: String, last4: String? = null) =
        Account(uid = uid, kind = kind, name = name, last4 = last4, createdAtMillis = 0)

    private var seq = 0

    fun tx(
        type: TransactionType,
        amountMinor: Long,
        on: LocalDate,
        account: String,
        to: String? = null,
        category: String? = null,
        recurringUid: String? = null,
        dueOn: LocalDate? = null,
        statementUid: String? = null,
        atMillis: Long = on.atStartOfDay(IST).toInstant().toEpochMilli(),
    ) = Transaction(
        uid = "tx${seq++}",
        type = type,
        amountMinor = amountMinor,
        occurredAtMillis = atMillis,
        occurredOn = on,
        accountUid = account,
        toAccountUid = to,
        categoryUid = category,
        recurringUid = recurringUid,
        dueOn = dueOn,
        statementUid = statementUid,
    )

    fun monthly(uid: String, name: String, kind: RecurringKind, amountMinor: Long, next: LocalDate, account: String = "kortex") =
        Recurring(
            uid = uid,
            name = name,
            kind = kind,
            amountMinor = amountMinor,
            frequency = Frequency.MONTHLY,
            anchorDay = next.dayOfMonth,
            nextDueOn = next,
            accountUid = account,
            createdAtMillis = 0,
        )

    /** Netflix, Gym, Airtel Fiber and Spotify, as on Recurring 01 and Pending payments. */
    val recurring = listOf(
        monthly("netflix", "Netflix", RecurringKind.SUBSCRIPTION, 649_00, LocalDate.of(2026, 10, 3)),
        monthly("gym", "Gym membership", RecurringKind.FIXED, 1_500_00, LocalDate.of(2026, 10, 5), "checking"),
        monthly("airtel", "Airtel Fiber", RecurringKind.FIXED, 799_00, LocalDate.of(2026, 10, 10), "checking"),
        monthly("spotify", "Spotify", RecurringKind.SUBSCRIPTION, 119_00, LocalDate.of(2026, 10, 12)),
    )

    /** KORTEX's 25 Sep statement: ₹1,400 due Thu 15 Oct, minimum ₹200. */
    val kortexStatement = CardStatement(
        uid = "stmt-sep",
        cardUid = "kortex",
        periodStart = LocalDate.of(2026, 8, 26),
        statementOn = LocalDate.of(2026, 9, 25),
        dueOn = LocalDate.of(2026, 10, 15),
        totalDueMinor = 1_400_00,
        minDueMinor = 200_00,
        createdAtMillis = 0,
    )

    class FixedClock(private val millis: Long, private val zone: ZoneId = IST) : Clock {
        override fun nowMillis() = millis
        override fun zone() = zone
    }

    /** Noon on [TODAY] in India. */
    val clock = FixedClock(TODAY.atTime(12, 0).atZone(IST).toInstant().toEpochMilli())
}
