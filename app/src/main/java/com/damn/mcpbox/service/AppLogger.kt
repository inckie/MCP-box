package com.damn.mcpbox.service

import android.util.Log

object AppLogger {
    private const val PREFIX = "MCPbox"

    fun d(tag: String, message: String) {
        Log.d("$PREFIX/$tag", message)
    }

    fun i(tag: String, message: String) {
        Log.i("$PREFIX/$tag", message)
    }

    fun w(tag: String, message: String) {
        Log.w("$PREFIX/$tag", message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable == null) {
            Log.e("$PREFIX/$tag", message)
        } else {
            Log.e("$PREFIX/$tag", message, throwable)
        }
    }
}

