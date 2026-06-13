package com.damn.mcpbox

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.core.content.edit
import com.damn.mcpbox.accessibility.McpAccessibilityService
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import com.damn.mcpbox.mcp.McpConfigManager
import com.damn.mcpbox.mcp.McpPluginManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.damn.mcpbox.ui.theme.MCPboxTheme
import com.damn.mcpbox.service.FloatingVirtualDisplayService
import com.damn.mcpbox.service.McpServerService
import com.damn.mcpbox.service.ServiceActions
import com.damn.mcpbox.ui.components.LauncherApp
import com.damn.mcpbox.ui.components.LauncherPicker
import com.damn.mcpbox.ui.components.McpSettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MCPboxTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    HomeScreen(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(ServiceActions.PREFS_NAME, Context.MODE_PRIVATE) }
    val apps = remember { loadLaunchableApps(context.packageManager) }
    var showPicker by remember { mutableStateOf(false) }
    var selectedApp by remember { mutableStateOf<LauncherApp?>(null) }
    var serverStarted by remember { mutableStateOf(false) }
    var virtualDisplayEnabled by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var keepScreenOn by remember {
        mutableStateOf(prefs.getBoolean(ServiceActions.PREF_KEEP_SCREEN_ON, false))
    }

    var isAccessibilityEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled(context, McpAccessibilityService::class.java)) }

    val pendingPermissions = remember { mutableStateListOf<PermissionRequest>() }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ServiceActions.ACTION_PERMISSION_DECISION) {
                    val requestId = intent.getIntExtra(ServiceActions.EXTRA_REQUEST_ID, -1)
                    val pluginId = intent.getStringExtra(ServiceActions.EXTRA_PLUGIN_ID) ?: ""
                    val toolName = intent.getStringExtra(ServiceActions.EXTRA_TOOL_NAME) ?: ""
                    
                    if (!intent.hasExtra(ServiceActions.EXTRA_DECISION)) {
                        pendingPermissions.add(PermissionRequest(requestId, pluginId, toolName))
                    }
                }
            }
        }
        val filter = IntentFilter(ServiceActions.ACTION_PERMISSION_DECISION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    // Refresh accessibility state when returning to foreground
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        isAccessibilityEnabled = isAccessibilityServiceEnabled(context, McpAccessibilityService::class.java)
    }

    val pluginManager = remember { McpPluginManager(context) }
    val configManager = remember { McpConfigManager(context) }
    val plugins = remember { pluginManager.getAllPlugins() }

    if (showSettings) {
        McpSettingsScreen(
            plugins = plugins,
            configManager = configManager,
            onBack = { showSettings = false }
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("MCP Server", style = MaterialTheme.typography.headlineSmall)
                if (serverStarted) {
                    Text(
                        text = McpServerService.serverUrl(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Switch(
                checked = serverStarted,
                onCheckedChange = { enabled ->
                    if (enabled) {
                        startForegroundCompat(
                            context,
                            Intent(context, McpServerService::class.java)
                                .setAction(ServiceActions.ACTION_SERVER_START)
                        )
                    } else {
                        startForegroundCompat(
                            context,
                            Intent(context, McpServerService::class.java)
                                .setAction(ServiceActions.ACTION_SERVER_STOP)
                        )
                    }
                    serverStarted = enabled
                }
            )
            androidx.compose.foundation.layout.Spacer(Modifier.padding(horizontal = 4.dp))
            IconButton(onClick = { showSettings = true }) {
                Icon(Icons.Default.Settings, contentDescription = "Settings")
            }
        }

        HorizontalDivider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Accessibility Service", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (isAccessibilityEnabled) "Enabled & Running" else "Disabled (Required for automation)",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isAccessibilityEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }
            Switch(
                checked = isAccessibilityEnabled,
                onCheckedChange = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Text("Keep Screen On")
            Switch(
                checked = keepScreenOn,
                onCheckedChange = { enabled ->
                    keepScreenOn = enabled
                    prefs.edit { putBoolean(ServiceActions.PREF_KEEP_SCREEN_ON, enabled) }
                    context.startService(
                        Intent(context, McpServerService::class.java)
                            .setAction(ServiceActions.ACTION_SET_KEEP_SCREEN_ON)
                            .putExtra(ServiceActions.EXTRA_ENABLED, enabled)
                    )
                }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Text("Enable Virtual Display (optional)")
            Switch(
                checked = virtualDisplayEnabled,
                onCheckedChange = { virtualDisplayEnabled = it }
            )
        }

        if (virtualDisplayEnabled) {
            Button(onClick = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        "package:${context.packageName}".toUri()
                    )
                )
            }) {
                Text("Grant Overlay Permission")
            }

            Button(onClick = {
                startForegroundCompat(
                    context,
                    Intent(context, FloatingVirtualDisplayService::class.java)
                        .setAction(ServiceActions.ACTION_SHOW_OVERLAY)
                )
            }) {
                Text("Start Floating Screen")
            }

            Button(onClick = { showPicker = true }) {
                Text("Pick App To Launch")
            }

            Text(
                text = "Selected: ${selectedApp?.label ?: "none"}",
                style = MaterialTheme.typography.bodyLarge
            )

            Button(onClick = {
                val packageName = selectedApp?.packageName ?: return@Button
                startForegroundCompat(
                    context,
                    Intent(context, FloatingVirtualDisplayService::class.java)
                        .setAction(ServiceActions.ACTION_LAUNCH_PACKAGE)
                        .putExtra(ServiceActions.EXTRA_PACKAGE_NAME, packageName)
                )
            }, enabled = selectedApp != null) {
                Text("Launch On Virtual Screen")
            }
        }
    }

    LauncherPicker(
        show = showPicker && virtualDisplayEnabled,
        apps = apps,
        onDismiss = { showPicker = false },
        onSelect = { app ->
            selectedApp = app
            showPicker = false
        }
    )

    pendingPermissions.firstOrNull()?.let { request ->
        AlertDialog(
            onDismissRequest = { /* Must make a choice */ },
            title = { Text("Tool Permission Request") },
            text = { Text("Allow ${request.pluginId}/${request.toolName} to execute?") },
            confirmButton = {
                TextButton(onClick = {
                    sendDecision(context, request.requestId, true)
                    pendingPermissions.remove(request)
                }) { Text("Allow") }
            },
            dismissButton = {
                TextButton(onClick = {
                    sendDecision(context, request.requestId, false)
                    pendingPermissions.remove(request)
                }) { Text("Deny") }
            }
        )
    }
}

