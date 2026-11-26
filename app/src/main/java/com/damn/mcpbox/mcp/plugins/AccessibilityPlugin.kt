package com.damn.mcpbox.mcp.plugins

import android.content.Context
import android.view.Display
import com.damn.mcpbox.accessibility.McpAccessibilityService
import com.damn.mcpbox.mcp.McpPlugin
import com.damn.mcpbox.mcp.McpTool
import com.damn.mcpbox.service.FloatingVirtualDisplayService
import com.damn.mcpbox.shizuku.ShizukuHelper
import org.json.JSONArray
import org.json.JSONObject

class AccessibilityPlugin : McpPlugin {
    override val id: String = "accessibility"
    override val name: String = "Accessibility"
    override val description: String = "Control the device using accessibility services (clicks, scrolls, typing, etc.)"

    override fun getTools(): List<McpTool> {
        return listOf(
            HealthTool(),
            GlobalActionTool(),
            ClickTextTool(),
            ClickViewIdTool(),
            SetTextTool(),
            InputTextTool(),
            KeyEventTool(),
            ScrollTool(),
            UiSnapshotTool(),
            FindNodesTool(),
            TapTool(),
            SwipeTool(),
            HardwareButtonTool(),
            ScreenshotTool()
        )
    }

    private companion object {
        fun addDisplayProperties(props: JSONObject): JSONObject {
            return props
                .put("target", JSONObject().put("type", "string").put("description", "Target display: 'main' (default) or 'virtual'"))
                .put("displayId", JSONObject().put("type", "integer").put("description", "Explicit Android displayId (overrides target)"))
        }

        fun resolveDisplayId(arguments: JSONObject): Pair<Int?, String?> {
            if (arguments.has("displayId") && !arguments.isNull("displayId")) {
                return arguments.optInt("displayId", Display.DEFAULT_DISPLAY) to null
            }
            val target = arguments.optString("target").ifBlank { "main" }
            return if (target.equals("virtual", ignoreCase = true)) {
                val activeId = FloatingVirtualDisplayService.activeDisplayId
                if (activeId == null) {
                    null to "Virtual display is not active"
                } else {
                    activeId to null
                }
            } else {
                Display.DEFAULT_DISPLAY to null
            }
        }
    }

    class HealthTool : McpTool {
        override val name: String = "health"
        override val description: String = "Returns server, accessibility, and virtual display health status"
        override val inputSchema: JSONObject = JSONObject().put("type", "object").put("properties", JSONObject())

        override fun call(context: Context, arguments: JSONObject): JSONObject {
            val virtualDisplayId = FloatingVirtualDisplayService.activeDisplayId
            return JSONObject()
                .put("ok", true)
                .put("accessibilityConnected", McpAccessibilityService.isConnected())
                .put("shizukuReady", ShizukuHelper.isReady())
                .put("virtualDisplayActive", virtualDisplayId != null)
                .put("virtualDisplayId", virtualDisplayId ?: JSONObject.NULL)
                .put("virtualDisplayWidth", FloatingVirtualDisplayService.VIRTUAL_DISPLAY_WIDTH)
                .put("virtualDisplayHeight", FloatingVirtualDisplayService.VIRTUAL_DISPLAY_HEIGHT)
                .put("virtualDisplayDensityDpi", FloatingVirtualDisplayService.VIRTUAL_DISPLAY_DENSITY_DPI)
        }
    }

    class GlobalActionTool : McpTool {
        override val name: String = "global_action"
        override val description: String = "Performs a global accessibility action"
        override val inputSchema: JSONObject = JSONObject()
            .put("type", "object")
            .put("required", JSONArray().put("name"))
            .put("properties", addDisplayProperties(
                JSONObject().put("name", JSONObject().put("type", "string"))
            ))

        override fun call(context: Context, arguments: JSONObject): JSONObject {
            val (displayId, error) = resolveDisplayId(arguments)
            if (displayId == null) return JSONObject().put("ok", false).put("error", error)
            val action = arguments.optString("name")
            val ok = McpAccessibilityService.performGlobalActionByName(action, displayId)
            return JSONObject().put("ok", ok).put("action", action).put("displayId", displayId)
        }
    }

