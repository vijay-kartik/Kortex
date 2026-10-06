package dev.kortex.finance.ui.read

import dev.kortex.finance.domain.model.InboxSms
import dev.kortex.finance.domain.model.ReviewReason
import dev.kortex.finance.ui.FinanceRoute
import dev.kortex.finance.ui.common.FinanceFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class SmsReviewRowUi(
    val id: String,
    /** "AX-HDFCBK". */
    val sender: String,
    /** Why it wasn't saved on its own: "Which account?". */
    val reason: String,
    /** "Today, 6:42 PM". */
    val received: String,
    val body: String,
)

/** To review (docs/SMS_AUTO_PLAN.md, phase 6): received bank SMS waiting to be checked, newest first. */
data class SmsReviewState(
    val loading: Boolean = true,
    val rows: List<SmsReviewRowUi> = emptyList(),
)

sealed interface SmsReviewIntent {
    /** Opens the Paste SMS review on it. */
    data class Open(val row: SmsReviewRowUi) : SmsReviewIntent

    /** Swiped away: not an entry. Undo puts it back. */
    data class Dismiss(val row: SmsReviewRowUi) : SmsReviewIntent
}

sealed interface SmsReviewEffect {
    data class Navigate(val route: FinanceRoute) : SmsReviewEffect
}

object SmsReviewUi {
    fun build(rows: List<InboxSms>, today: LocalDate, zone: ZoneId): SmsReviewState = SmsReviewState(
        loading = false,
        rows = rows.mapNotNull { sms ->
            val body = sms.body ?: return@mapNotNull null
            val day = Instant.ofEpochMilli(sms.receivedAtMillis).atZone(zone).toLocalDate()
            SmsReviewRowUi(
                id = sms.id,
                sender = sms.sender,
                reason = reasonLabel(sms.reason),
                received = "${FinanceFormat.relativeDay(day, today)}, ${FinanceFormat.time(sms.receivedAtMillis, zone)}",
                body = body,
            )
        },
    )

    fun reasonLabel(reason: ReviewReason?): String = when (reason) {
        ReviewReason.UNREADABLE -> "Couldn’t read it"
        ReviewReason.MODEL_READ -> "Read by AI · check it"
        ReviewReason.NO_ACCOUNT -> "Which account?"
        ReviewReason.DUPLICATE -> "Might already be added"
        ReviewReason.CARD -> "Card payment or refund"
        ReviewReason.LATE -> "Arrived days late"
        ReviewReason.BEFORE_ACCOUNT -> "From before you added this account"
        ReviewReason.NOT_SAVED -> "Couldn’t save it"
        ReviewReason.AUTO_OFF, null -> "Waiting for you"
    }
}
