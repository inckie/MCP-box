# MCP-box

Android prototype that:

- shows a draggable floating window containing a `VirtualDisplay` surface
- lets you pick an installed launchable app and request launching it on that display
- runs a local MCP-style HTTP bridge (`127.0.0.1:8765`) that forwards actions to an Accessibility Service

## Features

- App picker in `MainActivity`
- Floating virtual screen service: `FloatingVirtualDisplayService` (simulates a `720×1280` @ `320 dpi` portrait display in a scaled 9:16 overlay)
- Privileged virtual-display app launching via **Shizuku** (on-device) or **ADB** (from PC)
- Local MCP server service: `McpServerService` (`specialUse` foreground service with tap-to-open and **Stop** notification action)
- Modular MCP Plugin System:
    - **Accessibility**: UI automation (clicks, scrolls, snapshots, gestures).
    - **Contacts**: List device contacts.
    - **Calendar**: List calendar events.
    - **Media**: List photos, videos, and audio files.
    - **SMS**: List and search text messages.
    - **Settings**: Device info and battery status.
- UI Management: Toggle plugins and set per-tool permissions (Allow, Ask, Block).
- Support for MCP Resources: `screenshot://current`.

## Important platform note

Android restricts unprivileged apps from launching third-party apps (different UIDs) onto a private `VirtualDisplay` (`FLAG_PRIVATE`) to prevent unauthorized screen capture. MCP-box supports two ways to launch arbitrary apps onto `McpBoxVirtualDisplay` with `shell` (UID 2000) privileges:

1. **Shizuku Mode (On-device, no PC required after setup)**
2. **ADB Mode (From a PC over USB / Wireless ADB)**

If neither is used, `FloatingVirtualDisplayService` attempts a direct launch via `ActivityOptions.setLaunchDisplayId(...)` and falls back to the main screen when blocked by platform policy.

## Run

1. Build and install the app.
2. Open the app and grant **Overlay** permission.
3. Enable the `MCP-box` **Accessibility Service** in system settings.
4. Toggle **MCP Server** on, and optionally enable **Virtual Display** and tap **Start Floating Screen**.

### Launching Apps on the Virtual Screen

#### Option 1: Shizuku Mode (On-device)

1. Install and start the **Shizuku** app (`moe.shizuku.privileged.api`) via Wireless Debugging or ADB.
2. In MCP-box, enable **Virtual Display** — the **Shizuku** status row will show **Running — permission required**.
3. Tap **Grant** to authorize MCP-box in Shizuku (status changes to **Ready**).
4. Tap **Start Floating Screen**, pick an app, and tap **Launch On Virtual Screen**.
   - MCP-box binds its Shizuku `ShellUserService` (`IShellUserService.aidl`) and runs `am start --display <displayId> -n <package>/<activity>` on-device.

#### Option 2: ADB Mode (From PC)

1. In MCP-box, tap **Start Floating Screen** so `McpBoxVirtualDisplay` is created.
2. Find the virtual display's `displayId`:
   ```bash
   adb shell "dumpsys display | grep -E 'McpBoxVirtualDisplay.*displayId'"
   ```
3. Resolve the target app's launcher activity (example: `com.damn.aisuper.androidapp`):
   ```bash
   adb shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER com.damn.aisuper.androidapp
   ```
4. Launch the app onto the virtual display (replace `<DISPLAY_ID>` and `<COMPONENT>`):
   ```bash
   adb shell am start --display <DISPLAY_ID> -n <COMPONENT>
   ```
   *(If the app is already running on the main screen, run `adb shell am force-stop <PACKAGE>` first so it starts cleanly on the virtual display.)*

## MCP HTTP endpoints

Base URL: `http://127.0.0.1:8765`

- `GET /` (MCP manifest/discovery JSON)
- `GET /mcp` (same MCP manifest/discovery JSON)
- `GET /.well-known/mcp` (same MCP manifest/discovery JSON)
- `POST /mcp` (JSON-RPC 2.0 endpoint)
- `GET /health`
- `GET /apps`
- `POST /overlay/show`
- `POST /overlay/hide`
- `POST /launch` with JSON body: `{"packageName":"com.example.app"}`
- `POST /action` (Legacy REST actions for accessibility)

## MCP JSON-RPC Tools & Resources

Tools are dynamically registered from enabled plugins and follow the `pluginId/toolName` naming convention.

### Available Resources
- `screenshot://current`: Real-time PNG screenshot of the main screen (Android 11+).
- `screenshot://virtual`: Real-time PNG screenshot (`720×1280`) of `McpBoxVirtualDisplay` via `PixelCopy` (Android 8+).

### Available Tools (by Plugin)
- **accessibility/**: `health`, `global_action`, `click_text`, `click_view_id`, `set_text`, `input_text`, `key_event`, `scroll`, `ui_snapshot`, `find_nodes`, `tap`, `swipe`, `hardware_button`, `screenshot`
  - All `accessibility/*` tools (except `health`) accept optional `target: "main" | "virtual"` or explicit `displayId: <int>`.
  - On the virtual display (`target: "virtual"`), `tap`, `swipe`, `scroll`, `set_text`, `input_text`, `key_event`, and `global_action` (`back`/`home`) route through Shizuku (`input -d <displayId> ...`) when Shizuku is ready, and `screenshot` uses `PixelCopy`.
  - Note: Android hides windows on `FLAG_PRIVATE` virtual displays from `AccessibilityService.getWindowsOnAllDisplays()`, so accessibility-tree inspection (`ui_snapshot`, `find_nodes`, `click_text`, `click_view_id`) only works on the main display — use `screenshot` + coordinate/input tools (`tap`, `swipe`, `scroll`, `input_text`, `key_event`) on the virtual screen.
- **contacts/**: `list`
- **calendar/**: `list_events`
- **media/**: `list_images`, `list_videos`, `list_audio`
- **sms/**: `list_messages`, `search_messages`
- **settings/**: `get_device_info`, `get_battery_status`
- **server/**: `keep_screen_on`

JSON-RPC notes:

- `initialize` returns `protocolVersion`, `serverInfo`, and `capabilities.tools.listChanged`
- `notifications/initialized` (without `id`) returns HTTP `204 No Content`
- `ping` is supported and returns an empty JSON object

## Quick local test (adb)

```powershell
adb shell "toybox nc 127.0.0.1 8765 <<'EOF'
GET /health HTTP/1.1
Host: 127.0.0.1

EOF"
```

If your shell/device lacks `nc`, use any Android HTTP client app or run requests from code inside the app process.

