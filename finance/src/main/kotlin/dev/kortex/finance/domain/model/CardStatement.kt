package dev.kortex.finance.domain.model

import java.time.LocalDate

enum class StatementSource { AUTO, SMS, MANUAL }

/** Worked out from the card payments made against a statement; never stored. */
enum class StatementStatus { DUE, PARTLY_PAID, PAID, OVERDUE }

/**
 * A credit card bill (Figma: Credit Cards, Pay card bill). How much of it is paid comes from the
 * [TransactionType.CARD_PAYMENT]s that name it.
 */
data class CardStatement(
    val uid: String,
    val cardUid: String,
    val periodStart: LocalDate,
    val statementOn: LocalDate,
    val dueOn: LocalDate,
    val totalDueMinor: Long,
    val minDueMinor: Long,
    val source: StatementSource = StatementSource.AUTO,
    val createdAtMillis: Long,
    val updatedAtMillis: Long = createdAtMillis,
)

/** A payee's name and category, remembered across entries (Figma: Paste SMS 06). */
data class Merchant(
    val uid: String,
    val payeeKey: String,
    val displayName: String,
    /** The category last picked for it; suggested next time. */
    val categoryUid: String? = null,
    val updatedAtMillis: Long,
)
