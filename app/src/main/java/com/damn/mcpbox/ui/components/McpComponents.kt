package com.damn.mcpbox.ui.components

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.damn.mcpbox.mcp.McpConfigManager
import com.damn.mcpbox.mcp.McpPermission
import com.damn.mcpbox.mcp.McpPlugin
import com.damn.mcpbox.mcp.McpTool
import com.damn.mcpbox.mcp.PluginConfig
import com.damn.mcpbox.mcp.ToolConfig
import com.damn.mcpbox.ui.theme.MCPboxTheme
import org.json.JSONObject

/**
 * A simpler Launcher Picker used in HomeScreen
 */
data class LauncherApp(
    val label: String,
    val packageName: String,
)

@Composable
fun LauncherPicker(
    show: Boolean,
    apps: List<LauncherApp>,
    onDismiss: () -> Unit,
    onSelect: (LauncherApp) -> Unit
) {
    if (show) {
        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = onDismiss) {
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
                                    onSelect(app)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpSettingsScreen(
    plugins: List<McpPlugin>,
    configManager: McpConfigManager,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MCP Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(plugins) { plugin ->
                    PluginItem(plugin, configManager)
                }
            }
        }
    }
}

@Composable
fun PluginItem(plugin: McpPlugin, configManager: McpConfigManager) {
    val context = LocalContext.current
    var config by remember { mutableStateOf(configManager.getPluginConfig(plugin.id)) }
    var expanded by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            config = config.copy(enabled = true)
            configManager.savePluginConfig(plugin.id, config)
        } else {
            config = config.copy(enabled = false)
            configManager.savePluginConfig(plugin.id, config)
            android.widget.Toast.makeText(context, "Permissions denied for ${plugin.name}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // Check permissions reactively
    val hasPermissions = plugin.requiredPermissions.all {
        ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    LaunchedEffect(hasPermissions) {
        if (config.enabled && !hasPermissions) {
            config = config.copy(enabled = false)
            configManager.savePluginConfig(plugin.id, config)
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.padding(end = 8.dp)
                )
                Column {
                    Text(plugin.name, style = MaterialTheme.typography.bodyLarge)
                    Text(plugin.description, style = MaterialTheme.typography.bodySmall)
                }
            }
            Switch(
                checked = config.enabled,
                onCheckedChange = { enabled ->
                    if (enabled) {
                        val missing = plugin.requiredPermissions.filter {
                            ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
                        }
                        if (missing.isEmpty()) {
                            config = config.copy(enabled = true)
                            configManager.savePluginConfig(plugin.id, config)
                        } else {
                            permissionLauncher.launch(missing.toTypedArray())
                        }
                    } else {
                        config = config.copy(enabled = false)
                        configManager.savePluginConfig(plugin.id, config)
                    }
                }
            )
        }

        if (expanded && config.enabled) {
            Column(modifier = Modifier.padding(start = 24.dp, top = 8.dp)) {
                BulkActionDropdown(onAction = { permission ->
                    val newToolConfigs = plugin.getTools().associate { it.name to ToolConfig(permission) }
                    config = config.copy(toolConfigs = newToolConfigs)
                    configManager.savePluginConfig(plugin.id, config)
                })

                plugin.getTools().forEach { tool ->
                    ToolItem(tool, config) { newToolConfig ->
                        val newMap = config.toolConfigs.toMutableMap()
                        newMap[tool.name] = newToolConfig
                        config = config.copy(toolConfigs = newMap)
                        configManager.savePluginConfig(plugin.id, config)
                    }
                }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
fun ToolItem(tool: McpTool, pluginConfig: PluginConfig, onUpdate: (ToolConfig) -> Unit) {
    val toolConfig = pluginConfig.toolConfigs[tool.name] ?: ToolConfig()
    
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(tool.name, style = MaterialTheme.typography.bodyMedium)
            Text(tool.description, style = MaterialTheme.typography.bodySmall)
        }
        PermissionDropdown(toolConfig.permission) { newPermission ->
            onUpdate(toolConfig.copy(permission = newPermission))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionDropdown(current: McpPermission, onSelected: (McpPermission) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = current.name,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier.menuAnchor().fillMaxWidth(0.45f),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            textStyle = MaterialTheme.typography.bodySmall
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            McpPermission.values().forEach { permission ->
                DropdownMenuItem(
                    text = { Text(permission.name) },
                    onClick = {
                        onSelected(permission)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BulkActionDropdown(onAction: (McpPermission) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = "Bulk Action",
            onValueChange = {},
            readOnly = true,
            modifier = Modifier.menuAnchor().fillMaxWidth(0.6f),
            label = { Text("Set all tools to...") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            McpPermission.values().forEach { permission ->
                DropdownMenuItem(
                    text = { Text("Set all to ${permission.name}") },
                    onClick = {
                        onAction(permission)
                        expanded = false
                    }
                )
            }
        }
    }
}

// --- Previews ---

@Preview(showBackground = true)
@Composable
fun PreviewToolItem() {
    MCPboxTheme {
        ToolItem(
            tool = object : McpTool {
                override val name = "test_tool"
                override val description = "This is a test tool description"
                override val inputSchema = JSONObject()
                override fun call(context: android.content.Context, arguments: JSONObject) = JSONObject()
            },
            pluginConfig = PluginConfig(enabled = true),
            onUpdate = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewLauncherPicker() {
    MCPboxTheme {
        LauncherPicker(
            show = true,
            apps = listOf(
                LauncherApp("Camera", "com.android.camera"),
                LauncherApp("Maps", "com.google.android.apps.maps")
            ),
            onDismiss = {},
            onSelect = {}
        )
    }
}
