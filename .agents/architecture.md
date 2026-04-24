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
  - Shows app picker and sends launch requests.
- `app/src/main/java/com/damn/mcpbox/service/FloatingVirtualDisplayService.kt`
  - Foreground service hosting draggable overlay (`WindowManager`).
  - Creates `VirtualDisplay` on `SurfaceView` surface.
  - Attempts app launch with `ActivityOptions.setLaunchDisplayId(...)`.
- `app/src/main/java/com/damn/mcpbox/service/McpServerService.kt`
  - Foreground service with localhost HTTP server (`127.0.0.1:8765`).
  - Maps MCP-like endpoints to app/service actions.
- `app/src/main/java/com/damn/mcpbox/accessibility/McpAccessibilityService.kt`
  - Performs global actions (`back`, `home`, `recents`, `notifications`).
  - Performs click-by-text traversal in active window.
- `app/src/main/java/com/damn/mcpbox/service/ServiceActions.kt`
  - Shared action constants between UI and services.
- `app/src/main/java/com/damn/mcpbox/service/ForegroundNotificationFactory.kt`
  - Notification channel + foreground notification builder.

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
  - Returns service health and accessibility connection state.
- `GET /apps`
  - Returns launchable apps (`label`, `packageName`).
- `POST /overlay/show`
  - Shows draggable floating screen.
- `POST /overlay/hide`
  - Hides floating screen.
- `POST /launch`
  - Body: `{ "packageName": "com.example.app" }`.
  - Requests launch on virtual display.
- `POST /action`
  - Global action: `{ "type": "global", "name": "back" }`.
  - Click text: `{ "type": "clickText", "text": "Allow" }`.

## Permissions and Manifest Dependencies
Defined in `app/src/main/AndroidManifest.xml`:
- `android.permission.INTERNET`
- `android.permission.SYSTEM_ALERT_WINDOW`
- `android.permission.FOREGROUND_SERVICE`
- `android.permission.FOREGROUND_SERVICE_DATA_SYNC`
- `android.permission.POST_NOTIFICATIONS`
- Accessibility service binding: `android.permission.BIND_ACCESSIBILITY_SERVICE`
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