    class ClickTextTool : McpTool {
        override val name: String = "click_text"
        override val description: String = "Clicks UI node containing text"
        override val inputSchema: JSONObject = JSONObject()
            .put("type", "object")
            .put("required", JSONArray().put("text"))
            .put("properties", addDisplayProperties(
                JSONObject().put("text", JSONObject().put("type", "string"))
            ))

        override fun call(context: Context, arguments: JSONObject): JSONObject {
            val (displayId, error) = resolveDisplayId(arguments)
            if (displayId == null) return JSONObject().put("ok", false).put("error", error)
            val text = arguments.optString("text")
            val ok = McpAccessibilityService.clickNodeByText(text, displayId)
            return JSONObject().put("ok", ok).put("text", text).put("displayId", displayId)
        }
    }

    class ClickViewIdTool : McpTool {
        override val name: String = "click_view_id"
        override val description: String = "Clicks UI node by viewIdResourceName"
        override val inputSchema: JSONObject = JSONObject()
            .put("type", "object")
            .put("required", JSONArray().put("viewId"))
            .put("properties", addDisplayProperties(
                JSONObject().put("viewId", JSONObject().put("type", "string"))
            ))

        override fun call(context: Context, arguments: JSONObject): JSONObject {
            val (displayId, error) = resolveDisplayId(arguments)
            if (displayId == null) return JSONObject().put("ok", false).put("error", error)
            val viewId = arguments.optString("viewId")
            val ok = McpAccessibilityService.clickNodeByViewId(viewId, displayId)
            return JSONObject().put("ok", ok).put("viewId", viewId).put("displayId", displayId)
        }
    }

    class SetTextTool : McpTool {
        override val name: String = "set_text"
        override val description: String = "Sets text in editable control by text hint or viewId"
        override val inputSchema: JSONObject = JSONObject()
            .put("type", "object")
            .put("required", JSONArray().put("value"))
            .put("properties", addDisplayProperties(
                JSONObject()
                    .put("value", JSONObject().put("type", "string"))
                    .put("text", JSONObject().put("type", "string"))
                    .put("viewId", JSONObject().put("type", "string"))
            ))

        override fun call(context: Context, arguments: JSONObject): JSONObject {
            val (displayId, error) = resolveDisplayId(arguments)
            if (displayId == null) return JSONObject().put("ok", false).put("error", error)
            val value = arguments.optString("value")
            val text = arguments.optString("text").ifBlank { null }
            val viewId = arguments.optString("viewId").ifBlank { null }
            val ok = if (value.isBlank()) false else McpAccessibilityService.setText(text, viewId, value, displayId)
            return JSONObject().put("ok", ok).put("text", text).put("viewId", viewId ?: "").put("displayId", displayId)
        }
    }

    class ScrollTool : McpTool {
        override val name: String = "scroll"
        override val description: String = "Scrolls a container (forward/backward) optionally by target text/viewId"
        override val inputSchema: JSONObject = JSONObject()
            .put("type", "object")
            .put("required", JSONArray().put("direction"))
            .put("properties", addDisplayProperties(
                JSONObject()
                    .put("direction", JSONObject().put("type", "string"))
                    .put("text", JSONObject().put("type", "string"))
                    .put("viewId", JSONObject().put("type", "string"))
            ))

        override fun call(context: Context, arguments: JSONObject): JSONObject {
            val (displayId, error) = resolveDisplayId(arguments)
            if (displayId == null) return JSONObject().put("ok", false).put("error", error)
            val direction = arguments.optString("direction")
            val text = arguments.optString("text").ifBlank { null }
            val viewId = arguments.optString("viewId").ifBlank { null }
            val ok = McpAccessibilityService.scroll(text, viewId, direction, displayId)
            return JSONObject().put("ok", ok).put("direction", direction).put("displayId", displayId)
        }
    }

    class UiSnapshotTool : McpTool {
        override val name: String = "ui_snapshot"
        override val description: String = "Returns accessibility tree snapshot for agent exploration"
        override val inputSchema: JSONObject = JSONObject()
            .put("type", "object")
            .put("properties", addDisplayProperties(
                JSONObject().put("maxNodes", JSONObject().put("type", "integer"))
            ))

