package com.damn.mcpbox.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
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

        val snapshotNodes = bfsSnapshotNodes(root, maxNodes.coerceIn(1, MAX_SCAN_NODES))
        val nodesJson = JSONArray()
        for ((index, snapshotNode) in snapshotNodes.withIndex()) {
            nodesJson.put(nodeToJson(snapshotNode.node, index, snapshotNode.depth, snapshotNode.parentIndex))
        }

        return JSONObject()
            .put("ok", true)
            .put("capturedAtMs", SystemClock.elapsedRealtime())
            .put("nodeCount", snapshotNodes.size)
            .put("nodes", nodesJson)
    }

    private fun findNodes(targetText: String?, viewId: String?, limit: Int): JSONArray {
        val root = rootInActiveWindow ?: return JSONArray()
        val results = JSONArray()
        val boundedLimit = limit.coerceIn(1, 100)

        for ((index, snapshotNode) in bfsSnapshotNodes(root, MAX_SCAN_NODES).withIndex()) {
            val node = snapshotNode.node
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val matchesText = !targetText.isNullOrBlank() &&
                (text.contains(targetText, ignoreCase = true) || desc.contains(targetText, ignoreCase = true))
            val matchesViewId = !viewId.isNullOrBlank() && node.viewIdResourceName == viewId
            val actions = scrollActions(node)
            val includeScrollableWhenNoFilter = targetText.isNullOrBlank() && viewId.isNullOrBlank() &&
                (node.isScrollable || actions.length() > 0)

            if (!matchesText && !matchesViewId && !includeScrollableWhenNoFilter) continue

            results.put(nodeToJson(node, index, snapshotNode.depth, snapshotNode.parentIndex))
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

    private fun bfsSnapshotNodes(root: AccessibilityNodeInfo, maxNodes: Int): List<SnapshotNode> {
        val results = mutableListOf<SnapshotNode>()
        val queue: ArrayDeque<SnapshotNode> = ArrayDeque()
        queue.add(SnapshotNode(root, 0, -1))

        while (queue.isNotEmpty() && results.size < maxNodes) {
            val current = queue.removeFirst()
            val currentIndex = results.size
            results.add(current)
            for (i in 0 until current.node.childCount) {
                current.node.getChild(i)?.let { child ->
                    queue.add(SnapshotNode(child, current.depth + 1, currentIndex))
                }
            }
        }

        return results
    }

    private fun nodeToJson(node: AccessibilityNodeInfo, index: Int, depth: Int, parentIndex: Int): JSONObject {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val scrollActions = scrollActions(node)
        val supportsHorizontal =
            hasAction(node, AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id) ||
                hasAction(node, AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id)
        val supportsVertical =
            hasAction(node, AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id) ||
                hasAction(node, AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id) ||
                hasAction(node, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ||
                hasAction(node, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)

        return JSONObject()
            .put("index", index)
            .put("depth", depth)
            .put("parentIndex", parentIndex)
            .put("text", node.text?.toString().orEmpty())
            .put("contentDesc", node.contentDescription?.toString().orEmpty())
            .put("viewId", node.viewIdResourceName.orEmpty())
            .put("className", node.className?.toString().orEmpty())
            .put("packageName", node.packageName?.toString().orEmpty())
            .put("clickable", node.isClickable)
            .put("editable", node.isEditable)
            .put("scrollable", node.isScrollable)
            .put("enabled", node.isEnabled)
            .put("scroll", JSONObject()
                .put("actions", scrollActions)
                .put("supportsHorizontal", supportsHorizontal)
                .put("supportsVertical", supportsVertical))
            .put("bounds", JSONObject()
                .put("left", rect.left)
                .put("top", rect.top)
                .put("right", rect.right)
                .put("bottom", rect.bottom))
    }

    private fun scrollActions(node: AccessibilityNodeInfo): JSONArray {
        val actions = JSONArray()
        if (hasAction(node, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) actions.put("forward")
        if (hasAction(node, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) actions.put("backward")
        if (hasAction(node, AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id)) actions.put("up")
        if (hasAction(node, AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id)) actions.put("down")
        if (hasAction(node, AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id)) actions.put("left")
        if (hasAction(node, AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id)) actions.put("right")
        return actions
    }

    private fun hasAction(node: AccessibilityNodeInfo, actionId: Int): Boolean {
        return node.actionList.any { it.id == actionId }
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

    private fun dispatchMediaKey(keyCode: Int): Boolean {
        val audioManager = getSystemService(AudioManager::class.java) ?: return false
        return try {
            val down = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            val up = KeyEvent(KeyEvent.ACTION_UP, keyCode)
            audioManager.dispatchMediaKeyEvent(down)
            audioManager.dispatchMediaKeyEvent(up)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun takeScreenshot(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null

        val latch = CountDownLatch(1)
        var base64: String? = null

        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                try {
                    val bitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                    if (bitmap != null) {
                        val outputStream = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                        base64 = android.util.Base64.encodeToString(outputStream.toByteArray(), android.util.Base64.NO_WRAP)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process screenshot", e)
                } finally {
                    latch.countDown()
                }
            }

            override fun onFailure(errorCode: Int) {
                Log.e(TAG, "Failed to take screenshot: $errorCode")
                latch.countDown()
            }
        })

        latch.await(5, TimeUnit.SECONDS)
        return base64
    }

    companion object {
        private const val TAG = "McpAccessibilitySvc"
        private const val MAX_SCAN_NODES = 600

        @Volatile
        private var instance: McpAccessibilityService? = null

        private data class SnapshotNode(
            val node: AccessibilityNodeInfo,
            val depth: Int,
            val parentIndex: Int,
        )

        fun isConnected(): Boolean = instance != null

        fun performGlobalActionByName(name: String): Boolean {
            val service = instance ?: return false
            val action = when (name.lowercase()) {
                "back" -> GLOBAL_ACTION_BACK
                "home" -> GLOBAL_ACTION_HOME
                "recents" -> GLOBAL_ACTION_RECENTS
                "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
                "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
                "power_dialog" -> GLOBAL_ACTION_POWER_DIALOG
                else -> return false
            }
            return service.performGlobalAction(action)
        }

        fun performHardwareButton(name: String): Boolean {
            val service = instance ?: return false
            return when (name.lowercase()) {
                "back", "home", "recents", "notifications", "quick_settings", "power_dialog" -> {
                    performGlobalActionByName(name)
                }

                // There is no universal MENU global action; use best-effort UI target.
                "menu" -> service.clickByText("Menu") || service.clickByText("More options")

                "media_play_pause", "play_pause", "toggle_playback" -> service.dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                "media_play", "play" -> service.dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
                "media_pause", "pause" -> service.dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
                "media_next", "next" -> service.dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                "media_previous", "previous", "prev" -> service.dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                "media_stop", "stop" -> service.dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_STOP)
                else -> false
            }
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

        fun takeScreenshot(): String? {
            return instance?.takeScreenshot()
        }
    }
}

