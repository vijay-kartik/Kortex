package dev.kortex.finance.domain.port

/** The "N bank SMS to review" notification. Bound to Android's notifications. */
fun interface ReviewNotices {
    /** To review was opened, or the inbox cleared: the review notification has done its job. */
    fun cancelReview()
}
