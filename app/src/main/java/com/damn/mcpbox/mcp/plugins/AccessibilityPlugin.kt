package com.damn.mcpbox.mcp.plugins

import android.content.Context
import com.damn.mcpbox.accessibility.McpAccessibilityService
import com.damn.mcpbox.mcp.McpPlugin
import com.damn.mcpbox.mcp.McpTool
import org.json.JSONArray
import org.json.JSONObject

class AccessibilityPlugin : McpPlugin {
    override val id: String = "accessibility"
    override val name: String = "Accessibility"
    override val description: String = "Control the device using accessibility services (clicks, scrolls, typing, etc.)"

    override fun getTools(): List<McpTool> {
        return listOf(
            HealthTool(),
            ListAppsTool(),
            GlobalActionTool(),
            ClickTextTool(),
            ClickViewIdTool(),
            SetTextTool(),
            ScrollTool(),
            UiSnapshotTool(),
            FindNodesTool(),
            TapTool(),
            SwipeTool(),
            HardwareButtonTool()
        )
    }

    class HealthTool : McpTool {
        override val name: String = "health"
        override val description: String = "Returns server and accessibility health status"
        override val inputSchema: JSONObject = JSONObject().put("type", "object").put("properties", JSONObject())

        override fun call(context: Context, arguments: JSONObject): JSONObject {
            return JSONObject()
                .put("ok", true)
                .put("accessibilityConnected", McpAccessibilityService.isConnected())
        }
    }

    class ListAppsTool : McpTool {
        override val name: String = "list_apps"
        override val description: String = "Lists launchable installed apps"
        override val inputSchema: JSONObject = JSONObject().put("type", "object").put("properties", JSONObject())

        override fun call(context: Context, arguments: JSONObject): JSONObject {
            // This would call the app listing logic
            // For now, returning empty to be implemented
            return JSONObject().put("apps", JSONArray())
        }
    }

    class GlobalActionTool : McpTool {
        override val name: String = "global_action"
        override val description: String = "Performs a global accessibility action"
        override val inputSchema: JSONObject = JSONObject()
            .put("type", "object")
            .put("required", JSONArray().put("name"))
            .put("properties", JSONObject().put("name", JSONObject().put("type", "string")))

        override fun call(context: Context, arguments: JSONObject): JSONObject {
            val action = arguments.optString("name")
            val ok = McpAccessibilityService.performGlobalActionByName(action)
            return JSONObject().put("ok", ok).put("action", action)
        }
    }

    class ClickTextTool : McpTool {
        override val name: String = "click_text"
        override val description: String = "Clicks UI node containing text"
        override val inputSchema: JSONObject = JSONObject()
            .put("type", "object")
            .put("required", JSONArray().put("text"))
            .put("properties", JSONObject().put("text", JSONObject().put("type", "string")))

        override fun call(context: Context, arguments: JSONObject): JSONObject {
            val text = arguments.optString("text")
            val ok = McpAccessibilityService.clickNodeByText(text)
            return JSONObject().put("ok", ok).put("text", text)
        }
    }

    class ClickViewIdTool : McpTool {
        override val name: String = "click_view_id"
        override val description: String = "Clicks UI node by viewIdResourceName"
        override val inputSchema: JSONObject = JSONObject()
            .put("type", "object")
            .put("required", JSONArray().put("viewId"))
            .put("properties", JSONObject().put("viewId", JSONObject().put("type", "string")))

        override fun call(context: Context, arguments: JSONObject): JSONObject {
            val viewId = arguments.optString("viewId")
            val ok = McpAccessibilityService.clickNodeByViewId(viewId)
            return JSONObject().put("ok", ok).put("viewId", viewId)
        }
    }

    class SetTextTool : McpTool {
        override val name: String = "set_text"
        override val description: String = "Sets text in editable control by text hint or viewId"
        override val inputSchema: JSONObject = JSONObject()
            .put("type", "object")
            .put("required", JSONArray().put("value"))
            .put("properties", JSONObject()
                .put("value", JSONObject().put("type", "string"))
                .put("text", JSONObject().put("type", "string"))
                .put("viewId", JSONObject().put("type", "string")))

        override fun call(context: Context, arguments: JSONObject): JSONObject {
            val value = arguments.optString("value")
            val text = arguments.optString("text").ifBlank { null }
            val viewId = arguments.optString("viewId").ifBlank { null }
            val ok = if (value.isBlank()) false else McpAccessibilityService.setText(text, viewId, value)
            return JSONObject().put("ok", ok).put("text", text).put("viewId", viewId ?: "")
        }
    }

