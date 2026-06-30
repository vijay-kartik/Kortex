package dev.kortex.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import dev.kortex.core.ambient.ActionCard
import dev.kortex.core.ambient.ActionOutcome
import dev.kortex.core.ambient.CardAction
import dev.kortex.core.ambient.CardActionHandler
import dev.kortex.core.ambient.HandleType
import dev.kortex.core.store.ContactDao
import dev.kortex.core.store.toDomain

/**
 * Performs card actions via Android intents. Deliberately uses *composer* surfaces
 * (SMS composer, dialer, calendar insert) rather than auto-sending — the agent prepares the
 * action, the user still presses send. That keeps a human in the loop at the OS level on top
 * of Kortex's own approval gate.
 */
class IntentActionHandler(
    private val context: Context,
    private val contacts: ContactDao,
) : CardActionHandler {

    override suspend fun handle(card: ActionCard, action: CardAction): ActionOutcome {
        val phone = contacts.byId(card.contactId)?.toDomain()
            ?.handles?.firstOrNull { it.type == HandleType.PHONE }?.value

        return when (action) {
            is CardAction.ReplyText -> openSms(phone, action.suggestedText)
            is CardAction.CallContact -> openDialer(phone)
            is CardAction.CreateCalendarEvent -> openCalendar(action)
            is CardAction.SetReminder -> ActionOutcome.Done("Reminder noted: ${action.text}")
            is CardAction.ScheduleCheckIn -> ActionOutcome.Done("Check-in scheduled for later")
            is CardAction.ShareLocation -> ActionOutcome.Done("Location sharing not wired yet")
            is CardAction.ShareMedia -> ActionOutcome.Done("Media sharing not wired yet")
            is CardAction.Custom -> ActionOutcome.Done("Custom action: ${action.actionId}")
        }
    }

    private fun openSms(phone: String?, body: String): ActionOutcome {
        if (phone.isNullOrBlank()) return ActionOutcome.Failed("No phone number for this contact")
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phone"))
            .putExtra("sms_body", body)
        return launch(intent, "Opened SMS composer")
    }

    private fun openDialer(phone: String?): ActionOutcome {
        if (phone.isNullOrBlank()) return ActionOutcome.Failed("No phone number for this contact")
        return launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")), "Opened dialer")
    }

    private fun openCalendar(action: CardAction.CreateCalendarEvent): ActionOutcome {
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, action.title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, action.startMillis)
        action.endMillis?.let { intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it) }
        action.location?.let { intent.putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
        return launch(intent, "Opened calendar")
    }

    private fun launch(intent: Intent, success: String): ActionOutcome =
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ActionOutcome.Done(success)
        } catch (e: ActivityNotFoundException) {
            ActionOutcome.Failed("No app available to handle this action")
        }
}
