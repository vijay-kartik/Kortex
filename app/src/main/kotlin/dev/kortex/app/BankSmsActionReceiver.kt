package dev.kortex.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.kortex.finance.sms.BankSms
import dev.kortex.finance.sms.BankSmsNotifications

/**
 * Undo on a "Saved ₹420 at Swiggy" notification (docs/SMS_AUTO_PLAN.md, phase 5), without opening
 * the app. Not exported: only this app's own notifications can send it.
 */
class BankSmsActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BankSmsNotifications.ACTION_UNDO) return
        val pending = goAsync()
        BankSms.undo(context, intent, onDone = pending::finish)
    }
}
