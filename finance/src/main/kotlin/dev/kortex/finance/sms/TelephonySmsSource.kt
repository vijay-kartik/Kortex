package dev.kortex.finance.sms

import android.content.Context
import android.provider.Telephony
import dev.kortex.finance.domain.port.PhoneSmsSource
import dev.kortex.finance.domain.usecase.StoredSms

/** [PhoneSmsSource] on Android's SMS store (`Telephony.Sms.Inbox`). */
class TelephonySmsSource(private val context: Context) : PhoneSmsSource {

    override fun storedSince(fromMillis: Long): List<StoredSms> {
        val columns = arrayOf(Telephony.TextBasedSmsColumns.ADDRESS, Telephony.TextBasedSmsColumns.BODY, Telephony.TextBasedSmsColumns.DATE)
        val cursor = context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            columns,
            "${Telephony.TextBasedSmsColumns.DATE} >= ?",
            arrayOf(fromMillis.toString()),
            "${Telephony.TextBasedSmsColumns.DATE} ASC",
        ) ?: return emptyList()
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    val sender = it.getString(0) ?: continue
                    val body = it.getString(1) ?: continue
                    add(StoredSms(sender, body, it.getLong(2)))
                }
            }
        }
    }
}
