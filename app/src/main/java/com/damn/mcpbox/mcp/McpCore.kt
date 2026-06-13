package com.damn.mcpbox.mcp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Permission state for an MCP tool.
 */
enum class McpPermission {
    ALLOW,
    ASK,
    BLOCK
}

/**
 * Interface for a single tool within a plugin.
 */
interface McpTool {
    val name: String
    val description: String
    val inputSchema: JSONObject

    /**
     * Executes the tool.
     * @param context Android context
     * @param arguments JSON arguments passed to the tool
     * @return Result as a JSONObject
     */
    fun call(context: Context, arguments: JSONObject): JSONObject
}

/**
 * Interface for an MCP plugin which groups several tools.
 */
interface McpPlugin {
    val id: String
    val name: String
    val description: String
    
    /**
     * List of Android permissions required by this plugin.
     */
    val requiredPermissions: List<String> get() = emptyList()

    fun getTools(): List<McpTool>
    
    /**
     * Optional: Called when the plugin is enabled or disabled.
     */
    fun onStateChanged(context: Context, enabled: Boolean) {}
}

/**
 * Information about a tool's current configuration.
 */
data class ToolConfig(
    val permission: McpPermission = McpPermission.ASK
)

/**
 * Information about a plugin's current configuration.
 */
data class PluginConfig(
    val enabled: Boolean = false,
    val toolConfigs: Map<String, ToolConfig> = emptyMap()
)
