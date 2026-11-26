# MCP-box Architecture

## Purpose
`MCP-box` is an Android prototype that combines:
- an app picker/controller UI,
- a floating virtual-screen launcher flow,
- a localhost MCP-style HTTP bridge,
- and an `AccessibilityService` execution layer.

Primary goal: select installed apps and attempt to launch them on a virtual display while exposing automation actions through a local API.

## Code Map
- `app/src/main/java/com/damn/mcpbox/MainActivity.kt`
  - Compose controller screen.
  - Starts/stops core services.
  - MCP Plugin & Tool management UI (permissions, enable/disable).
- `app/src/main/java/com/damn/mcpbox/mcp/`
  - `McpCore.kt`: Plugin/Tool interfaces and Permission enums.
  - `McpPluginManager.kt`: Registry and execution coordinator.
  - `McpConfigManager.kt`: Persistent storage for plugin/tool settings.
- `app/src/main/java/com/damn/mcpbox/mcp/plugins/`
  - Modular plugin implementations (Accessibility, Contacts, Calendar, Media, SMS, Settings).
- `app/src/main/java/com/damn/mcpbox/service/FloatingVirtualDisplayService.kt`
  - `specialUse` foreground service hosting draggable overlay (`WindowManager`).
  - Creates a `720×1280` @ `320 dpi` portrait `VirtualDisplay` (`McpBoxVirtualDisplay`) on a scaled 9:16 `SurfaceView` (`holder.setFixedSize(720, 1280)`).
  - Launches apps via Shizuku (`ShizukuHelper.launchOnDisplay(...)`) when ready; otherwise falls back to `ActivityOptions.setLaunchDisplayId(...)` / main display.
- `app/src/main/java/com/damn/mcpbox/shizuku/`
  - `ShizukuHelper.kt`: Tracks Shizuku binder/permission state (`NOT_RUNNING`, `PERMISSION_REQUIRED`, `READY`), binds `ShellUserService`, and runs display launches off the main thread.
  - `ShellUserService.kt` + `app/src/main/aidl/com/damn/mcpbox/shizuku/IShellUserService.aidl`: Privileged Shizuku `UserService` running `am start --display <id> -n <component>` with `shell` UID privileges.
- `app/src/main/java/com/damn/mcpbox/service/McpServerService.kt`
  - `specialUse` foreground service with LAN/localhost HTTP server (`0.0.0.0:8765`).
  - Exposes `isRunning: StateFlow<Boolean>` for UI synchronization.
  - Maps MCP JSON-RPC 2.0 endpoints to plugins and resources.
- `app/src/main/java/com/damn/mcpbox/accessibility/McpAccessibilityService.kt`
  - Performs global actions (`back`, `home`, `recents`, `notifications`).
  - Performs click-by-text traversal in active window.
- `app/src/main/java/com/damn/mcpbox/service/ServiceActions.kt`
  - Shared action constants between UI and services.
- `app/src/main/java/com/damn/mcpbox/service/ForegroundNotificationFactory.kt`
  - Notification channel + foreground notification builder with tap-to-open (`MainActivity`) and **Stop** action button.

## Runtime Interaction Flow
1. User opens `MainActivity`.
2. User grants overlay permission and enables accessibility service.
3. User starts floating service and MCP server service.
4. App picker selects target package.
5. UI (or MCP `/launch`) sends `ACTION_LAUNCH_PACKAGE` to floating service.
6. Floating service launches package on virtual display when surface/display is ready.
7. MCP `/action` routes to `McpAccessibilityService` for interaction commands.

## Endpoint Contract (Local)
Base URL: `http://127.0.0.1:8765`

- `GET /health`
  - Returns service health, accessibility state, and screenshot support.
- `GET /tools`
  - Returns dynamic tool list from enabled plugins (namespaced as `pluginId/toolName`).
- `POST /` or `POST /mcp`
  - MCP JSON-RPC 2.0 endpoint (supports `initialize`, `tools/list`, `tools/call`, `resources/list`, `resources/read`).

## Plugin Development (Skill)
To add a new plugin:
1. Implement `McpPlugin` interface in `com.damn.mcpbox.mcp.plugins`.
2. Define `requiredPermissions` (Android runtime permissions).
3. Implement `McpTool` classes for each functionality.
4. Register the plugin in `McpPluginManager.init {}`.
5. Update `AndroidManifest.xml` with any new permissions or features.

## Permissions and Manifest Dependencies
Defined in `app/src/main/AndroidManifest.xml`:
- `android.permission.INTERNET`, `SYSTEM_ALERT_WINDOW`, `WAKE_LOCK`
- `android.permission.FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` (with `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` on both foreground services to avoid Android 15+ `dataSync` 6-hour timeout)
- `android.permission.POST_NOTIFICATIONS`
- Runtime Permissions: `READ_CONTACTS`, `READ_CALENDAR`, `READ_SMS`, `READ_EXTERNAL_STORAGE`, `READ_MEDIA_*`
- Accessibility service binding: `android.permission.BIND_ACCESSIBILITY_SERVICE`
- Shizuku binder delivery provider: `rikka.shizuku.ShizukuProvider` (`authorities="${applicationId}.shizuku"`) and `<package android:name="moe.shizuku.privileged.api" />` in `<queries>`.
- Package visibility query for launcher activities.

## Platform Constraints (Important)
- Launching third-party apps into custom virtual displays is OEM/version/policy dependent.
- Behavior may vary across Android 12-15+ and vendor ROMs.
- Accessibility-based automation must be user-consented and policy-compliant.
- Overlay behavior can be restricted by OEM battery/security managers.

## Operational Notes for Future AI Sessions
- Treat this project as a prototype, not guaranteed cross-device product behavior.
- Preserve foreground service notifications and explicit user-consent UX.
- Prefer additive changes to MCP endpoints; avoid breaking existing payloads.
- Validate with `get_errors` first, then run Gradle tasks when Java/SDK are available.

