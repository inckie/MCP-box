package com.damn.mcpbox.mcp.plugins

import android.content.Context
import android.provider.ContactsContract
import com.damn.mcpbox.mcp.McpPlugin
import com.damn.mcpbox.mcp.McpTool
import org.json.JSONArray
import org.json.JSONObject

class ContactsPlugin : McpPlugin {
    override val id: String = "contacts"
    override val name: String = "Contacts"
    override val description: String = "Read device contacts"
    override val requiredPermissions: List<String> = listOf(android.Manifest.permission.READ_CONTACTS)

    override fun getTools(): List<McpTool> {
        return listOf(ListContactsTool())
    }

    class ListContactsTool : McpTool {
        override val name: String = "list"
        override val description: String = "Lists all contacts with names and phone numbers"
        override val inputSchema: JSONObject = JSONObject().put("type", "object").put("properties", JSONObject())

        override fun call(context: Context, arguments: JSONObject): JSONObject {
            val contacts = JSONArray()
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                null, null, null, null
            )
            
            cursor?.use {
                val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val phoneIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                
                while (it.moveToNext()) {
                    val name = it.getString(nameIdx)
                    val phone = it.getString(phoneIdx)
                    contacts.put(JSONObject().put("name", name).put("phone", phone))
                }
            }
            
            return JSONObject().put("contacts", contacts)
        }
    }
}
