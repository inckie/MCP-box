package com.damn.mcpbox.mcp

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

class McpConfigManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getPluginConfig(pluginId: String): PluginConfig {
        val jsonStr = prefs.getString(KEY_PLUGIN_PREFIX + pluginId, null) ?: return PluginConfig()
        return try {
            val json = JSONObject(jsonStr)
            val enabled = json.optBoolean("enabled", false)
            val toolsJson = json.optJSONObject("tools") ?: JSONObject()
            val toolConfigs = mutableMapOf<String, ToolConfig>()
            
            toolsJson.keys().forEach { toolName ->
                val toolJson = toolsJson.getJSONObject(toolName)
                val permStr = toolJson.optString("permission", McpPermission.ASK.name)
                val permission = try { McpPermission.valueOf(permStr) } catch (e: Exception) { McpPermission.ASK }
                toolConfigs[toolName] = ToolConfig(permission)
            }
            
            PluginConfig(enabled, toolConfigs)
        } catch (e: Exception) {
            PluginConfig()
        }
    }

    fun savePluginConfig(pluginId: String, config: PluginConfig) {
        val json = JSONObject()
        json.put("enabled", config.enabled)
        val toolsJson = JSONObject()
        config.toolConfigs.forEach { (name, toolConfig) ->
            val toolJson = JSONObject()
            toolJson.put("permission", toolConfig.permission.name)
            toolsJson.put(name, toolJson)
        }
        json.put("tools", toolsJson)
        prefs.edit().putString(KEY_PLUGIN_PREFIX + pluginId, json.toString()).apply()
    }

    fun getToolPermission(pluginId: String, toolName: String): McpPermission {
        val config = getPluginConfig(pluginId)
        if (!config.enabled) return McpPermission.BLOCK
        return config.toolConfigs[toolName]?.permission ?: McpPermission.ASK
    }

    companion object {
        private const val PREFS_NAME = "mcp_plugins_config"
        private const val KEY_PLUGIN_PREFIX = "plugin_"
    }
}
