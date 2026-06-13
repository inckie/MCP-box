package com.damn.mcpbox.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.damn.mcpbox.accessibility.McpAccessibilityService
import com.damn.mcpbox.mcp.McpPluginManager
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

class McpServerService : Service(), McpPluginManager.PermissionRequester {
    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    private val serverRunning = AtomicBoolean(false)
    private var wakeLock: PowerManager.WakeLock? = null
    private val pluginManager by lazy { 
        McpPluginManager(this).also { it.permissionRequester = this }
    }

    private var isAppInForeground = false

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
            ServiceActions.ACTION_SET_KEEP_SCREEN_ON -> {
                val enabled = intent.getBooleanExtra(ServiceActions.EXTRA_ENABLED, false)
                setKeepScreenOn(enabled)
            }
            ServiceActions.ACTION_PERMISSION_DECISION -> {
                val requestId = intent.getIntExtra(ServiceActions.EXTRA_REQUEST_ID, -1)
                val decision = intent.getBooleanExtra(ServiceActions.EXTRA_DECISION, false)
                pluginManager.onPermissionDecision(requestId, decision)
                
                // Cancel notification if it was used
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(requestId + 1000)
            }
            else -> {
                val prefs = getSharedPreferences(ServiceActions.PREFS_NAME, MODE_PRIVATE)
                val enabled = prefs.getBoolean(ServiceActions.PREF_KEEP_SCREEN_ON, false)
                setKeepScreenOn(enabled)
                startServerIfNeeded()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        AppLogger.i(TAG, "Service destroyed")
        setKeepScreenOn(false)
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

    private fun setKeepScreenOn(enabled: Boolean) {
        if (enabled) {
            if (wakeLock == null) {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                @Suppress("DEPRECATION")
                wakeLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "McpBox::KeepScreenOn"
                )
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire()
                AppLogger.i(TAG, "WakeLock acquired")
            }
        } else {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                AppLogger.i(TAG, "WakeLock released")
            }
            wakeLock = null
        }
    }

    private fun handleClient(client: Socket) {
        client.use { socket ->
            val input = BufferedReader(InputStreamReader(socket.getInputStream()))
            val output = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))

            val requestLine = input.readLine() ?: return
            val requestParts = requestLine.split(" ")
            if (requestParts.size < 2) return

            val method = requestParts[0].uppercase()
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
                var read = 0
                while (read < contentLength) {
                    val current = input.read(buffer, read, contentLength - read)
                    if (current <= 0) break
                    read += current
                }
                String(buffer, 0, read)
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
            when (method) {
                "POST" if (path == "/" || path == "/mcp") -> routeJsonRpc(body)
                "GET" if (path == "/" || path == "/mcp" || path == "/.well-known/mcp") -> {
                    httpJson(200, mcpServerManifest())
                }
                "GET" if path == "/tools" -> {
                    httpJson(200, JSONObject().put("tools", listMcpTools()))
                }
                "GET" if path == "/health" -> httpJson(
                    200,
                    JSONObject()
                        .put("ok", true)
                        .put("serverUrl", serverUrl())
                        .put("accessibilityConnected", McpAccessibilityService.isConnected())
                )
                "GET" if path == "/apps" -> httpJson(200, listApps())
                "POST" if path == "/overlay/show" -> {
                    startService(
                        Intent(this, FloatingVirtualDisplayService::class.java)
                            .setAction(ServiceActions.ACTION_SHOW_OVERLAY)
                    )
                    httpJson(200, JSONObject().put("ok", true))
                }
                "POST" if path == "/overlay/hide" -> {
                    startService(
                        Intent(this, FloatingVirtualDisplayService::class.java)
                            .setAction(ServiceActions.ACTION_HIDE_OVERLAY)
                    )
                    httpJson(200, JSONObject().put("ok", true))
                }
                "POST" if path == "/launch" -> {
                    val packageName = JSONObject(body).optString("packageName")
                    val target = JSONObject(body).optString("target").ifBlank { "main" }
                    if (packageName.isBlank()) {
                        httpJson(400, JSONObject().put("ok", false).put("error", "packageName is required"))
                    } else {
                        httpJson(200, launchPackage(packageName, target))
                    }
                }
                "POST" if path == "/action" -> routeAction(body)
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

        if (method.isBlank()) {
            return jsonRpcError(id, -32600, "Invalid Request")
        }

        return when (method) {
            "initialize" -> {
                val result = JSONObject()
                    .put("protocolVersion", MCP_PROTOCOL_VERSION)
                    .put("capabilities", JSONObject()
                        .put("tools", JSONObject().put("listChanged", false))
                        .put("resources", JSONObject().put("subscribe", false).put("listChanged", false))
                    )
                    .put("serverInfo", JSONObject().put("name", MCP_SERVER_NAME).put("version", MCP_SERVER_VERSION))
                jsonRpcSuccess(id, result)
            }

            "notifications/initialized" -> {
                if (id == null) {
                    httpNoContent(204)
                } else {
                    jsonRpcSuccess(id, JSONObject())
                }
            }

            "ping" -> jsonRpcSuccess(id, JSONObject())

            "tools/list" -> {
                val tools = JSONArray()
                pluginManager.getEnabledPlugins().forEach { plugin ->
                    plugin.getTools().forEach { tool ->
                        tools.put(
                            JSONObject()
                                .put("name", "${plugin.id}/${tool.name}")
                                .put("description", tool.description)
                                .put("inputSchema", tool.inputSchema)
                        )
                    }
                }
                jsonRpcSuccess(id, JSONObject().put("tools", tools))
            }

            "resources/list" -> {
                val result = JSONObject().put("resources", listMcpResources())
                jsonRpcSuccess(id, result)
            }

            "resources/read" -> {
                val params = request.optJSONObject("params") ?: JSONObject()
                val uri = params.optString("uri")
                val contents = handleResourceRead(uri)
                if (contents == null) {
                    jsonRpcError(id, -32602, "Invalid URI or failed to read")
                } else {
                    val result = JSONObject().put("contents", contents)
                    jsonRpcSuccess(id, result)
                }
            }

            "tools/call" -> {
                val params = request.optJSONObject("params") ?: JSONObject()
                val fullName = params.optString("name")
                val parts = fullName.split("/", limit = 2)
                if (parts.size != 2) {
                    return jsonRpcError(id, -32602, "Invalid tool name format. Expected pluginId/toolName")
                }
                val pluginId = parts[0]
                val toolName = parts[1]

                if (!pluginManager.checkAndRequestPermission(pluginId, toolName)) {
                    return jsonRpcError(id, -32000, "Tool execution blocked by user or permission settings")
                }

                val tool = pluginManager.getTool(pluginId, toolName)
                if (tool == null) {
                    return jsonRpcError(id, -32601, "Tool not found: $fullName")
                }

                val args = params.optJSONObject("arguments") ?: JSONObject()
                val toolResult = tool.call(this, args)
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
            .put(
                JSONObject()
                    .put("name", "keep_screen_on")
                    .put("description", "Keeps the screen on or allows it to turn off")
                    .put(
                        "inputSchema",
                        JSONObject()
                            .put("type", "object")
                            .put("required", JSONArray().put("enabled"))
                            .put("properties", JSONObject().put("enabled", JSONObject().put("type", "boolean")))
                    )
            )
    }

    private fun handleToolCall(toolName: String, args: JSONObject): JSONObject {
        return when (toolName) {
            "health" -> JSONObject()
                .put("ok", true)
                .put("serverUrl", serverUrl())
                .put("accessibilityConnected", McpAccessibilityService.isConnected())
                .put("screenshotSupported", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)

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

            "keep_screen_on" -> {
                val enabled = args.optBoolean("enabled")
                val prefs = getSharedPreferences(ServiceActions.PREFS_NAME, MODE_PRIVATE)
                prefs.edit().putBoolean(ServiceActions.PREF_KEEP_SCREEN_ON, enabled).apply()
                setKeepScreenOn(enabled)
                JSONObject().put("ok", true).put("enabled", enabled)
            }

            else -> JSONObject().put("ok", false).put("error", "Unknown tool: $toolName")
        }
    }

    override fun requestPermission(requestId: Int, pluginId: String, toolName: String) {
        AppLogger.i(TAG, "Requesting permission for $pluginId/$toolName (id=$requestId)")
        
        // Broadcast for the Activity to pick up if it's in foreground
        val intent = Intent(ServiceActions.ACTION_PERMISSION_DECISION)
            .putExtra(ServiceActions.EXTRA_REQUEST_ID, requestId)
            .putExtra(ServiceActions.EXTRA_PLUGIN_ID, pluginId)
            .putExtra(ServiceActions.EXTRA_TOOL_NAME, toolName)
        sendBroadcast(intent)

        // Also show notification with buttons for background state
        showPermissionNotification(requestId, pluginId, toolName)
    }

    private fun showPermissionNotification(requestId: Int, pluginId: String, toolName: String) {
        val allowIntent = Intent(this, McpServerService::class.java).apply {
            action = ServiceActions.ACTION_PERMISSION_DECISION
            putExtra(ServiceActions.EXTRA_REQUEST_ID, requestId)
            putExtra(ServiceActions.EXTRA_DECISION, true)
        }
        val denyIntent = Intent(this, McpServerService::class.java).apply {
            action = ServiceActions.ACTION_PERMISSION_DECISION
            putExtra(ServiceActions.EXTRA_REQUEST_ID, requestId)
            putExtra(ServiceActions.EXTRA_DECISION, false)
        }

        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_UPDATE_CURRENT
        
        val allowPending = PendingIntent.getService(this, requestId * 2, allowIntent, flag)
        val denyPending = PendingIntent.getService(this, requestId * 2 + 1, denyIntent, flag)

        val notification = NotificationCompat.Builder(this, ForegroundNotificationFactory.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("MCP Tool Request")
            .setContentText("Allow $pluginId/$toolName?")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(true)
            .addAction(0, "Allow", allowPending)
            .addAction(0, "Deny", denyPending)
            .build()

        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(requestId + 1000, notification)
    }

    private fun mcpServerManifest(): JSONObject {
        return JSONObject()
            .put("name", MCP_SERVER_NAME)
            .put("version", MCP_SERVER_VERSION)
            .put("protocolVersion", MCP_PROTOCOL_VERSION)
            .put("transport", "http")
            .put("jsonRpcEndpoint", "/mcp")
            .put("capabilities", JSONObject()
                .put("tools", JSONObject().put("listChanged", false))
                .put("resources", JSONObject().put("subscribe", false).put("listChanged", false))
            )
            .put("tools", listMcpTools())
            .put("resources", listMcpResources())
    }

    private fun listMcpResources(): JSONArray {
        val resources = JSONArray()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            resources.put(
                JSONObject()
                    .put("uri", "screenshot://current")
                    .put("name", "Current screen screenshot")
                    .put("mimeType", "image/png")
                    .put("description", "A real-time screenshot of the current screen")
            )
        }
        return resources
    }

    private fun handleResourceRead(uri: String): JSONArray? {
        if (uri == "screenshot://current") {
            val base64 = McpAccessibilityService.takeScreenshot()
            if (base64 != null) {
                return JSONArray().put(
                    JSONObject()
                        .put("uri", uri)
                        .put("mimeType", "image/png")
                        .put("blob", base64)
                )
            }
        }
        return null
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

    private fun httpNoContent(statusCode: Int = 204): String {
        return buildString {
            append("HTTP/1.1 $statusCode No Content\r\n")
            append("Connection: close\r\n\r\n")
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
