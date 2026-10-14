package dev.kortex.finance.domain.port

import dev.kortex.finance.domain.usecase.StoredSms

/** The SMS already on the phone, for Settings › Add earlier SMS. Bound to Android's SMS store. */
fun interface PhoneSmsSource {
    /** The phone's received SMS since [fromMillis], oldest first. Needs `READ_SMS`. */
    fun storedSince(fromMillis: Long): List<StoredSms>
}
