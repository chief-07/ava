package com.ava.service

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import com.ava.ui.MainActivity
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.*
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.ava.agent.AgentLoop
import com.ava.agent.GeminiClient
import com.ava.agent.AgentState
import com.ava.overlay.AVABanner
import com.ava.overlay.AVAAvatarButton
import com.ava.util.AppLogger
import com.ava.voice.SpeechInput
import com.ava.voice.WakeWordListener
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow

private const val TAG = "AVA:AccessibilityService"

/**
 * AVAAccessibilityService is the core Android service that gives AVA
 * the ability to:
 *  - Read the current screen's UI tree (via getRootInActiveWindow)
 *  - Execute actions (tap, scroll, type, swipe) via performAction / dispatchGesture
 *  - React to screen changes via onAccessibilityEvent
 *
 * This service must be enabled by the user in:
 * Settings → Accessibility → Installed services → AVA
 *
 * It is the single source of truth for device interaction — all other
 * components (AgentLoop, ActionExecutor) receive a reference to this service.
 */
class AVAAccessibilityService : AccessibilityService(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private var agentLoop: AgentLoop? = null
    private var geminiClient: GeminiClient? = null
    private lateinit var speechInput: SpeechInput
    private var wakeWordListener: WakeWordListener? = null

    // ─── Lifecycle & SavedState boilerplate ──────────────────────────────────
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    // ─── Banner drawing state ───────────────────────────────────────────────
    private var windowManager: WindowManager? = null
    private var bannerContainer: FrameLayout? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var statusText by mutableStateOf("Ready")
    private var taskText by mutableStateOf("")
    private var showUserPrompt by mutableStateOf(false)
    private var userPromptText by mutableStateOf("")

    private var isRunningState by mutableStateOf(false)
    private var isDoneState by mutableStateOf(false)
    private var needsUserState by mutableStateOf(false)
    private var isErrorState by mutableStateOf(false)
    private var isListeningState by mutableStateOf(false)
    private var liveTranscription by mutableStateOf("")
    private var isUserExpandedState by mutableStateOf(false)

    companion object {
        const val ACTION_SHOW_BANNER = "com.ava.action.SHOW_BANNER"
        const val ACTION_TOGGLE_OVERLAY = "com.ava.action.TOGGLE_OVERLAY"
        const val ACTION_REFRESH_NOTIFICATION = "com.ava.action.REFRESH_NOTIFICATION"
        private const val CHANNEL_ID = "ava_persistent_channel"
        private const val NOTIFICATION_ID = 888

        // Singleton reference so other components can reach the service
        var instance: AVAAccessibilityService? = null
            private set
    }

    // ─── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        speechInput = SpeechInput(this)
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        AppLogger.i(TAG, "AVA Accessibility Service connected ✅")

        // Load API key from secure storage (set via MainActivity)
        val prefs = getSharedPreferences("ava_config", MODE_PRIVATE)
        val apiKey = prefs.getString("gemini_api_key", "") ?: ""

        if (apiKey.isNotBlank()) {
            geminiClient = GeminiClient(apiKey)
            agentLoop = AgentLoop(this, geminiClient!!)
            AppLogger.i(TAG, "Agent loop initialized with Gemini client")
        } else {
            AppLogger.w(TAG, "No API key configured — agent loop not started yet")
        }
        updatePersistentNotification()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        hideBanner()
        agentLoop?.cancel()
        geminiClient?.close()
        try {
            wakeWordListener?.destroy()
            wakeWordListener = null
        } catch (e: Exception) {
            // Ignore
        }
        serviceScope.cancel()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        AppLogger.i(TAG, "AVA Accessibility Service destroyed")
    }

    // ─── Accessibility events ──────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Ignore events from AVA itself to avoid log noise
        if (event.packageName?.toString() == packageName) return

        // Log to logcat only (not AppLogger) to avoid flooding the in-app console
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                Log.v(TAG, "Window changed: ${event.packageName} / ${event.className}")
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                Log.v(TAG, "Content changed in: ${event.packageName}")
        }
    }

    override fun onInterrupt() {
        AppLogger.w(TAG, "Accessibility service interrupted")
        agentLoop?.stop()
    }

    // ─── Banner management ────────────────────────────────────────────────────

    private fun showBanner(task: String) {
        if (bannerContainer != null) return // already showing

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        taskText = task
        statusText = "Starting..."
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val prefs = getSharedPreferences("ava_config", MODE_PRIVATE)
            x = prefs.getInt("avatar_x", 24)
            y = prefs.getInt("avatar_y", 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                blurBehindRadius = 45
            }
        }
        windowParams = params

        bannerContainer = FrameLayout(this).also { container ->
            container.setViewTreeLifecycleOwner(this@AVAAccessibilityService)
            container.setViewTreeSavedStateRegistryOwner(this@AVAAccessibilityService)
            container.setViewTreeViewModelStoreOwner(this@AVAAccessibilityService)

            val composeView = ComposeView(this).apply {
                setViewTreeLifecycleOwner(this@AVAAccessibilityService)
                setViewTreeSavedStateRegistryOwner(this@AVAAccessibilityService)
                setViewTreeViewModelStoreOwner(this@AVAAccessibilityService)
                setContent {
                    AVAAvatarButton(
                        isRunning = isRunningState,
                        isDone = isDoneState,
                        needsUser = needsUserState,
                        isError = isErrorState,
                        isListening = isListeningState,
                        liveTranscription = liveTranscription,
                        statusText = statusText,
                        isUserExpanded = isUserExpandedState,
                        onToggleExpand = { expand ->
                            isUserExpandedState = expand
                        },
                        onStopTask = { cancelActiveTask() },
                        onDrag = { dx, dy -> updateWindowPosition(dx, dy) },
                        onDragEnd = { saveWindowPosition() },
                        onClickText = { triggerSpeechInput() }
                    )
                }
            }
            container.addView(composeView)
        }

        try {
            windowManager?.addView(bannerContainer, params)
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
            AppLogger.i(TAG, "Banner shown directly by Accessibility Service ✅")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to show banner: ${e.message}")
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
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        if (instance != null) {
            wakeWordListener?.start()
        }
    }

    private fun updateWindowPosition(dx: Float, dy: Float) {
        val params = windowParams ?: return
        val container = bannerContainer ?: return
        params.x += dx.toInt()
        params.y += dy.toInt()
        try {
            windowManager?.updateViewLayout(container, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update layout position: ${e.message}")
        }
    }

    private fun saveWindowPosition() {
        val params = windowParams ?: return
        val prefs = getSharedPreferences("ava_config", MODE_PRIVATE)
        prefs.edit().apply {
            putInt("avatar_x", params.x)
            putInt("avatar_y", params.y)
            apply()
        }
        AppLogger.d(TAG, "Saved avatar position: x=${params.x}, y=${params.y}")
    }

    private var stateObserverJob: Job? = null

    private fun updateBannerFocus(focusable: Boolean) {
        val container = bannerContainer ?: return
        val params = windowParams ?: return
        val currentFlags = params.flags
        val newFlags = if (focusable) {
            currentFlags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            currentFlags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (newFlags != currentFlags) {
            params.flags = newFlags
            try {
                windowManager?.updateViewLayout(container, params)
                Log.d(TAG, "Banner window focusable updated: $focusable")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update banner layout flags: ${e.message}")
            }
        }
    }

    private fun observeAgentState(stateFlow: StateFlow<AgentState>) {
        stateObserverJob?.cancel()
        stateObserverJob = serviceScope.launch {
            stateFlow.collect { state ->
                withContext(Dispatchers.Main) {
                    taskText = state.task
                    statusText = when {
                        state.isDone -> "Done"
                        state.needsUser -> state.userMessage
                        state.steps.isNotEmpty() -> state.steps.last()
                        else -> "Thinking..."
                    }
                    showUserPrompt = state.needsUser
                    userPromptText = state.userMessage
                    
                    isRunningState = state.isRunning
                    isDoneState = state.isDone
                    needsUserState = state.needsUser
                    isErrorState = state.isError

                    if (state.isDone || state.isError || state.needsUser) {
                        isUserExpandedState = true
                    }
                    
                    updateBannerFocus(state.needsUser)
                }
            }
        }
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    fun showIdleBanner() {
        taskText = ""
        statusText = "Ready — tap to speak"
        isRunningState = false
        isDoneState = false
        needsUserState = false
        isErrorState = false
        isUserExpandedState = false
        showBanner("")
    }

    fun startTask(task: String): Boolean {
        val prefs = getSharedPreferences("ava_config", MODE_PRIVATE)
        val apiKey = prefs.getString("gemini_api_key", "") ?: ""

        // Dynamically initialize or refresh the agent loop if the API key has changed
        if (geminiClient == null || geminiClient?.apiKey != apiKey) {
            if (apiKey.isNotBlank()) {
                geminiClient = GeminiClient(apiKey)
                agentLoop = AgentLoop(this, geminiClient!!)
                AppLogger.i(TAG, "Agent loop initialized/updated with new API key")
            }
        }

        val loop = agentLoop ?: run {
            AppLogger.e(TAG, "Cannot start task — agent loop not initialized (missing API key?)")
            return false
        }

        isRunningState = true
        isDoneState = false
        needsUserState = false
        isErrorState = false
        isUserExpandedState = true

        showBanner(task)
        observeAgentState(loop.state)

        // Start the agent
        loop.start(task)

        return true
    }

    fun getAgentState(): StateFlow<AgentState>? {
        return agentLoop?.state
    }

    fun cancelActiveTask() {
        agentLoop?.stop()
        isRunningState = false
        isDoneState = false
        needsUserState = false
        isErrorState = false
        statusText = "Cancelled"
    }

    private fun stopTask() {
        agentLoop?.stop()
        hideBanner()
    }

    private fun provideUserInput(input: String) {
        agentLoop?.provideUserInput(input)
    }

    private fun toggleOverlay() {
        if (bannerContainer != null) {
            hideBanner()
            wakeWordListener?.stop()
            AppLogger.i(TAG, "Overlay hidden via toggle")
        } else {
            showIdleBanner()
            initWakeWordListener()
            AppLogger.i(TAG, "Overlay shown via toggle")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            when (intent.action) {
                ACTION_SHOW_BANNER -> {
                    AppLogger.i(TAG, "Show banner action triggered from notification")
                    showIdleBanner()
                }
                ACTION_TOGGLE_OVERLAY -> {
                    AppLogger.i(TAG, "Toggle overlay action triggered from notification")
                    toggleOverlay()
                }
                ACTION_REFRESH_NOTIFICATION -> {
                    updatePersistentNotification()
                    initWakeWordListener()
                }
            }
        }
        return START_STICKY
    }

    private fun updatePersistentNotification() {
        val prefs = getSharedPreferences("ava_config", MODE_PRIVATE)
        val showNotification = prefs.getBoolean("show_persistent_notification", false)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (!showNotification) {
            notificationManager.cancel(NOTIFICATION_ID)
            return
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AVA Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps AVA launcher active in your notification drawer"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(this, AVAAccessibilityService::class.java).apply {
            action = ACTION_TOGGLE_OVERLAY
        }
        val piFlags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getService(this, 0, intent, piFlags)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AVA is active")
            .setContentText("Tap to open voice search button")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
        Log.d(TAG, "Persistent notification posted successfully")
    }

    fun isAgentRunning(): Boolean = agentLoop?.state?.value?.isRunning == true

    private fun initWakeWordListener() {
        if (wakeWordListener != null) {
            wakeWordListener?.stop()
            wakeWordListener = null
        }
        wakeWordListener = WakeWordListener(this) { command ->
            serviceScope.launch(Dispatchers.Main) {
                if (command != null) {
                    AppLogger.i(TAG, "Wake word triggered with command: $command")
                    cancelActiveTask()
                    startTask(command)
                } else {
                    AppLogger.i(TAG, "Wake word triggered (idle wake)")
                    cancelActiveTask()
                    showIdleBanner()
                    triggerSpeechInput()
                }
            }
        }
        wakeWordListener?.start()
        AppLogger.i(TAG, "Wake word listener initialized successfully")
    }

    private fun triggerSpeechInput() {
        serviceScope.launch {
            wakeWordListener?.stop()
            // Unfocus the banner so speech recognizer has absolute focus
            updateBannerFocus(false)
            val oldStatus = statusText
            isListeningState = true
            liveTranscription = ""
            statusText = "Listening..."
            val task = speechInput.listenOnce { partialText ->
                liveTranscription = partialText
            }
            isListeningState = false
            if (!task.isNullOrBlank()) {
                statusText = "Thinking..."
                AppLogger.i(TAG, "Speech recognized from banner overlay: \"$task\"")
                
                val currentAgentState = agentLoop?.state?.value
                if (currentAgentState != null && (currentAgentState.needsUser || currentAgentState.isError)) {
                    provideUserInput(task)
                } else {
                    startTask(task)
                }
            } else {
                statusText = "Cancelled"
                delay(1500)
                val currentAgentState = agentLoop?.state?.value
                if (currentAgentState != null) {
                    statusText = when {
                        currentAgentState.isDone -> "Done"
                        currentAgentState.needsUser -> currentAgentState.userMessage
                        currentAgentState.steps.isNotEmpty() -> currentAgentState.steps.last()
                        else -> oldStatus
                    }
                } else {
                    statusText = "Ready"
                }
            }
            wakeWordListener?.start()
        }
    }
}