        override fun call(context: Context, arguments: JSONObject): JSONObject {
            val (displayId, error) = resolveDisplayId(arguments)
            if (displayId == null) return JSONObject().put("ok", false).put("error", error)
            val maxNodes = arguments.optInt("maxNodes", 120)
            return McpAccessibilityService.uiSnapshot(maxNodes, displayId)
        }
    }

    class FindNodesTool : McpTool {
        override val name: String = "find_nodes"
        override val description: String = "Find nodes by text or viewId"
        override val inputSchema: JSONObject = JSONObject()
            .put("type", "object")
            .put("properties", addDisplayProperties(
                JSONObject()
                    .put("text", JSONObject().put("type", "string"))
                    .put("viewId", JSONObject().put("type", "string"))
                    .put("limit", JSONObject().put("type", "integer"))
            ))

        override fun call(context: Context, arguments: JSONObject): JSONObject {
            val (displayId, error) = resolveDisplayId(arguments)
            if (displayId == null) return JSONObject().put("ok", false).put("error", error)
            val text = arguments.optString("text").ifBlank { null }
            val viewId = arguments.optString("viewId").ifBlank { null }
            val limit = arguments.optInt("limit", 20)
            return JSONObject()
                .put("ok", true)
                .put("displayId", displayId)
                .put("nodes", McpAccessibilityService.findNodes(text, viewId, limit, displayId))
        }
    }

    class TapTool : McpTool {
        override val name: String = "tap"
        override val description: String = "Performs tap gesture at screen coordinates"
        override val inputSchema: JSONObject = JSONObject()
            .put("type", "object")
            .put("required", JSONArray().put("x").put("y"))
            .put("properties", addDisplayProperties(
                JSONObject()
                    .put("x", JSONObject().put("type", "number"))
                    .put("y", JSONObject().put("type", "number"))
                    .put("durationMs", JSONObject().put("type", "integer"))
            ))

        override fun call(context: Context, arguments: JSONObject): JSONObject {
            val (displayId, error) = resolveDisplayId(arguments)
            if (displayId == null) return JSONObject().put("ok", false).put("error", error)
            val x = arguments.optDouble("x", Double.NaN)
            val y = arguments.optDouble("y", Double.NaN)
            val durationMs = arguments.optLong("durationMs", 120L)
            val ok = if (x.isNaN() || y.isNaN()) false else McpAccessibilityService.tap(x.toFloat(), y.toFloat(), durationMs, displayId)
            return JSONObject().put("ok", ok).put("x", x).put("y", y).put("displayId", displayId)
        }
    }

    class SwipeTool : McpTool {
        override val name: String = "swipe"
        override val description: String = "Performs swipe gesture between coordinates"
        override val inputSchema: JSONObject = JSONObject()
            .put("type", "object")
            .put("required", JSONArray().put("x1").put("y1").put("x2").put("y2"))
            .put("properties", addDisplayProperties(
                JSONObject()
                    .put("x1", JSONObject().put("type", "number"))
                    .put("y1", JSONObject().put("type", "number"))
                    .put("x2", JSONObject().put("type", "number"))
                    .put("y2", JSONObject().put("type", "number"))
                    .put("durationMs", JSONObject().put("type", "integer"))
            ))

