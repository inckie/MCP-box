package com.damn.mcpbox.shizuku

import android.util.Log
import kotlin.system.exitProcess

class ShellUserService : IShellUserService.Stub() {
    override fun destroy() {
        Log.i(TAG, "Destroying ShellUserService")
        exitProcess(0)
    }

    override fun launchActivityOnDisplay(componentName: String, displayId: Int): Int {
        return execCommand(listOf("am", "start", "--display", displayId.toString(), "-n", componentName))
    }

    override fun runInputCommand(args: Array<out String>?): Int {
        if (args.isNullOrEmpty()) return -1
        return execCommand(listOf("input") + args.toList())
    }

    private fun execCommand(command: List<String>): Int {
        return try {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            Log.i(TAG, "${command.joinToString(" ")} exit=$exitCode output=$output")
            exitCode
        } catch (e: Exception) {
            Log.e(TAG, "Failed to run ${command.joinToString(" ")}", e)
            -1
        }
    }

    companion object {
        private const val TAG = "ShellUserService"
    }
}
