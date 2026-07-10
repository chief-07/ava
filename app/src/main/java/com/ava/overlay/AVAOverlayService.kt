package com.ava.overlay

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.*
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.ava.agent.AgentState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow

private const val TAG = "AVA:OverlayService"
private const val CHANNEL_ID = "ava_overlay"
private const val NOTIF_ID = 1001

/**
 * AVAOverlayService draws AVA's top banner over all apps.
 *
 * It uses Android's WindowManager to attach a floating ComposeView
 * to the screen. The banner shows:
 *   - Current task
 *   - Live step / reasoning text
 *   - Stop button
 *   - User input prompt (when agent needs clarification)
 *
 * The service runs as a Foreground Service so Android doesn't kill it.
 */
class AVAOverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    companion object {
        var instance: AVAOverlayService? = null
            private set

        const val ACTION_START = "com.ava.overlay.START"
        const val ACTION_STOP  = "com.ava.overlay.STOP"
        const val EXTRA_TASK   = "task"
    }

    // ─── Lifecycle boilerplate for Compose in a Service ───────────────────────

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    // ─── State ────────────────────────────────────────────────────────────────

    private var windowManager: WindowManager? = null
    private var bannerContainer: FrameLayout? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Mutable state observed by the Compose banner
    private var statusText by mutableStateOf("Ready")
    private var taskText by mutableStateOf("")
    private var showUserPrompt by mutableStateOf(false)
    private var userPromptText by mutableStateOf("")

    // ─── Service lifecycle ────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        createNotificationChannel()
        Log.i(TAG, "AVA Overlay Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED

        when (intent?.action) {
            ACTION_START -> {
                val task = intent.getStringExtra(EXTRA_TASK) ?: ""
                taskText = task
                statusText = "Starting..."
                showBanner()

                // Connect to the accessibility service's agent state updates
                com.ava.service.AVAAccessibilityService.instance?.getAgentState()?.let { stateFlow ->
                    observeAgentState(stateFlow)
                }
            }
            ACTION_STOP -> {
                hideBanner()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        hideBanner()
        serviceScope.cancel()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        Log.i(TAG, "AVA Overlay Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── Banner management ────────────────────────────────────────────────────

    private fun showBanner() {
        if (bannerContainer != null) return // already showing

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        bannerContainer = FrameLayout(this).also { container ->
            val composeView = ComposeView(this).apply {
                setViewTreeLifecycleOwner(this@AVAOverlayService)
                setViewTreeSavedStateRegistryOwner(this@AVAOverlayService)
                setContent {
                    AVABanner(
                        task = taskText,
                        status = statusText,
                        showUserPrompt = showUserPrompt,
                        userPromptText = userPromptText,
                        onStop = { stopFromOverlay() },
                        onUserInput = { input -> handleUserInput(input) }
                    )
                }
            }
            container.addView(composeView)
        }

        try {
            windowManager?.addView(bannerContainer, params)
            startForeground(NOTIF_ID, buildNotification())
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
            com.ava.util.AppLogger.i(TAG, "Banner shown ✅")
        } catch (e: Exception) {
            com.ava.util.AppLogger.e(TAG, "Failed to show banner: ${e.message}")
            Log.e(TAG, "Failed to show banner", e)
        }
    }

    private fun hideBanner() {
        bannerContainer?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing banner: ${e.message}")
            }
        }
        bannerContainer = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    // ─── Agent state observer ─────────────────────────────────────────────────

    fun observeAgentState(stateFlow: StateFlow<AgentState>) {
        serviceScope.launch {
            stateFlow.collect { state ->
                withContext(Dispatchers.Main) {
                    taskText = state.task
                    statusText = when {
                        state.isDone -> "✅ Done"
                        state.needsUser -> "❓ ${state.userMessage}"
                        state.steps.isNotEmpty() -> state.steps.last()
                        else -> "Thinking..."
                    }
                    showUserPrompt = state.needsUser
                    userPromptText = state.userMessage
                }
            }
        }
    }

    // ─── User interaction ─────────────────────────────────────────────────────

    private fun stopFromOverlay() {
        com.ava.service.AVAAccessibilityService.instance?.stopTask()
        hideBanner()
        stopSelf()
    }

    private fun handleUserInput(input: String) {
        showUserPrompt = false
        com.ava.service.AVAAccessibilityService.instance?.provideUserInput(input)
    }

    // ─── Notification (required for foreground service) ────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "AVA Agent",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows while AVA is running a task"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AVA is running")
            .setContentText(taskText.take(60))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }
}
