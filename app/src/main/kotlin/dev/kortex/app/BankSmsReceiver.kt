package dev.kortex.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import dev.kortex.finance.sms.BankSms

/**
 * Every received SMS (docs/SMS_AUTO_PLAN.md, phase 4). Hands the broadcast to [BankSms], which
 * drops it unless Settings › "Add bank SMS automatically" is on and the sender looks like a bank.
 * Only the system can send this broadcast: the manifest requires `BROADCAST_SMS` of the sender.
 */
class BankSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        // The SMS is stored off the main thread; keep the process alive until it is.
        val pending = goAsync()
        BankSms.receive(context, intent, onDone = pending::finish)
    }
}
