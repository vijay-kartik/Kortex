package dev.kortex.finance.domain.port

/**
 * Whether finance notifications must keep their details to the app: while App lock is on they say
 * "Saved an entry" or "A payment is due", with no amount, merchant, account or category, since the
 * lock screen and the shade of a locked phone would show them. Bound by the app, which owns App
 * lock. Read when each notification is posted, so turning the lock on or off applies at once.
 */
fun interface NotificationPrivacy {
    fun redact(): Boolean
}
