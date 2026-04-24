package com.damn.mcpbox.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class McpAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    private fun clickByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        for (node in bfsNodes(root, MAX_SCAN_NODES)) {
            val nodeText = node.text?.toString().orEmpty()
            val nodeDesc = node.contentDescription?.toString().orEmpty()
            if (nodeText.contains(text, ignoreCase = true) || nodeDesc.contains(text, ignoreCase = true)) {
                if (performClick(node)) {
                    return true
                }
            }
        }
        return false
    }

    private fun clickByViewId(viewId: String): Boolean {
        val root = rootInActiveWindow ?: return false
        for (node in bfsNodes(root, MAX_SCAN_NODES)) {
            if (node.viewIdResourceName == viewId && performClick(node)) {
                return true
            }
        }
        return false
    }

    private fun setText(targetText: String?, viewId: String?, value: String): Boolean {
        val root = rootInActiveWindow ?: return false
        for (node in bfsNodes(root, MAX_SCAN_NODES)) {
            if (!node.isEditable) continue

            val matchesViewId = !viewId.isNullOrBlank() && node.viewIdResourceName == viewId
            val nodeText = node.text?.toString().orEmpty()
            val nodeDesc = node.contentDescription?.toString().orEmpty()
            val matchesText = !targetText.isNullOrBlank() &&
                (nodeText.contains(targetText, ignoreCase = true) || nodeDesc.contains(targetText, ignoreCase = true))

            if (!matchesViewId && !matchesText) continue

            val args = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            }
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                return true
            }
        }
        return false
    }

    private fun scroll(targetText: String?, viewId: String?, direction: String): Boolean {
        val action = when (direction.lowercase()) {
            "forward", "down", "right" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            "backward", "up", "left" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else -> return false
        }

        val root = rootInActiveWindow ?: return false
        for (node in bfsNodes(root, MAX_SCAN_NODES)) {
            val matchesViewId = !viewId.isNullOrBlank() && node.viewIdResourceName == viewId
            val nodeText = node.text?.toString().orEmpty()
            val nodeDesc = node.contentDescription?.toString().orEmpty()
            val matchesText = !targetText.isNullOrBlank() &&
                (nodeText.contains(targetText, ignoreCase = true) || nodeDesc.contains(targetText, ignoreCase = true))
            val isScrollableTarget = node.isScrollable || matchesViewId || matchesText

            if (isScrollableTarget && node.performAction(action)) {
                return true
            }
        }
        return false
    }

    private fun uiSnapshot(maxNodes: Int): JSONObject {
        val root = rootInActiveWindow ?: return JSONObject()
            .put("ok", false)
            .put("error", "No active window")

        val nodesJson = JSONArray()
        var count = 0
        for ((index, nodeDepth) in bfsNodesWithDepth(root, maxNodes.coerceIn(1, MAX_SCAN_NODES)).withIndex()) {
            val (node, depth) = nodeDepth
            val rect = Rect()
            node.getBoundsInScreen(rect)
            nodesJson.put(
                JSONObject()
                    .put("index", index)
                    .put("depth", depth)
                    .put("text", node.text?.toString().orEmpty())
                    .put("contentDesc", node.contentDescription?.toString().orEmpty())
                    .put("viewId", node.viewIdResourceName.orEmpty())
                    .put("className", node.className?.toString().orEmpty())
                    .put("packageName", node.packageName?.toString().orEmpty())
                    .put("clickable", node.isClickable)
                    .put("editable", node.isEditable)
                    .put("scrollable", node.isScrollable)
                    .put("enabled", node.isEnabled)
                    .put("bounds", JSONObject()
                        .put("left", rect.left)
                        .put("top", rect.top)
                        .put("right", rect.right)
                        .put("bottom", rect.bottom))
            )
            count++
        }

        return JSONObject()
            .put("ok", true)
            .put("capturedAtMs", SystemClock.elapsedRealtime())
            .put("nodeCount", count)
            .put("nodes", nodesJson)
    }

    private fun findNodes(targetText: String?, viewId: String?, limit: Int): JSONArray {
        val root = rootInActiveWindow ?: return JSONArray()
        val results = JSONArray()
        val boundedLimit = limit.coerceIn(1, 100)

        for (node in bfsNodes(root, MAX_SCAN_NODES)) {
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val matchesText = !targetText.isNullOrBlank() &&
                (text.contains(targetText, ignoreCase = true) || desc.contains(targetText, ignoreCase = true))
            val matchesViewId = !viewId.isNullOrBlank() && node.viewIdResourceName == viewId

            if (!matchesText && !matchesViewId) continue

            val rect = Rect()
            node.getBoundsInScreen(rect)
            results.put(
                JSONObject()
                    .put("text", text)
                    .put("contentDesc", desc)
                    .put("viewId", node.viewIdResourceName.orEmpty())
                    .put("className", node.className?.toString().orEmpty())
                    .put("clickable", node.isClickable)
                    .put("editable", node.isEditable)
                    .put("scrollable", node.isScrollable)
                    .put("bounds", JSONObject()
                        .put("left", rect.left)
                        .put("top", rect.top)
                        .put("right", rect.right)
                        .put("bottom", rect.bottom))
            )
            if (results.length() >= boundedLimit) break
        }

        return results
    }

    private fun tap(x: Float, y: Float, durationMs: Long): Boolean {
        val boundedDuration = durationMs.coerceIn(50L, 2_000L)
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, boundedDuration))
            .build()
        return runGesture(gesture)
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val boundedDuration = durationMs.coerceIn(100L, 5_000L)
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, boundedDuration))
            .build()
        return runGesture(gesture)
    }

    private fun runGesture(gesture: GestureDescription): Boolean {
        val latch = CountDownLatch(1)
        var success = false
        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    success = true
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription) {
                    success = false
                    latch.countDown()
                }
            },
            null
        )

        if (!dispatched) return false
        latch.await(2, TimeUnit.SECONDS)
        return success
    }

    private fun bfsNodes(root: AccessibilityNodeInfo, maxNodes: Int): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        val queue: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
        queue.add(root)

        while (queue.isNotEmpty() && results.size < maxNodes) {
            val node = queue.removeFirst()
            results.add(node)
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(queue::add)
            }
        }
        return results
    }

    private fun bfsNodesWithDepth(root: AccessibilityNodeInfo, maxNodes: Int): List<Pair<AccessibilityNodeInfo, Int>> {
        val results = mutableListOf<Pair<AccessibilityNodeInfo, Int>>()
        val queue: ArrayDeque<Pair<AccessibilityNodeInfo, Int>> = ArrayDeque()
        queue.add(root to 0)

        while (queue.isNotEmpty() && results.size < maxNodes) {
            val (node, depth) = queue.removeFirst()
            results.add(node to depth)
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it to depth + 1) }
            }
        }
        return results
    }

    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
        }
        return false
    }

    companion object {
        private const val TAG = "McpAccessibilitySvc"
        private const val MAX_SCAN_NODES = 600

        @Volatile
        private var instance: McpAccessibilityService? = null

        fun isConnected(): Boolean = instance != null

        fun performGlobalActionByName(name: String): Boolean {
            val service = instance ?: return false
            val action = when (name.lowercase()) {
                "back" -> GLOBAL_ACTION_BACK
                "home" -> GLOBAL_ACTION_HOME
                "recents" -> GLOBAL_ACTION_RECENTS
                "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
                else -> return false
            }
            return service.performGlobalAction(action)
        }

        fun clickNodeByText(text: String): Boolean {
            val service = instance ?: return false
            return service.clickByText(text)
        }

        fun clickNodeByViewId(viewId: String): Boolean {
            val service = instance ?: return false
            return service.clickByViewId(viewId)
        }

        fun setText(targetText: String?, viewId: String?, value: String): Boolean {
            val service = instance ?: return false
            return service.setText(targetText, viewId, value)
        }

        fun scroll(targetText: String?, viewId: String?, direction: String): Boolean {
            val service = instance ?: return false
            return service.scroll(targetText, viewId, direction)
        }

        fun uiSnapshot(maxNodes: Int): JSONObject {
            val service = instance
            return if (service == null) {
                JSONObject().put("ok", false).put("error", "Accessibility service is not connected")
            } else {
                service.uiSnapshot(maxNodes)
            }
        }

        fun findNodes(targetText: String?, viewId: String?, limit: Int): JSONArray {
            val service = instance ?: return JSONArray()
            return service.findNodes(targetText, viewId, limit)
        }

        fun tap(x: Float, y: Float, durationMs: Long): Boolean {
            val service = instance ?: return false
            return service.tap(x, y, durationMs)
        }

        fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
            val service = instance ?: return false
            return service.swipe(x1, y1, x2, y2, durationMs)
        }
    }
}