private fun sendDecision(context: Context, requestId: Int, allowed: Boolean) {
    val intent = Intent(context, McpServerService::class.java).apply {
        action = ServiceActions.ACTION_PERMISSION_DECISION
        putExtra(ServiceActions.EXTRA_REQUEST_ID, requestId)
        putExtra(ServiceActions.EXTRA_DECISION, allowed)
    }
    context.startService(intent)
}

private data class PermissionRequest(
    val requestId: Int,
    val pluginId: String,
    val toolName: String
)

private fun isAccessibilityServiceEnabled(context: Context, service: Class<out AccessibilityService>): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)

    for (enabledService in enabledServices) {
        val enabledServiceInfo = enabledService.resolveInfo.serviceInfo
        if (enabledServiceInfo.packageName == context.packageName && enabledServiceInfo.name == service.name) {
            return true
        }
    }
    return false
}

private fun startForegroundCompat(context: Context, intent: Intent) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
}

private fun loadLaunchableApps(packageManager: PackageManager): List<LauncherApp> {
    val launchIntent = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
    }
    val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.queryIntentActivities(launchIntent, PackageManager.ResolveInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        packageManager.queryIntentActivities(launchIntent, 0)
    }
    return resolveInfos
        .map {
            LauncherApp(
                label = it.loadLabel(packageManager).toString(),
                packageName = it.activityInfo.packageName
            )
        }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}
