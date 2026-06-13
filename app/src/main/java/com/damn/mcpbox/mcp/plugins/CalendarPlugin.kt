package com.damn.mcpbox.mcp.plugins

import android.content.Context
import android.provider.CalendarContract
import com.damn.mcpbox.mcp.McpPlugin
import com.damn.mcpbox.mcp.McpTool
import org.json.JSONArray
import org.json.JSONObject

class CalendarPlugin : McpPlugin {
    override val id: String = "calendar"
    override val name: String = "Calendar"
    override val description: String = "Read device calendar events"
    override val requiredPermissions: List<String> = listOf(android.Manifest.permission.READ_CALENDAR)

    override fun getTools(): List<McpTool> {
        return listOf(ListEventsTool())
    }

    class ListEventsTool : McpTool {
        override val name: String = "list_events"
        override val description: String = "Lists calendar events"
        override val inputSchema: JSONObject = JSONObject().put("type", "object").put("properties", JSONObject())

        override fun call(context: Context, arguments: JSONObject): JSONObject {
            val events = JSONArray()
            val cursor = context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(
                    CalendarContract.Events.TITLE,
                    CalendarContract.Events.DTSTART,
                    CalendarContract.Events.DTEND,
                    CalendarContract.Events.EVENT_LOCATION
                ),
                null, null, null
            )
            
            cursor?.use {
                while (it.moveToNext()) {
                    events.put(JSONObject()
                        .put("title", it.getString(0))
                        .put("start", it.getLong(1))
                        .put("end", it.getLong(2))
                        .put("location", it.getString(3))
                    )
                }
            }
            return JSONObject().put("events", events)
        }
    }
}
