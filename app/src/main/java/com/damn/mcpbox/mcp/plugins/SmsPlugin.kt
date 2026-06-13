package com.damn.mcpbox.mcp.plugins

import android.content.Context
import android.provider.Telephony
import com.damn.mcpbox.mcp.McpPlugin
import com.damn.mcpbox.mcp.McpTool
import org.json.JSONArray
import org.json.JSONObject

class SmsPlugin : McpPlugin {
    override val id: String = "sms"
    override val name: String = "SMS"
    override val description: String = "Read text messages"
    
    override val requiredPermissions: List<String> = listOf(android.Manifest.permission.READ_SMS)

    override fun getTools(): List<McpTool> = listOf(
        ListMessagesTool(),
        SearchMessagesTool()
    )

    class ListMessagesTool : McpTool {
        override val name: String = "list_messages"
        override val description: String = "Lists recent SMS messages"
        override val inputSchema: JSONObject = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().put("limit", JSONObject().put("type", "integer")))

        override fun call(context: Context, arguments: JSONObject): JSONObject {
            val limit = arguments.optInt("limit", 20)
            val messages = JSONArray()
            val projection = arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE
            )
            
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection, null, null, "${Telephony.Sms.DATE} DESC"
            )?.use { cursor ->
                val addrIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)

                var count = 0
                while (cursor.moveToNext() && count < limit) {
                    messages.put(JSONObject()
                        .put("address", cursor.getString(addrIdx))
                        .put("body", cursor.getString(bodyIdx))
                        .put("date", cursor.getLong(dateIdx))
                    )
                    count++
                }
            }
            return JSONObject().put("messages", messages)
        }
    }

    class SearchMessagesTool : McpTool {
        override val name: String = "search_messages"
        override val description: String = "Search for messages containing a specific keyword"
        override val inputSchema: JSONObject = JSONObject()
            .put("type", "object")
            .put("required", JSONArray().put("query"))
            .put("properties", JSONObject().put("query", JSONObject().put("type", "string")))

        override fun call(context: Context, arguments: JSONObject): JSONObject {
            val query = arguments.optString("query")
            val messages = JSONArray()
            val selection = "${Telephony.Sms.BODY} LIKE ?"
            val selectionArgs = arrayOf("%$query%")
            
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                null, selection, selectionArgs, "${Telephony.Sms.DATE} DESC"
            )?.use { cursor ->
                val addrIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)

                while (cursor.moveToNext() && messages.length() < 50) {
                    messages.put(JSONObject()
                        .put("address", cursor.getString(addrIdx))
                        .put("body", cursor.getString(bodyIdx))
                        .put("date", cursor.getLong(dateIdx))
                    )
                }
            }
            return JSONObject().put("results", messages)
        }
    }
}
