package dev.kortex.app.tools

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import dev.kortex.core.tool.RiskLevel
import dev.kortex.core.tool.Tool
import dev.kortex.core.tool.ToolResult
import dev.kortex.core.tool.int
import dev.kortex.core.tool.string
import dev.kortex.core.tool.tool

/**
 * A tool that creates a reminder on the user's phone by setting an alarm with a label.
 */
fun reminderTool(context: Context): Tool = tool(
    name = "create_reminder",
    description = "Creates a reminder on the user's phone by setting an alarm with a specific label. Use this to remind the user about something at a specific time.",
) {
    param("message", "string", "The reminder message or label.")
    param("hour", "integer", "The hour (0-23) for the reminder in 24-hour format.")
    param("minute", "integer", "The minute (0-59) for the reminder.")

    risk(RiskLevel.LOW)

    execute { args ->
        val message = args.string("message")
        val hour = args.int("hour", 0)
        val minute = args.int("minute", 0)

        runCatching {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_MESSAGE, message)
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult(true, "Reminder set for ${String.format("%02d:%02d", hour, minute)} with message: '$message'.")
        }.getOrElse {
            ToolResult(false, "Failed to create reminder: ${it.message}")
        }
    }
}
