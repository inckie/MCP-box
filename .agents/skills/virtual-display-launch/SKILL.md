---
name: virtual-display-launch
description: Launch Android apps onto MCP-box's floating virtual display (McpBoxVirtualDisplay) using either Shizuku mode (on-device) or ADB mode (from PC), and troubleshoot display/Shizuku issues.
---

# Virtual Display Launch (Shizuku & ADB Modes)

`FloatingVirtualDisplayService` creates a `FLAG_PRIVATE` virtual display named **`McpBoxVirtualDisplay`** configured as a **720×1280 @ 320 dpi** portrait display (`360dp × 640dp`) rendered inside a scaled 9:16 `SurfaceView` (`holder.setFixedSize(720, 1280)`).

Because Android blocks unprivileged cross-UID launches onto a `FLAG_PRIVATE` virtual display, MCP-box supports **two** privileged launch modes:

## Mode 1: Shizuku (On-device)

### How it works
- `ShizukuHelper` (`app/src/main/java/com/damn/mcpbox/shizuku/ShizukuHelper.kt`) monitors Shizuku binder and permission state (`NOT_RUNNING`, `PERMISSION_REQUIRED`, `READY`).
- When ready, `FloatingVirtualDisplayService.launchPackageOnVirtualDisplay()` delegates to `ShizukuHelper.launchOnDisplay(...)`, which binds `ShellUserService` (`IShellUserService.aidl`) running under `shell` (UID 2000) and executes:
  ```bash
  am start --display <displayId> -n <package>/<activity>
  ```
- Requires `<provider android:name="rikka.shizuku.ShizukuProvider" android:authorities="${applicationId}.shizuku" ... />` in `AndroidManifest.xml` so `shizuku_server` can deliver its binder.

### Setup & Verification
1. Verify Shizuku app and server are running on the device:
   ```powershell
   adb -s <SERIAL> shell "ps -A | grep -i shizuku"
   ```
   Expect `shizuku_server` running under `shell`.
2. In MCP-box, enable **Virtual Display** and tap **Grant** on the **Shizuku** row.
3. Tap **Start Floating Screen**, pick an app, and tap **Launch On Virtual Screen**.
4. Check logs if anything fails:
   ```powershell
   adb -s <SERIAL> logcat -d -s ShizukuProvider:V ShizukuHelper:V ShellUserService:V FloatVirtualDisplay:V
   ```

## Mode 2: ADB (From PC)

Use this when Shizuku is not installed/running or when automating from a PC.

1. Ensure the floating screen is started in MCP-box on the device (`FloatingVirtualDisplayService` is `exported="false"`, so start it from the app UI).
2. Find `McpBoxVirtualDisplay`'s current `displayId`:
   ```powershell
   $adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
   & $adb -s <SERIAL> shell "dumpsys display | grep -E 'McpBoxVirtualDisplay.*displayId'"
   ```
3. Resolve the target app's launcher activity:
   ```powershell
   $activity = (& $adb -s <SERIAL> shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER <PACKAGE> | Select-Object -Last 1).Trim()
   ```
4. Force-stop the app (if already running on Display #0) and launch it onto the virtual display:
   ```powershell
   & $adb -s <SERIAL> shell am force-stop <PACKAGE>
   & $adb -s <SERIAL> shell am start --display <DISPLAY_ID> -n $activity
   ```
5. Verify the app is resumed on the virtual display:
   ```powershell
   & $adb -s <SERIAL> shell "dumpsys activity activities | grep -E 'Display #<DISPLAY_ID>|topResumedActivity|<PACKAGE>'"
   ```

## Controlling Apps on the Virtual Screen via MCP

- **Health & dimensions**: Call `accessibility/health` (or `GET /health`) to check `virtualDisplayActive`, `virtualDisplayId`, `shizukuReady`, and `720×1280` dimensions.
- **Screenshots**: Call `accessibility/screenshot` with `{"target": "virtual"}` (returns metadata + native MCP `image` block) or read resource `screenshot://virtual` (`PixelCopy` on the `SurfaceView`).
- **Input & navigation**: Pass `"target": "virtual"` (or explicit `"displayId"`) to:
  - `accessibility/tap` (`x`, `y` in `0..720` × `0..1280`)
  - `accessibility/swipe` (`x1`, `y1`, `x2`, `y2`, `durationMs`)
  - `accessibility/scroll` (`direction`: `down`/`up`/`left`/`right` — falls back to centered swipe on virtual display)
  - `accessibility/input_text` (`text`, optional `pressEnter`) or `accessibility/set_text` (falls back to `input -d <id> text` on virtual display)
  - `accessibility/key_event` (`key`: `back`, `home`, `enter`, `del`, `tab`, `escape`, `dpad_*`, or numeric keycode; optional `longPress`)
  - `accessibility/global_action` (`back`, `home`)
- **Limitation**: Android hides windows on `FLAG_PRIVATE` virtual displays from `AccessibilityService.getWindowsOnAllDisplays()`, so `ui_snapshot`, `find_nodes`, `click_text`, and `click_view_id` only work on Display #0.

## Multi-Display Security Notes
- Activities belonging to the **same UID** as an app already on `McpBoxVirtualDisplay` stay on the virtual display.
- Launching a **different third-party UID** from inside an unprivileged app on a `FLAG_PRIVATE` virtual display is blocked/redirected to Display #0 by Android — use Shizuku or ADB for cross-UID launches.
