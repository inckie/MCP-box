package com.damn.mcpbox.mcp.plugins

import android.content.Context
import android.content.Intent
import android.os.Build
import com.damn.mcpbox.mcp.McpPlugin
import com.damn.mcpbox.mcp.McpTool
import com.damn.mcpbox.service.FloatingVirtualDisplayService
import com.damn.mcpbox.service.ServiceActions
import org.json.JSONArray
import org.json.JSONObject

class SystemPlugin : McpPlugin {
    override val id: String = "system"
    override val name: String = "System"
    override val description: String = "System-level controls: app launching, overlays, and device state"

    override fun getTools(): List<McpTool> = listOf(
        OverlayShowTool(),
        OverlayHideTool(),
        ListAppsTool(),
        LaunchTool(),
        KeepScreenOnTool()
    )

    class OverlayShowTool : McpTool {
        override val name: String = "overlay_show"
        override val description: String = "Shows floating overlay"
        override val inputSchema: JSONObject = JSONObject().put("type", "object").put("properties", JSONObject())

        override fun call(context: Context, arguments: JSONObject): JSONObject {
            val intent = Intent(context, FloatingVirtualDisplayService::class.java)
                .setAction(ServiceActions.ACTION_SHOW_OVERLAY)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            return JSONObject().put("ok", true)
        }
    }

    class OverlayHideTool : McpTool {
        override val name: String = "overlay_hide"
        override val description: String = "Hides floating overlay"
        override val inputSchema: JSONObject = JSONObject().put("type", "object").put("properties", JSONObject())

        override fun call(context: Context, arguments: JSONObject): JSONObject {
            val intent = Intent(context, FloatingVirtualDisplayService::class.java)
                .setAction(ServiceActions.ACTION_HIDE_OVERLAY)
            context.startService(intent)
            return JSONObject().put("ok", true)
        }
    }

    class ListAppsTool : McpTool {
        override val name: String = "list_apps"
        override val description: String = "Lists launchable installed apps"
        override val inputSchema: JSONObject = JSONObject().put("type", "object").put("properties", JSONObject())

        override fun call(context: Context, arguments: JSONObject): JSONObject {
            val pm = context.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
            val apps = JSONArray()

            for (info in resolveInfos) {
                val packageName = info.activityInfo.packageName
                val label = info.loadLabel(pm).toString()
                var version = "unknown"
                try {
                    val packageInfo = pm.getPackageInfo(packageName, 0)
                    version = packageInfo.versionName ?: "unknown"
                } catch (_: Exception) {
                    // Ignore
                }

                apps.put(JSONObject()
                    .put("name", label)
                    .put("bundle", packageName)
                    .put("version", version))
            }

            return JSONObject().put("apps", apps)
        }
    }

    class LaunchTool : McpTool {
        override val name: String = "launch"
        override val description: String = "Launches an app package"
        override val inputSchema: JSONObject = JSONObject()
            .put("type", "object")
            .put("required", JSONArray().put("packageName"))
            .put("properties", JSONObject()
                .put("packageName", JSONObject().put("type", "string"))
                .put("target", JSONObject().put("type", "string").put("description", "Target display: 'main' or 'virtual'")))

        override fun call(context: Context, arguments: JSONObject): JSONObject {
            val packageName = arguments.optString("packageName")
            if (packageName.isBlank()) {
                return JSONObject().put("ok", false).put("error", "packageName is required")
            }
            val target = arguments.optString("target").ifBlank { "main" }

            return if (target.equals("virtual", ignoreCase = true)) {
                val intent = Intent(context, FloatingVirtualDisplayService::class.java)
                    .setAction(ServiceActions.ACTION_LAUNCH_PACKAGE)
                    .putExtra(ServiceActions.EXTRA_PACKAGE_NAME, packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                JSONObject().put("ok", true).put("packageName", packageName).put("target", "virtual")
            } else {
                val pm = context.packageManager
                val intent = pm.getLaunchIntentForPackage(packageName)
                if (intent == null) {
                    JSONObject().put("ok", false).put("error", "App is not launchable").put("packageName", packageName)
                } else {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(intent)
                        JSONObject().put("ok", true).put("packageName", packageName).put("target", "main")
                    } catch (e: Exception) {
                        JSONObject().put("ok", false).put("error", e.message ?: "Launch failed").put("packageName", packageName)
                    }
                }
            }
        }
    }

    class KeepScreenOnTool : McpTool {
        override val name: String = "keep_screen_on"
        override val description: String = "Keeps the screen on or allows it to turn off"
        override val inputSchema: JSONObject = JSONObject()
            .put("type", "object")
            .put("required", JSONArray().put("enabled"))
            .put("properties", JSONObject().put("enabled", JSONObject().put("type", "boolean")))

        override fun call(context: Context, arguments: JSONObject): JSONObject {
            val enabled = arguments.optBoolean("enabled")
            val prefs = context.getSharedPreferences(ServiceActions.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(ServiceActions.PREF_KEEP_SCREEN_ON, enabled).apply()

            val intent = Intent(context, com.damn.mcpbox.service.McpServerService::class.java)
                .setAction(ServiceActions.ACTION_SET_KEEP_SCREEN_ON)
                .putExtra(ServiceActions.EXTRA_ENABLED, enabled)
            context.startService(intent)

            return JSONObject().put("ok", true).put("enabled", enabled)
        }
    }
}
