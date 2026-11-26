package com.damn.mcpbox.shizuku

import android.util.Log
import kotlin.system.exitProcess

class ShellUserService : IShellUserService.Stub() {
    override fun destroy() {
        Log.i(TAG, "Destroying ShellUserService")
        exitProcess(0)
    }

    override fun launchActivityOnDisplay(componentName: String, displayId: Int): Int {
        return try {
            val process = ProcessBuilder(
                "am", "start",
                "--display", displayId.toString(),
                "-n", componentName
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            Log.i(TAG, "am start --display $displayId -n $componentName exit=$exitCode output=$output")
            exitCode
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch $componentName on display $displayId", e)
            -1
        }
    }

    companion object {
        private const val TAG = "ShellUserService"
    }
}
