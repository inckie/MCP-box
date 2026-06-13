package com.damn.mcpbox.mcp.plugins

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import com.damn.mcpbox.mcp.McpPlugin
import com.damn.mcpbox.mcp.McpTool
import org.json.JSONObject

class SettingsPlugin : McpPlugin {
    override val id: String = "settings"
    override val name: String = "Settings"
    override val description: String = "Check device system and hardware status"
    
    override fun getTools(): List<McpTool> = listOf(
        DeviceInfoTool(),
        BatteryStatusTool()
    )

    class DeviceInfoTool : McpTool {
        override val name: String = "get_device_info"
        override val description: String = "Returns basic hardware and OS information"
        override val inputSchema: JSONObject = JSONObject().put("type", "object").put("properties", JSONObject())

        override fun call(context: Context, arguments: JSONObject): JSONObject {
            return JSONObject()
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("brand", Build.BRAND)
                .put("android_version", Build.VERSION.RELEASE)
                .put("sdk_version", Build.VERSION.SDK_INT)
        }
    }

    class BatteryStatusTool : McpTool {
        override val name: String = "get_battery_status"
        override val description: String = "Returns current battery level and charging status"
        override val inputSchema: JSONObject = JSONObject().put("type", "object").put("properties", JSONObject())

        override fun call(context: Context, arguments: JSONObject): JSONObject {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val isCharging = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                bm.isCharging
            } else {
                false // Simplified for older versions
            }
            return JSONObject()
                .put("level", level)
                .put("is_charging", isCharging)
        }
    }
}
