package com.damn.mcpbox.service

import android.app.ActivityOptions
import android.app.ActivityManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.damn.mcpbox.R

class FloatingVirtualDisplayService : Service() {
    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var surfaceView: SurfaceView? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var pendingPackageName: String? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        ForegroundNotificationFactory.ensureChannel(this)
        startForeground(
            NOTIFICATION_ID,
            ForegroundNotificationFactory.build(this, getString(R.string.foreground_notification_text))
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(
            TAG,
            "onStartCommand action=${intent?.action} flags=$flags startId=$startId pendingPackage=$pendingPackageName"
        )
        when (intent?.action) {
            ServiceActions.ACTION_SHOW_OVERLAY -> showOverlay()
            ServiceActions.ACTION_HIDE_OVERLAY -> stopSelf()
            ServiceActions.ACTION_LAUNCH_PACKAGE -> {
                val packageName = intent.getStringExtra(ServiceActions.EXTRA_PACKAGE_NAME)
                Log.d(TAG, "Launch requested package=$packageName")
                if (!packageName.isNullOrBlank()) {
                    launchPackageOnVirtualDisplay(packageName)
                }
            }
            else -> showOverlay()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroyed")
        super.onDestroy()
        releaseVirtualDisplay()
        removeOverlay()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showOverlay() {
        if (overlayView != null) {
            Log.d(TAG, "showOverlay skipped: overlay already visible")
            return
        }
        Log.d(TAG, "Creating overlay view")

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(0xCC1E1E1E.toInt())
        }

        val title = TextView(this).apply {
            text = getString(R.string.virtual_screen_title)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
        }
        container.addView(title)

        surfaceView = SurfaceView(this).also { sv ->
            sv.layoutParams = LinearLayout.LayoutParams(720, 460)
            sv.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    Log.d(TAG, "Surface created isValid=${holder.surface.isValid}")
                    createVirtualDisplay(holder.surface)
                    val pending = pendingPackageName
                    pendingPackageName = null
                    Log.d(TAG, "Surface created pendingPackage=$pending")
                    pending?.let { launchPackageOnVirtualDisplay(it) }
                }

                override fun surfaceChanged(
                    holder: SurfaceHolder,
                    format: Int,
                    width: Int,
                    height: Int
                ) {
                    Log.d(TAG, "Surface changed format=$format size=${width}x$height")
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    Log.d(TAG, "Surface destroyed")
                    releaseVirtualDisplay()
                }
            })
            container.addView(sv)
        }

        val closeButton = Button(this).apply {
            text = getString(R.string.close)
            setOnClickListener { stopSelf() }
        }
        container.addView(closeButton)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 60
            y = 120
        }

        makeDraggable(container, params)
        windowManager.addView(container, params)
        overlayView = container
        Log.d(TAG, "Overlay added at x=${params.x}, y=${params.y}")
    }

    private fun createVirtualDisplay(surface: Surface) {
        releaseVirtualDisplay()

        val displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        val displayMetrics: DisplayMetrics = resources.displayMetrics
        val virtualDisplayFlags =
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION

        virtualDisplay = try {
            Log.d(
                TAG,
                "Creating virtual display width=1920 height=1280 density=${displayMetrics.densityDpi} flags=$virtualDisplayFlags surfaceValid=${surface.isValid}"
            )
            displayManager.createVirtualDisplay(
                "McpBoxVirtualDisplay",
                1920,
                1280,
                displayMetrics.densityDpi,
                surface,
                virtualDisplayFlags
            ).also {
                Log.i(TAG, "Virtual display created displayId=${it.display?.displayId}")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Virtual display creation failed due to security policy", e)
            Toast.makeText(this, "Virtual display creation blocked by system policy", Toast.LENGTH_LONG).show()
            null
        }
    }

    private fun launchPackageOnVirtualDisplay(packageName: String) {
        val display = virtualDisplay?.display
        Log.d(
            TAG,
            "launchPackageOnVirtualDisplay package=$packageName display=${display?.displayId} virtualDisplayPresent=${virtualDisplay != null}"
        )
        if (display == null) {
            pendingPackageName = packageName
            Log.w(TAG, "Display not ready; queued package=$packageName")
            Toast.makeText(this, "Virtual screen is not ready yet", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            Log.w(TAG, "No launch intent for package=$packageName")
            Toast.makeText(this, "App is not launchable", Toast.LENGTH_SHORT).show()
            return
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!canLaunchOnDisplay(display.displayId, intent)) {
                Log.w(
                    TAG,
                    "Platform policy denied launch package=$packageName on displayId=${display.displayId}; fallback to main display"
                )
                launchOnMainDisplay(intent)
                return
            }

            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
                val options = ActivityOptions.makeBasic().setLaunchDisplayId(display.displayId)
                Log.d(TAG, "Starting package=$packageName on displayId=${display.displayId}")
                startActivity(intent, options.toBundle())
                Log.i(TAG, "Started package=$packageName on virtual display")
            } catch (e: SecurityException) {
                Log.e(
                    TAG,
                    "SecurityException launching on displayId=${display.displayId} package=$packageName. Fallback to main display.",
                    e
                )
                launchOnMainDisplay(intent)
            } catch (e: IllegalArgumentException) {
                Log.e(
                    TAG,
                    "IllegalArgumentException launching on displayId=${display.displayId} package=$packageName. Fallback to main display.",
                    e
                )
                launchOnMainDisplay(intent)
            }
        } else {
            Log.d(TAG, "SDK < O; starting package=$packageName on default display")
            launchOnMainDisplay(intent)
        }
    }

    private fun canLaunchOnDisplay(displayId: Int, intent: Intent): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Pre-Q lacks a public policy pre-check; rely on try/catch path.
            return true
        }

        val activityManager = getSystemService(ActivityManager::class.java)
        val allowed = activityManager.isActivityStartAllowedOnDisplay(this, displayId, intent)
        Log.d(TAG, "isActivityStartAllowedOnDisplay displayId=$displayId allowed=$allowed")
        return allowed
    }

    private fun launchOnMainDisplay(intent: Intent) {
        // Avoid carrying display-adjacent behavior when falling back to the default screen.
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        Toast.makeText(this, "Cannot launch on virtual display, opening on main screen", Toast.LENGTH_LONG).show()
        startActivity(intent)
    }

    private fun releaseVirtualDisplay() {
        Log.d(TAG, "Releasing virtual display id=${virtualDisplay?.display?.displayId}")
        virtualDisplay?.release()
        virtualDisplay = null
    }

    private fun removeOverlay() {
        overlayView?.let {
            windowManager.removeView(it)
            Log.d(TAG, "Overlay removed")
        }
        overlayView = null
        surfaceView = null
    }

    private fun makeDraggable(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(view, params)
                    true
                }

                MotionEvent.ACTION_UP -> {
                    view.performClick()
                    true
                }

                else -> false
            }
        }
    }

    companion object {
        private const val TAG = "FloatVirtualDisplay"
        private const val NOTIFICATION_ID = 101
    }
}

