package dev.kortex.finance.domain.model

/** Where a received bank SMS has got to (docs/SMS_AUTO_PLAN.md). */
enum class InboxStatus {
    /** Received, not read yet. */
    PENDING,

    /** Waiting in To review, for the reason it carries. */
    REVIEW,

    /** Became an entry, saved on its own or from review. */
    SAVED,

    /** Undone, skipped or swiped away. */
    DISMISSED,

    /** An OTP, an advert, or a bank message that isn't a payment. */
    NOT_PAYMENT,
    ;

    /** Done with: the text is cleared, and the row goes once it's old. */
    val handled: Boolean get() = this == SAVED || this == DISMISSED || this == NOT_PAYMENT
}

/** Why a received SMS wasn't saved on its own. */
enum class ReviewReason {
    /** Neither the patterns nor the models could read it; the models may have been unreachable. */
    UNREADABLE,

    /** The LLM read it, so it's checked before it counts. */
    MODEL_READ,

    /** None of your cards or accounts has its last 4, or more than one does. */
    NO_ACCOUNT,

    /** It looks like an entry that's already there. */
    DUPLICATE,

    /** A card payment or refund. */
    CARD,

    /** It's dated more than a few days back. */
    LATE,

    /**
     * It's from before the account was added here. The account's opening balance already counts
     * that money, so saving it would count it twice.
     */
    BEFORE_ACCOUNT,

    /** "Save clear ones without asking" is off. */
    AUTO_OFF,

    /** Read fine, but saving turned it down (an account archived meanwhile, say). */
    NOT_SAVED,
}

/**
 * A received bank SMS, kept on this phone only: never synced. [id] is the id its entry gets, so
 * the same SMS received twice is one row, and pasting it afterwards finds the entry it became.
 */
data class InboxSms(
    val id: String,
    val sender: String,
    /** Null once [status] is handled. */
    val body: String?,
    val receivedAtMillis: Long,
    val status: InboxStatus = InboxStatus.PENDING,
    val reason: ReviewReason? = null,
    val transactionUid: String? = null,
    /** Read from the phone's earlier SMS (Settings › Add earlier SMS), not heard as it arrived. */
    val imported: Boolean = false,
)
