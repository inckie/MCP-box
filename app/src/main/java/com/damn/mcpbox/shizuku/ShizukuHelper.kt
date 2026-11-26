package com.damn.mcpbox.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

enum class ShizukuStatus {
    NOT_RUNNING,
    PERMISSION_REQUIRED,
    READY
}

object ShizukuHelper {
    private const val TAG = "ShizukuHelper"
    private const val PERMISSION_REQUEST_CODE = 1001

    private val mainHandler = Handler(Looper.getMainLooper())
    private val _status = MutableStateFlow(ShizukuStatus.NOT_RUNNING)
    val status: StateFlow<ShizukuStatus> = _status.asStateFlow()

    @Volatile
    private var initialized = false

    @Volatile
    private var shellService: IShellUserService? = null

    private val pendingCallbacks = mutableListOf<(IShellUserService?) -> Unit>()
    private var bindingInProgress = false

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder received")
        refreshStatus()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder dead")
        synchronized(this) {
            shellService = null
            bindingInProgress = false
            val callbacks = pendingCallbacks.toList()
            pendingCallbacks.clear()
            callbacks.forEach { it(null) }
        }
        _status.value = ShizukuStatus.NOT_RUNNING
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == PERMISSION_REQUEST_CODE) {
                Log.i(TAG, "Shizuku permission result=$grantResult")
                refreshStatus()
            }
        }

    fun init() {
        if (initialized) {
            refreshStatus()
            return
        }
        synchronized(this) {
            if (initialized) {
                refreshStatus()
                return
            }
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
            initialized = true
        }
        refreshStatus()
    }

    fun refreshStatus(): ShizukuStatus {
        val next = try {
            if (!Shizuku.pingBinder()) {
                ShizukuStatus.NOT_RUNNING
            } else if (Shizuku.isPreV11()) {
                ShizukuStatus.NOT_RUNNING
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                ShizukuStatus.READY
            } else {
                ShizukuStatus.PERMISSION_REQUIRED
            }
        } catch (e: Exception) {
            Log.d(TAG, "Shizuku status check failed: ${e.message}")
            ShizukuStatus.NOT_RUNNING
        }
        _status.value = next
        return next
    }

    fun isReady(): Boolean = refreshStatus() == ShizukuStatus.READY

    fun requestPermission() {
        try {
            if (Shizuku.pingBinder() && !Shizuku.isPreV11()) {
                Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request Shizuku permission", e)
        }
    }

    fun launchOnDisplay(
        context: Context,
        componentName: String,
        displayId: Int,
        onResult: (Boolean) -> Unit
    ) {
        if (!isReady()) {
            onResult(false)
            return
        }

        withShellService(context) { service ->
            if (service == null) {
                mainHandler.post { onResult(false) }
                return@withShellService
            }
            thread(name = "shizuku-launch") {
                val ok = try {
                    service.launchActivityOnDisplay(componentName, displayId) == 0
                } catch (e: Exception) {
                    Log.e(TAG, "Shizuku launchActivityOnDisplay failed", e)
                    synchronized(this@ShizukuHelper) {
                        shellService = null
                    }
                    false
                }
                mainHandler.post { onResult(ok) }
            }
        }
    }

    fun runInputSync(context: Context, vararg args: String): Boolean {
        if (!isReady()) return false
        val latch = CountDownLatch(1)
        var result = false
        withShellService(context) { service ->
            if (service == null) {
                latch.countDown()
                return@withShellService
            }
            thread(name = "shizuku-input") {
                result = try {
                    service.runInputCommand(args) == 0
                } catch (e: Exception) {
                    Log.e(TAG, "Shizuku runInputCommand failed", e)
                    synchronized(this@ShizukuHelper) {
                        shellService = null
                    }
                    false
                } finally {
                    latch.countDown()
                }
            }
        }
        latch.await(5, TimeUnit.SECONDS)
        return result
    }

    private fun withShellService(context: Context, callback: (IShellUserService?) -> Unit) {
        val existing = shellService
        if (existing != null && existing.asBinder().pingBinder()) {
            callback(existing)
            return
        }

        val shouldBind: Boolean
        synchronized(this) {
            val current = shellService
            if (current != null && current.asBinder().pingBinder()) {
                callback(current)
                return
            }
            shellService = null
            pendingCallbacks.add(callback)
            shouldBind = !bindingInProgress
            bindingInProgress = true
        }

        if (!shouldBind) return

        val args = Shizuku.UserServiceArgs(
            ComponentName(context.packageName, ShellUserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("shizuku_shell")
            .debuggable(true)
            .version(2)

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val service = binder?.let { IShellUserService.Stub.asInterface(it) }
                Log.i(TAG, "Connected to ShellUserService: ${service != null}")
                val callbacks: List<(IShellUserService?) -> Unit>
                synchronized(this@ShizukuHelper) {
                    shellService = service
                    bindingInProgress = false
                    callbacks = pendingCallbacks.toList()
                    pendingCallbacks.clear()
                }
                callbacks.forEach { it(service) }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                Log.w(TAG, "Disconnected from ShellUserService")
                synchronized(this@ShizukuHelper) {
                    shellService = null
                    bindingInProgress = false
                }
            }
        }

        try {
            Shizuku.bindUserService(args, connection)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind ShellUserService", e)
            val callbacks: List<(IShellUserService?) -> Unit>
            synchronized(this) {
                bindingInProgress = false
                callbacks = pendingCallbacks.toList()
                pendingCallbacks.clear()
            }
            callbacks.forEach { it(null) }
        }
    }
}
