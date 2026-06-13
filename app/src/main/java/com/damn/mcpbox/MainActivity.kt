package com.damn.mcpbox

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
    val prefs = remember { context.getSharedPreferences(ServiceActions.PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    val apps = remember { loadLaunchableApps(context.packageManager) }
    var showPicker by remember { mutableStateOf(false) }
    var selectedApp by remember { mutableStateOf<LauncherApp?>(null) }
    var serverStarted by remember { mutableStateOf(false) }
    var virtualDisplayEnabled by remember { mutableStateOf(false) }
    var keepScreenOn by remember {
        mutableStateOf(prefs.getBoolean(ServiceActions.PREF_KEEP_SCREEN_ON, false))
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("MCP-box Controller", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Use MCP server without virtual display, or enable virtual display tools when needed.",
            style = MaterialTheme.typography.bodyMedium
        )

        Button(onClick = {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }) {
            Text("Open Accessibility Settings")
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Keep Screen On")
            Switch(
                checked = keepScreenOn,
                onCheckedChange = { enabled ->
                    keepScreenOn = enabled
                    prefs.edit().putBoolean(ServiceActions.PREF_KEEP_SCREEN_ON, enabled).apply()
                    context.startService(
                        Intent(context, McpServerService::class.java)
                            .setAction(ServiceActions.ACTION_SET_KEEP_SCREEN_ON)
                            .putExtra(ServiceActions.EXTRA_ENABLED, enabled)
                    )
                }
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                startForegroundCompat(
                    context,
                    Intent(context, McpServerService::class.java)
                        .setAction(ServiceActions.ACTION_SERVER_START)
                )
                serverStarted = true
            }) {
                Text("Start MCP Server")
            }

            Button(onClick = {
                startForegroundCompat(
                    context,
                    Intent(context, McpServerService::class.java)
                        .setAction(ServiceActions.ACTION_SERVER_STOP)
                )
                serverStarted = false
            }) {
                Text("Stop MCP Server")
            }
        }

        if (serverStarted) {
            Text(
                text = "MCP URL: ${McpServerService.serverUrl()}",
                style = MaterialTheme.typography.bodyLarge
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
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

    if (showPicker && virtualDisplayEnabled) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = { showPicker = false }) {
                    Text("Done")
                }
            },
            title = { Text("Installed Launchable Apps") },
            text = {
                LazyColumn {
                    items(apps) { app ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedApp = app
                                    showPicker = false
                                }
                                .padding(vertical = 8.dp)
                        ) {
                            Text(text = app.label)
                            Text(text = app.packageName, style = MaterialTheme.typography.bodySmall)
                        }
                        HorizontalDivider()
                    }
                }
            }
        )
    }
}

private fun startForegroundCompat(context: android.content.Context, intent: Intent) {
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

private data class LauncherApp(
    val label: String,
    val packageName: String,
)
