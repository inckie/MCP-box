package com.damn.mcpbox.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
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
                "GET" if path == "/health" -> httpJson(
                    200,
                    JSONObject()
                        .put("ok", true)
                        .put("serverUrl", serverUrl())
                        .put("accessibilityConnected", McpAccessibilityService.isConnected())
                )
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

                val tool = pluginManager.getTool(pluginId, toolName) ?: return jsonRpcError(
                    id,
                    -32601,
                    "Tool not found: $fullName"
                )

                val args = params.optJSONObject("arguments") ?: JSONObject()
                val toolResult = tool.call(this, args)
                val result = JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", toolResult.toString())))
                jsonRpcSuccess(id, result)
            }

            else -> jsonRpcError(id, -32601, "Method not found")
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
