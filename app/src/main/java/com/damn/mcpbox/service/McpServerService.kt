package com.damn.mcpbox.service

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import com.damn.mcpbox.accessibility.McpAccessibilityService
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class McpServerService : Service() {
    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    private val serverRunning = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        AppLogger.i(TAG, "Service created; endpoint=${serverUrl()}")
        ForegroundNotificationFactory.ensureChannel(this)
        startForeground(
            NOTIFICATION_ID,
            ForegroundNotificationFactory.build(this, "MCP API available at ${serverUrl()}")
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLogger.d(TAG, "onStartCommand action=${intent?.action} startId=$startId")
        when (intent?.action) {
            ServiceActions.ACTION_SERVER_STOP -> stopSelf()
            else -> startServerIfNeeded()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        AppLogger.i(TAG, "Service destroyed")
        stopServer()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startServerIfNeeded() {
        if (serverRunning.get()) {
            AppLogger.d(TAG, "startServerIfNeeded ignored; already running")
            return
        }
        serverRunning.set(true)
        serverThread = thread(name = "mcp-http-server") {
            try {
                // Bind to all interfaces so the server is reachable from other devices on LAN.
                ServerSocket(PORT).use { socket ->
                    AppLogger.i(TAG, "Server listening on 0.0.0.0:$PORT")
                    serverSocket = socket
                    socket.soTimeout = ACCEPT_TIMEOUT_MS
                    while (serverRunning.get()) {
                        try {
                            val client = socket.accept()
                            handleClient(client)
                        } catch (_: SocketTimeoutException) {
                            // Keep looping to observe stop flag.
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Server thread failed", e)
                serverRunning.set(false)
            }
        }
    }

    private fun stopServer() {
        AppLogger.i(TAG, "Stopping server")
        serverRunning.set(false)
        serverSocket?.close()
        serverSocket = null
        serverThread?.interrupt()
        serverThread = null
    }

    private fun handleClient(client: Socket) {
        client.use { socket ->
            val input = BufferedReader(InputStreamReader(socket.getInputStream()))
            val output = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))

            val requestLine = input.readLine() ?: return
            val requestParts = requestLine.split(" ")
            if (requestParts.size < 2) return

            val method = requestParts[0]
            val path = requestParts[1]
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = input.readLine() ?: break
                if (line.isBlank()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }
            }

            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (contentLength > 0) {
                val buffer = CharArray(contentLength)
                input.read(buffer, 0, contentLength)
                String(buffer)
            } else {
                ""
            }

            AppLogger.d(TAG, "HTTP request method=$method path=$path contentLength=$contentLength")

            val response = route(method, path, body)
            output.write(response)
            output.flush()
        }
    }

    private fun route(method: String, path: String, body: String): String {
        return try {
            when {
                method == "POST" && (path == "/" || path == "/mcp") -> routeJsonRpc(body)

                method == "GET" && path == "/health" -> httpJson(
                    200,
                    JSONObject()
                        .put("ok", true)
                        .put("serverUrl", serverUrl())
                        .put("accessibilityConnected", McpAccessibilityService.isConnected())
                )

                method == "GET" && path == "/apps" -> httpJson(200, listApps())

                method == "POST" && path == "/overlay/show" -> {
                    startService(
                        Intent(this, FloatingVirtualDisplayService::class.java)
                            .setAction(ServiceActions.ACTION_SHOW_OVERLAY)
                    )
                    httpJson(200, JSONObject().put("ok", true))
                }

                method == "POST" && path == "/overlay/hide" -> {
                    startService(
                        Intent(this, FloatingVirtualDisplayService::class.java)
                            .setAction(ServiceActions.ACTION_HIDE_OVERLAY)
                    )
                    httpJson(200, JSONObject().put("ok", true))
                }

                method == "POST" && path == "/launch" -> {
                    val packageName = JSONObject(body).optString("packageName")
                    val target = JSONObject(body).optString("target").ifBlank { "main" }
                    if (packageName.isBlank()) {
                        httpJson(400, JSONObject().put("ok", false).put("error", "packageName is required"))
                    } else {
                        httpJson(200, launchPackage(packageName, target))
                    }
                }

                method == "POST" && path == "/action" -> routeAction(body)

                else -> httpText(404, "Not found")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Route handling failed method=$method path=$path", e)
            httpJson(400, JSONObject().put("ok", false).put("error", e.message ?: "Unhandled error"))
        }
    }

    private fun routeJsonRpc(body: String): String {
        val request = try {
            JSONObject(body)
        } catch (_: Exception) {
            return httpJson(400, JSONObject().put("ok", false).put("error", "Invalid JSON-RPC body"))
        }

        val jsonRpc = request.optString("jsonrpc")
        if (jsonRpc != "2.0") {
            return jsonRpcError(request.opt("id"), -32600, "Invalid Request")
        }

        val method = request.optString("method")
        val id = request.opt("id")
        AppLogger.d(TAG, "JSON-RPC method=$method id=$id")
        return when (method) {
            "initialize" -> {
                val result = JSONObject()
                    .put("protocolVersion", MCP_PROTOCOL_VERSION)
                    .put("capabilities", JSONObject().put("tools", JSONObject()))
                    .put("serverInfo", JSONObject().put("name", MCP_SERVER_NAME).put("version", MCP_SERVER_VERSION))
                jsonRpcSuccess(id, result)
            }

            "notifications/initialized" -> {
                if (id == null) {
                    httpText(200, "")
                } else {
                    jsonRpcSuccess(id, JSONObject())
                }
            }

            "tools/list" -> {
                val result = JSONObject().put("tools", listMcpTools())
                jsonRpcSuccess(id, result)
            }

            "tools/call" -> {
                val params = request.optJSONObject("params") ?: JSONObject()
                val toolName = params.optString("name")
                val args = params.optJSONObject("arguments") ?: JSONObject()
                val toolResult = handleToolCall(toolName, args)
                val result = JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", toolResult.toString())))
                jsonRpcSuccess(id, result)
            }

            else -> jsonRpcError(id, -32601, "Method not found")
        }
    }

    private fun listMcpTools(): JSONArray {
        return JSONArray()
            .put(
                JSONObject()
                    .put("name", "health")
                    .put("description", "Returns server and accessibility health status")
                    .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()))
            )
            .put(
                JSONObject()
                    .put("name", "list_apps")
                    .put("description", "Lists launchable installed apps")
                    .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()))
            )
            .put(
                JSONObject()
                    .put("name", "overlay_show")
                    .put("description", "Shows floating overlay")
                    .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()))
            )
            .put(
                JSONObject()
                    .put("name", "overlay_hide")
                    .put("description", "Hides floating overlay")
                    .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()))
            )
            .put(
                JSONObject()
                    .put("name", "launch")
                    .put("description", "Launches an app package")
                    .put(
                        "inputSchema",
                        JSONObject()
                            .put("type", "object")
                            .put("required", JSONArray().put("packageName"))
                            .put("properties", JSONObject().put("packageName", JSONObject().put("type", "string")))
                    )
            )
            .put(
                JSONObject()
                    .put("name", "global_action")
                    .put("description", "Performs a global accessibility action")
                    .put(
                        "inputSchema",
                        JSONObject()
                            .put("type", "object")
                            .put("required", JSONArray().put("name"))
                            .put("properties", JSONObject().put("name", JSONObject().put("type", "string")))
                    )
            )
            .put(
                JSONObject()
                    .put("name", "click_text")
                    .put("description", "Clicks UI node containing text")
                    .put(
                        "inputSchema",
                        JSONObject()
                            .put("type", "object")
                            .put("required", JSONArray().put("text"))
                            .put("properties", JSONObject().put("text", JSONObject().put("type", "string")))
                    )
            )
            .put(
                JSONObject()
                    .put("name", "click_view_id")
                    .put("description", "Clicks UI node by viewIdResourceName")
                    .put(
                        "inputSchema",
                        JSONObject()
                            .put("type", "object")
                            .put("required", JSONArray().put("viewId"))
                            .put("properties", JSONObject().put("viewId", JSONObject().put("type", "string")))
                    )
            )
            .put(
                JSONObject()
                    .put("name", "set_text")
                    .put("description", "Sets text in editable control by text hint or viewId")
                    .put(
                        "inputSchema",
                        JSONObject()
                            .put("type", "object")
                            .put("required", JSONArray().put("value"))
                            .put(
                                "properties",
                                JSONObject()
                                    .put("value", JSONObject().put("type", "string"))
                                    .put("text", JSONObject().put("type", "string"))
                                    .put("viewId", JSONObject().put("type", "string"))
                            )
                    )
            )
            .put(
                JSONObject()
                    .put("name", "scroll")
                    .put("description", "Scrolls a container (forward/backward) optionally by target text/viewId")
                    .put(
                        "inputSchema",
                        JSONObject()
                            .put("type", "object")
                            .put("required", JSONArray().put("direction"))
                            .put(
                                "properties",
                                JSONObject()
                                    .put("direction", JSONObject().put("type", "string"))
                                    .put("text", JSONObject().put("type", "string"))
                                    .put("viewId", JSONObject().put("type", "string"))
                            )
                    )
            )
            .put(
                JSONObject()
                    .put("name", "ui_snapshot")
                    .put("description", "Returns accessibility tree snapshot for agent exploration")
                    .put(
                        "inputSchema",
                        JSONObject()
                            .put("type", "object")
                            .put("properties", JSONObject().put("maxNodes", JSONObject().put("type", "integer")))
                    )
            )
            .put(
                JSONObject()
                    .put("name", "find_nodes")
                    .put("description", "Find nodes by text or viewId")
                    .put(
                        "inputSchema",
                        JSONObject()
                            .put("type", "object")
                            .put(
                                "properties",
                                JSONObject()
                                    .put("text", JSONObject().put("type", "string"))
                                    .put("viewId", JSONObject().put("type", "string"))
                                    .put("limit", JSONObject().put("type", "integer"))
                            )
                    )
            )
            .put(
                JSONObject()
                    .put("name", "tap")
                    .put("description", "Performs tap gesture at screen coordinates")
                    .put(
                        "inputSchema",
                        JSONObject()
                            .put("type", "object")
                            .put("required", JSONArray().put("x").put("y"))
                            .put(
                                "properties",
                                JSONObject()
                                    .put("x", JSONObject().put("type", "number"))
                                    .put("y", JSONObject().put("type", "number"))
                                    .put("durationMs", JSONObject().put("type", "integer"))
                            )
                    )
            )
            .put(
                JSONObject()
                    .put("name", "swipe")
                    .put("description", "Performs swipe gesture between coordinates")
                    .put(
                        "inputSchema",
                        JSONObject()
                            .put("type", "object")
                            .put("required", JSONArray().put("x1").put("y1").put("x2").put("y2"))
                            .put(
                                "properties",
                                JSONObject()
                                    .put("x1", JSONObject().put("type", "number"))
                                    .put("y1", JSONObject().put("type", "number"))
                                    .put("x2", JSONObject().put("type", "number"))
                                    .put("y2", JSONObject().put("type", "number"))
                                    .put("durationMs", JSONObject().put("type", "integer"))
                            )
                    )
            )
            .put(
                JSONObject()
                    .put("name", "hardware_button")
                    .put("description", "Performs hardware-style commands: back/menu/media buttons")
                    .put(
                        "inputSchema",
                        JSONObject()
                            .put("type", "object")
                            .put("required", JSONArray().put("name"))
                            .put("properties", JSONObject().put("name", JSONObject().put("type", "string")))
                    )
            )
    }

    private fun handleToolCall(toolName: String, args: JSONObject): JSONObject {
        return when (toolName) {
            "health" -> JSONObject()
                .put("ok", true)
                .put("serverUrl", serverUrl())
                .put("accessibilityConnected", McpAccessibilityService.isConnected())

            "list_apps" -> listApps()

            "overlay_show" -> {
                startService(Intent(this, FloatingVirtualDisplayService::class.java).setAction(ServiceActions.ACTION_SHOW_OVERLAY))
                JSONObject().put("ok", true)
            }

            "overlay_hide" -> {
                startService(Intent(this, FloatingVirtualDisplayService::class.java).setAction(ServiceActions.ACTION_HIDE_OVERLAY))
                JSONObject().put("ok", true)
            }

            "launch" -> {
                val packageName = args.optString("packageName")
                if (packageName.isBlank()) {
                    JSONObject().put("ok", false).put("error", "packageName is required")
                } else {
                    val target = args.optString("target").ifBlank { "main" }
                    launchPackage(packageName, target)
                }
            }

            "global_action" -> {
                val action = args.optString("name")
                JSONObject().put("ok", McpAccessibilityService.performGlobalActionByName(action)).put("action", action)
            }

            "click_text" -> {
                val text = args.optString("text")
                JSONObject().put("ok", McpAccessibilityService.clickNodeByText(text)).put("text", text)
            }

            "click_view_id" -> {
                val viewId = args.optString("viewId")
                JSONObject().put("ok", McpAccessibilityService.clickNodeByViewId(viewId)).put("viewId", viewId)
            }

            "set_text" -> {
                val value = args.optString("value")
                val text = args.optString("text").ifBlank { null }
                val viewId = args.optString("viewId").ifBlank { null }
                val ok = if (value.isBlank()) {
                    false
                } else {
                    McpAccessibilityService.setText(text, viewId, value)
                }
                JSONObject().put("ok", ok).put("text", text).put("viewId", viewId ?: "")
            }

            "scroll" -> {
                val direction = args.optString("direction")
                val text = args.optString("text").ifBlank { null }
                val viewId = args.optString("viewId").ifBlank { null }
                JSONObject()
                    .put("ok", McpAccessibilityService.scroll(text, viewId, direction))
                    .put("direction", direction)
            }

            "ui_snapshot" -> {
                val maxNodes = args.optInt("maxNodes", 120)
                McpAccessibilityService.uiSnapshot(maxNodes)
            }

            "find_nodes" -> {
                val text = args.optString("text").ifBlank { null }
                val viewId = args.optString("viewId").ifBlank { null }
                val limit = args.optInt("limit", 20)
                JSONObject().put("ok", true).put("nodes", McpAccessibilityService.findNodes(text, viewId, limit))
            }

            "tap" -> {
                val x = args.optDouble("x", Double.NaN)
                val y = args.optDouble("y", Double.NaN)
                val durationMs = args.optLong("durationMs", 120L)
                val ok = if (x.isNaN() || y.isNaN()) false else McpAccessibilityService.tap(x.toFloat(), y.toFloat(), durationMs)
                JSONObject().put("ok", ok).put("x", x).put("y", y)
            }

            "swipe" -> {
                val x1 = args.optDouble("x1", Double.NaN)
                val y1 = args.optDouble("y1", Double.NaN)
                val x2 = args.optDouble("x2", Double.NaN)
                val y2 = args.optDouble("y2", Double.NaN)
                val durationMs = args.optLong("durationMs", 350L)
                val ok = if (x1.isNaN() || y1.isNaN() || x2.isNaN() || y2.isNaN()) {
                    false
                } else {
                    McpAccessibilityService.swipe(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), durationMs)
                }
                JSONObject().put("ok", ok)
            }

            "hardware_button" -> {
                val name = args.optString("name")
                JSONObject().put("ok", McpAccessibilityService.performHardwareButton(name)).put("name", name)
            }

            else -> JSONObject().put("ok", false).put("error", "Unknown tool: $toolName")
        }
    }

    private fun jsonRpcSuccess(id: Any?, result: JSONObject): String {
        return httpJson(
            200,
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("result", result)
        )
    }

    private fun jsonRpcError(id: Any?, code: Int, message: String): String {
        return httpJson(
            200,
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("error", JSONObject().put("code", code).put("message", message))
        )
    }

    private fun routeAction(body: String): String {
        val payload = JSONObject(body)
        AppLogger.d(TAG, "REST action type=${payload.optString("type")}")
        return when (payload.optString("type")) {
            "global" -> {
                val actionName = payload.optString("name")
                val ok = McpAccessibilityService.performGlobalActionByName(actionName)
                httpJson(200, JSONObject().put("ok", ok).put("action", actionName))
            }

            "clickText" -> {
                val text = payload.optString("text")
                val ok = McpAccessibilityService.clickNodeByText(text)
                httpJson(200, JSONObject().put("ok", ok).put("text", text))
            }

            "clickViewId" -> {
                val viewId = payload.optString("viewId")
                val ok = McpAccessibilityService.clickNodeByViewId(viewId)
                httpJson(200, JSONObject().put("ok", ok).put("viewId", viewId))
            }

            "setText" -> {
                val value = payload.optString("value")
                val text = payload.optString("text").ifBlank { null }
                val viewId = payload.optString("viewId").ifBlank { null }
                val ok = if (value.isBlank()) false else McpAccessibilityService.setText(text, viewId, value)
                httpJson(200, JSONObject().put("ok", ok))
            }

            "scroll" -> {
                val direction = payload.optString("direction")
                val text = payload.optString("text").ifBlank { null }
                val viewId = payload.optString("viewId").ifBlank { null }
                val ok = McpAccessibilityService.scroll(text, viewId, direction)
                httpJson(200, JSONObject().put("ok", ok).put("direction", direction))
            }

            "uiSnapshot" -> {
                val maxNodes = payload.optInt("maxNodes", 120)
                httpJson(200, McpAccessibilityService.uiSnapshot(maxNodes))
            }

            "findNodes" -> {
                val text = payload.optString("text").ifBlank { null }
                val viewId = payload.optString("viewId").ifBlank { null }
                val limit = payload.optInt("limit", 20)
                httpJson(200, JSONObject().put("ok", true).put("nodes", McpAccessibilityService.findNodes(text, viewId, limit)))
            }

            "tap" -> {
                val x = payload.optDouble("x", Double.NaN)
                val y = payload.optDouble("y", Double.NaN)
                val durationMs = payload.optLong("durationMs", 120L)
                val ok = if (x.isNaN() || y.isNaN()) false else McpAccessibilityService.tap(x.toFloat(), y.toFloat(), durationMs)
                httpJson(200, JSONObject().put("ok", ok))
            }

            "swipe" -> {
                val x1 = payload.optDouble("x1", Double.NaN)
                val y1 = payload.optDouble("y1", Double.NaN)
                val x2 = payload.optDouble("x2", Double.NaN)
                val y2 = payload.optDouble("y2", Double.NaN)
                val durationMs = payload.optLong("durationMs", 350L)
                val ok = if (x1.isNaN() || y1.isNaN() || x2.isNaN() || y2.isNaN()) {
                    false
                } else {
                    McpAccessibilityService.swipe(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), durationMs)
                }
                httpJson(200, JSONObject().put("ok", ok))
            }

            "hardwareButton" -> {
                val name = payload.optString("name")
                val ok = McpAccessibilityService.performHardwareButton(name)
                httpJson(200, JSONObject().put("ok", ok).put("name", name))
            }

            else -> httpJson(400, JSONObject().put("ok", false).put("error", "Unsupported action type"))
        }
    }

    private fun listApps(): JSONObject {
        val launchIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                launchIntent,
                PackageManager.ResolveInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(launchIntent, 0)
        }

        val apps = resolveInfos
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
            .map {
                JSONObject()
                    .put("label", it.loadLabel(packageManager).toString())
                    .put("packageName", it.activityInfo.packageName)
            }

        return JSONObject().put("apps", JSONArray(apps))
    }

    private fun launchPackage(packageName: String, target: String): JSONObject {
        AppLogger.i(TAG, "Launch request package=$packageName target=$target")
        return if (target.equals("virtual", ignoreCase = true)) {
            startService(
                Intent(this, FloatingVirtualDisplayService::class.java)
                    .setAction(ServiceActions.ACTION_LAUNCH_PACKAGE)
                    .putExtra(ServiceActions.EXTRA_PACKAGE_NAME, packageName)
            )
            JSONObject().put("ok", true).put("packageName", packageName).put("target", "virtual")
        } else {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent == null) {
                JSONObject().put("ok", false).put("error", "App is not launchable").put("packageName", packageName)
            } else {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    startActivity(intent)
                    JSONObject().put("ok", true).put("packageName", packageName).put("target", "main")
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Failed to start package on main display package=$packageName", e)
                    JSONObject().put("ok", false).put("error", e.message ?: "Launch failed").put("packageName", packageName)
                }
            }
        }
    }

    private fun httpJson(statusCode: Int, json: JSONObject): String {
        val body = json.toString()
        return buildString {
            append("HTTP/1.1 $statusCode ")
            append(if (statusCode in 200..299) "OK" else "ERROR")
            append("\r\n")
            append("Content-Type: application/json\r\n")
            append("Content-Length: ${body.toByteArray().size}\r\n")
            append("Connection: close\r\n\r\n")
            append(body)
        }
    }

    private fun httpText(statusCode: Int, message: String): String {
        return buildString {
            append("HTTP/1.1 $statusCode ERROR\r\n")
            append("Content-Type: text/plain\r\n")
            append("Content-Length: ${message.toByteArray().size}\r\n")
            append("Connection: close\r\n\r\n")
            append(message)
        }
    }

    companion object {
        private const val TAG = "McpServerService"
        private const val NOTIFICATION_ID = 102
        private const val PORT = 8765
        private const val MCP_SERVER_NAME = "mcp-android-http"
        private const val MCP_SERVER_VERSION = "1.0.0"
        private const val MCP_PROTOCOL_VERSION = "2025-06-18"
        const val LOCAL_BASE_URL = "http://127.0.0.1:$PORT"
        private const val ACCEPT_TIMEOUT_MS = 1_000

        fun serverUrl(): String {
            val lanIp = resolveLanIpv4Address()
            return if (lanIp != null) {
                "http://$lanIp:$PORT"
            } else {
                LOCAL_BASE_URL
            }
        }

        private fun resolveLanIpv4Address(): String? {
            return try {
                NetworkInterface.getNetworkInterfaces()
                    ?.toList()
                    .orEmpty()
                    .asSequence()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { it.inetAddresses.toList().asSequence() }
                    .filterIsInstance<Inet4Address>()
                    .map { it.hostAddress }
                    .firstOrNull { ip ->
                        ip != null &&
                            !ip.startsWith("127.") &&
                            !ip.startsWith("169.254.")
                    }
            } catch (_: Exception) {
                null
            }
        }
    }
}
