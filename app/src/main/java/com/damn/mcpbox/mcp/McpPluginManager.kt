package com.damn.mcpbox.mcp

import android.content.Context
import com.damn.mcpbox.mcp.plugins.AccessibilityPlugin
import com.damn.mcpbox.mcp.plugins.CalendarPlugin
import com.damn.mcpbox.mcp.plugins.ContactsPlugin
import com.damn.mcpbox.mcp.plugins.MediaPlugin
import com.damn.mcpbox.mcp.plugins.SmsPlugin
import com.damn.mcpbox.mcp.plugins.SettingsPlugin
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class McpPluginManager(private val context: Context) {
    private val configManager = McpConfigManager(context)
    private val plugins = mutableMapOf<String, McpPlugin>()
    
    private val pendingRequests = mutableMapOf<Int, CompletableFuture<Boolean>>()
    private val requestIdCounter = AtomicInteger(1)

    interface PermissionRequester {
        fun requestPermission(requestId: Int, pluginId: String, toolName: String)
    }

    var permissionRequester: PermissionRequester? = null

    init {
        // Register known plugins
        registerPlugin(AccessibilityPlugin())
        registerPlugin(ContactsPlugin())
        registerPlugin(CalendarPlugin())
        registerPlugin(MediaPlugin())
        registerPlugin(SmsPlugin())
        registerPlugin(SettingsPlugin())
    }

    private fun registerPlugin(plugin: McpPlugin) {
        plugins[plugin.id] = plugin
    }

    fun getAllPlugins(): List<McpPlugin> = plugins.values.toList()

    fun getEnabledPlugins(): List<McpPlugin> {
        return plugins.values.filter { plugin ->
            val config = configManager.getPluginConfig(plugin.id)
            if (!config.enabled) return@filter false
            
            // Validate permissions are still granted
            plugin.requiredPermissions.all { perm ->
                androidx.core.content.ContextCompat.checkSelfPermission(context, perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        }
    }

    fun getPlugin(id: String): McpPlugin? = plugins[id]

    fun getTool(pluginId: String, toolName: String): McpTool? {
        return plugins[pluginId]?.getTools()?.find { it.name == toolName }
    }

    /**
     * Checks if a tool can be executed.
     * If permission is ASK, it triggers a request and waits for result.
     */
    fun checkAndRequestPermission(pluginId: String, toolName: String): Boolean {
        val permission = configManager.getToolPermission(pluginId, toolName)
        if (permission == McpPermission.ALLOW) return true
        if (permission == McpPermission.BLOCK) return false
        
        // Handle ASK
        val requester = permissionRequester ?: return false
        val requestId = requestIdCounter.getAndIncrement()
        val future = CompletableFuture<Boolean>()
        pendingRequests[requestId] = future
        
        requester.requestPermission(requestId, pluginId, toolName)
        
        return try {
            // Wait for user decision (timeout after 30 seconds)
            future.get(30, TimeUnit.SECONDS)
        } catch (e: Exception) {
            false
        } finally {
            pendingRequests.remove(requestId)
        }
    }

    fun onPermissionDecision(requestId: Int, allowed: Boolean) {
        pendingRequests[requestId]?.complete(allowed)
    }

    fun canExecute(pluginId: String, toolName: String): Boolean {
        val permission = configManager.getToolPermission(pluginId, toolName)
        return permission == McpPermission.ALLOW
    }
}
