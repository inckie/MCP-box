---
name: build-and-deploy
description: Build MCP-box debug APK, run unit tests, and deploy/launch via ADB on connected Android devices (Windows PowerShell).
---

# Build & Deploy MCP-box

## Prerequisites (Windows)
- `JAVA_HOME` is typically not set globally; use Android Studio's bundled JBR:
  `C:\Program Files\Android\Android Studio\jbr`
- `adb.exe` is at:
  `$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe`

## Build & Test
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```
Output APK: `app\build\outputs\apk\debug\app-debug.apk`

## Deploy to a Device
1. List connected devices:
   ```powershell
   $adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
   & $adb devices -l
   ```
2. Install and launch `MainActivity` (note: Windows PowerShell 5.1 does not support `&&`, use `if ($LASTEXITCODE -eq 0)`):
   ```powershell
   & $adb -s <SERIAL> install -r "app\build\outputs\apk\debug\app-debug.apk"
   if ($LASTEXITCODE -eq 0) {
     & $adb -s <SERIAL> shell am start -n "com.damn.mcpbox/.MainActivity"
   }
   ```
3. If `INSTALL_FAILED_UPDATE_INCOMPATIBLE` occurs (different signing key on device), confirm with the user before uninstalling (`uninstall` clears app preferences and disables the Accessibility Service):
   ```powershell
   & $adb -s <SERIAL> uninstall com.damn.mcpbox
   & $adb -s <SERIAL> install "app\build\outputs\apk\debug\app-debug.apk"
   ```

## Useful Logcat Tags
```powershell
& $adb -s <SERIAL> logcat -d -s McpServerService:V FloatVirtualDisplay:V ShizukuProvider:V ShizukuHelper:V ShellUserService:V
```