    class ScrollTool : McpTool {
        override val name: String = "scroll"
        override val description: String = "Scrolls a container (forward/backward) optionally by target text/viewId"
        override val inputSchema: JSONObject = JSONObject()
            .put("type", "object")
            .put("required", JSONArray().put("direction"))
            .put("properties", JSONObject()
                .put("direction", JSONObject().put("type", "string"))
                .put("text", JSONObject().put("type", "string"))
                .put("viewId", JSONObject().put("type", "string")))

        override fun call(context: Context, arguments: JSONObject): JSONObject {
            val direction = arguments.optString("direction")
            val text = arguments.optString("text").ifBlank { null }
            val viewId = arguments.optString("viewId").ifBlank { null }
            val ok = McpAccessibilityService.scroll(text, viewId, direction)
            return JSONObject().put("ok", ok).put("direction", direction)
        }
    }

    class UiSnapshotTool : McpTool {
        override val name: String = "ui_snapshot"
        override val description: String = "Returns accessibility tree snapshot for agent exploration"
        override val inputSchema: JSONObject = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().put("maxNodes", JSONObject().put("type", "integer")))

        override fun call(context: Context, arguments: JSONObject): JSONObject {
            val maxNodes = arguments.optInt("maxNodes", 120)
            return McpAccessibilityService.uiSnapshot(maxNodes)
        }
    }

    class FindNodesTool : McpTool {
        override val name: String = "find_nodes"
        override val description: String = "Find nodes by text or viewId"
        override val inputSchema: JSONObject = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject()
                .put("text", JSONObject().put("type", "string"))
                .put("viewId", JSONObject().put("type", "string"))
                .put("limit", JSONObject().put("type", "integer")))

        override fun call(context: Context, arguments: JSONObject): JSONObject {
            val text = arguments.optString("text").ifBlank { null }
            val viewId = arguments.optString("viewId").ifBlank { null }
            val limit = arguments.optInt("limit", 20)
            return JSONObject().put("ok", true).put("nodes", McpAccessibilityService.findNodes(text, viewId, limit))
        }
    }

    class TapTool : McpTool {
        override val name: String = "tap"
        override val description: String = "Performs tap gesture at screen coordinates"
        override val inputSchema: JSONObject = JSONObject()
            .put("type", "object")
            .put("required", JSONArray().put("x").put("y"))
            .put("properties", JSONObject()
                .put("x", JSONObject().put("type", "number"))
                .put("y", JSONObject().put("type", "number"))
                .put("durationMs", JSONObject().put("type", "integer")))

        override fun call(context: Context, arguments: JSONObject): JSONObject {
            val x = arguments.optDouble("x", Double.NaN)
            val y = arguments.optDouble("y", Double.NaN)
            val durationMs = arguments.optLong("durationMs", 120L)
            val ok = if (x.isNaN() || y.isNaN()) false else McpAccessibilityService.tap(x.toFloat(), y.toFloat(), durationMs)
            return JSONObject().put("ok", ok).put("x", x).put("y", y)
        }
    }

    class SwipeTool : McpTool {
        override val name: String = "swipe"
        override val description: String = "Performs swipe gesture between coordinates"
        override val inputSchema: JSONObject = JSONObject()
            .put("type", "object")
            .put("required", JSONArray().put("x1").put("y1").put("x2").put("y2"))
            .put("properties", JSONObject()
                .put("x1", JSONObject().put("type", "number"))
                .put("y1", JSONObject().put("type", "number"))
                .put("x2", JSONObject().put("type", "number"))
                .put("y2", JSONObject().put("type", "number"))
                .put("durationMs", JSONObject().put("type", "integer")))

        override fun call(context: Context, arguments: JSONObject): JSONObject {
            val x1 = arguments.optDouble("x1", Double.NaN)
            val y1 = arguments.optDouble("y1", Double.NaN)
            val x2 = arguments.optDouble("x2", Double.NaN)
            val y2 = arguments.optDouble("y2", Double.NaN)
            val durationMs = arguments.optLong("durationMs", 350L)
            val ok = if (x1.isNaN() || y1.isNaN() || x2.isNaN() || y2.isNaN()) false 
                     else McpAccessibilityService.swipe(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), durationMs)
            return JSONObject().put("ok", ok)
        }
    }

    class HardwareButtonTool : McpTool {
        override val name: String = "hardware_button"
        override val description: String = "Performs hardware-style commands: back/menu/media buttons"
        override val inputSchema: JSONObject = JSONObject()
            .put("type", "object")
            .put("required", JSONArray().put("name"))
            .put("properties", JSONObject().put("name", JSONObject().put("type", "string")))

        override fun call(context: Context, arguments: JSONObject): JSONObject {
            val name = arguments.optString("name")
            val ok = McpAccessibilityService.performHardwareButton(name)
            return JSONObject().put("ok", ok).put("name", name)
        }
    }
}