        override fun call(context: Context, arguments: JSONObject): JSONObject {
            val (displayId, error) = resolveDisplayId(arguments)
            if (displayId == null) return JSONObject().put("ok", false).put("error", error)
            val x1 = arguments.optDouble("x1", Double.NaN)
            val y1 = arguments.optDouble("y1", Double.NaN)
            val x2 = arguments.optDouble("x2", Double.NaN)
            val y2 = arguments.optDouble("y2", Double.NaN)
            val durationMs = arguments.optLong("durationMs", 350L)
            val ok = if (x1.isNaN() || y1.isNaN() || x2.isNaN() || y2.isNaN()) false
                     else McpAccessibilityService.swipe(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), durationMs, displayId)
            return JSONObject().put("ok", ok).put("displayId", displayId)
        }
    }

    class HardwareButtonTool : McpTool {
        override val name: String = "hardware_button"
        override val description: String = "Performs hardware-style commands: back/menu/media buttons"
        override val inputSchema: JSONObject = JSONObject()
            .put("type", "object")
            .put("required", JSONArray().put("name"))
            .put("properties", addDisplayProperties(
                JSONObject().put("name", JSONObject().put("type", "string"))
            ))

        override fun call(context: Context, arguments: JSONObject): JSONObject {
            val (displayId, error) = resolveDisplayId(arguments)
            if (displayId == null) return JSONObject().put("ok", false).put("error", error)
            val name = arguments.optString("name")
            val ok = McpAccessibilityService.performHardwareButton(name, displayId)
            return JSONObject().put("ok", ok).put("name", name).put("displayId", displayId)
        }
    }

    class InputTextTool : McpTool {
        override val name: String = "input_text"
        override val description: String = "Types text into the focused field on main or virtual display (via Shizuku)"
        override val inputSchema: JSONObject = JSONObject()
            .put("type", "object")
            .put("required", JSONArray().put("text"))
            .put("properties", addDisplayProperties(
                JSONObject()
                    .put("text", JSONObject().put("type", "string").put("description", "Text to type"))
                    .put("pressEnter", JSONObject().put("type", "boolean").put("description", "Send ENTER key after typing"))
            ))

        override fun call(context: Context, arguments: JSONObject): JSONObject {
            val (displayId, error) = resolveDisplayId(arguments)
            if (displayId == null) return JSONObject().put("ok", false).put("error", error)
            val text = arguments.optString("text")
            if (text.isEmpty()) return JSONObject().put("ok", false).put("error", "text is required")
            val pressEnter = arguments.optBoolean("pressEnter", false)
            val ok = McpAccessibilityService.inputText(text, displayId, pressEnter)
            return JSONObject().put("ok", ok).put("displayId", displayId).put("pressEnter", pressEnter)
        }
    }

    class KeyEventTool : McpTool {
        override val name: String = "key_event"
        override val description: String = "Sends a key event (e.g. back, home, enter, del, tab, escape, dpad_*, or numeric keycode) to main or virtual display (via Shizuku)"
        override val inputSchema: JSONObject = JSONObject()
            .put("type", "object")
            .put("required", JSONArray().put("key"))
            .put("properties", addDisplayProperties(
                JSONObject()
                    .put("key", JSONObject().put("type", "string").put("description", "Key name (back, home, enter, del, tab, escape, dpad_up/down/left/right) or Android KeyEvent code"))
                    .put("longPress", JSONObject().put("type", "boolean").put("description", "Whether to send as a long press"))
            ))

        override fun call(context: Context, arguments: JSONObject): JSONObject {
            val (displayId, error) = resolveDisplayId(arguments)
            if (displayId == null) return JSONObject().put("ok", false).put("error", error)
            val key = arguments.optString("key")
            if (key.isBlank()) return JSONObject().put("ok", false).put("error", "key is required")
            val longPress = arguments.optBoolean("longPress", false)
            val ok = McpAccessibilityService.sendKeyEvent(key, displayId, longPress)
            return JSONObject().put("ok", ok).put("key", key).put("displayId", displayId)
        }
    }

    class ScreenshotTool : McpTool {
        override val name: String = "screenshot"
        override val description: String = "Captures a PNG screenshot of the main or virtual display"
        override val inputSchema: JSONObject = JSONObject()
            .put("type", "object")
            .put("properties", addDisplayProperties(JSONObject()))

        override fun call(context: Context, arguments: JSONObject): JSONObject {
            val (displayId, error) = resolveDisplayId(arguments)
            if (displayId == null) return JSONObject().put("ok", false).put("error", error)
            val base64 = McpAccessibilityService.takeScreenshot(displayId)
                ?: return JSONObject().put("ok", false).put("displayId", displayId).put("error", "Failed to capture screenshot")
            return JSONObject()
                .put("ok", true)
                .put("displayId", displayId)
                .put("mimeType", "image/png")
                .put("_imageBase64", base64)
        }
    }
}
