package dev.kortex.app.tools

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import dev.kortex.core.tool.RiskLevel
import dev.kortex.core.tool.Tool
import dev.kortex.core.tool.ToolResult
import dev.kortex.core.tool.string
import dev.kortex.core.tool.stringOrNull
import dev.kortex.core.tool.tool
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A tool that creates a calendar event on the device, allowing the inclusion of clickable links in the description.
 */
fun calendarEventTool(context: Context): Tool = tool(
    name = "create_calendar_event",
    description = "Creates a calendar event on the device. start_time and end_time must be in ISO-8601 format (e.g. 2026-07-27T15:30:00). Use this for detailed events or when the user wants to include links/URLs in the description.",
) {
    param("title", "string", "Title of the event.")
    param("description", "string", "Description of the event. Can include URLs or links.", required = false)
    param("start_time", "string", "Start time in ISO-8601 format (e.g., '2026-07-27T15:30:00').")
    param("end_time", "string", "End time in ISO-8601 format (e.g., '2026-07-27T16:30:00').")

    risk(RiskLevel.LOW)

    execute { args ->
        val title = args.string("title")
        val description = args.stringOrNull("description") ?: ""
        val startTimeStr = args.string("start_time")
        val endTimeStr = args.string("end_time")

        runCatching {
            val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
            val startMillis = LocalDateTime.parse(startTimeStr, formatter)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
            val endMillis = LocalDateTime.parse(endTimeStr, formatter)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()

            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMillis)
                putExtra(CalendarContract.Events.TITLE, title)
                putExtra(CalendarContract.Events.DESCRIPTION, description)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult(true, "Calendar event '$title' created successfully.")
        }.getOrElse {
            ToolResult(false, "Failed to create calendar event: ${it.message}")
        }
    }
}
