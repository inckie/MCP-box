# MCP-box

Android prototype that:

- shows a draggable floating window containing a `VirtualDisplay` surface
- lets you pick an installed launchable app and request launching it on that display
- runs a local MCP-style HTTP bridge (`127.0.0.1:8765`) that forwards actions to an Accessibility Service

## Features

- App picker in `MainActivity`
- Floating virtual screen service: `FloatingVirtualDisplayService`
- Local MCP server service: `McpServerService`
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

Launching third-party apps into a custom virtual display is restricted on many Android builds (OEM and Android version dependent). This project implements the API flow, but behavior can vary by device policy.

## Run

1. Build and install app.
2. Open app and grant overlay permission.
3. Enable `MCP-box` accessibility service in system settings.
4. Tap **Start Floating Screen**, then **Start MCP Server**.
5. Pick an app and tap **Launch On Virtual Screen**.

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
- `screenshot://current`: Real-time PNG screenshot (Android 11+).

### Available Tools (by Plugin)
- **accessibility/**: `health`, `global_action`, `click_text`, `click_view_id`, `set_text`, `scroll`, `ui_snapshot`, `find_nodes`, `tap`, `swipe`, `hardware_button`
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

