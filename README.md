# MCP-box

Android prototype that:

- shows a draggable floating window containing a `VirtualDisplay` surface
- lets you pick an installed launchable app and request launching it on that display
- runs a local MCP-style HTTP bridge (`127.0.0.1:8765`) that forwards actions to an Accessibility Service

## Features

- App picker in `MainActivity`
- Floating virtual screen service: `FloatingVirtualDisplayService`
- Local MCP server service: `McpServerService`
- Accessibility automation bridge: `McpAccessibilityService`

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
- `GET /tools` (tool list JSON)
- `GET /health`
- `GET /apps`
- `POST /overlay/show`
- `POST /overlay/hide`
- `POST /launch` with JSON body: `{"packageName":"com.example.app"}`
- `POST /action` with JSON body:
  - global action: `{"type":"global","name":"back"}` (`home`, `recents`, `notifications`)
  - click text: `{"type":"clickText","text":"Allow"}`
  - click by view id: `{"type":"clickViewId","viewId":"com.example:id/button_ok"}`
  - set text: `{"type":"setText","viewId":"com.example:id/input","value":"hello"}`
  - scroll: `{"type":"scroll","direction":"forward","viewId":"com.example:id/list"}`
  - UI snapshot: `{"type":"uiSnapshot","maxNodes":120}`
  - find nodes: `{"type":"findNodes","text":"Sign in","limit":10}`
  - tap: `{"type":"tap","x":540,"y":1200,"durationMs":120}`
  - swipe: `{"type":"swipe","x1":540,"y1":1800,"x2":540,"y2":600,"durationMs":350}`
  - hardware button: `{"type":"hardwareButton","name":"back"}` (`menu`, `media_play_pause`, `media_next`, `media_previous`, `media_stop`)

## MCP JSON-RPC tools

For MCP clients (LM Studio, etc.) using `POST /` or `POST /mcp`, available tools include:

- `health`
- `list_apps`
- `overlay_show`
- `overlay_hide`
- `launch`
- `global_action`
- `click_text`
- `click_view_id`
- `set_text`
- `scroll`
- `ui_snapshot`
- `find_nodes`
- `tap`
- `swipe`
- `hardware_button`

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

