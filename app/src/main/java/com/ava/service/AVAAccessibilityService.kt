package com.ava.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
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
import com.ava.util.AppLogger
import com.ava.voice.SpeechInput
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

    companion object {
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
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        hideBanner()
        agentLoop?.cancel()
        geminiClient?.close()
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
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
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
                    AVABanner(
                        task = taskText,
                        status = statusText,
                        showUserPrompt = showUserPrompt,
                        userPromptText = userPromptText,
                        onStop = { stopTask() },
                        onUserInput = { input -> provideUserInput(input) },
                        onMicClick = { triggerSpeechInput() }
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
                        state.isDone -> "✅ Done"
                        state.needsUser -> "❓ ${state.userMessage}"
                        state.steps.isNotEmpty() -> state.steps.last()
                        else -> "Thinking..."
                    }
                    showUserPrompt = state.needsUser
                    userPromptText = state.userMessage
                    
                    updateBannerFocus(state.needsUser)
                }
            }
        }
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    fun startTask(task: String): Boolean {
        // Dynamic initialization to resolve setup-order bug
        if (agentLoop == null) {
            val prefs = getSharedPreferences("ava_config", MODE_PRIVATE)
            val apiKey = prefs.getString("gemini_api_key", "") ?: ""
            if (apiKey.isNotBlank()) {
                geminiClient = GeminiClient(apiKey)
                agentLoop = AgentLoop(this, geminiClient!!)
                AppLogger.i(TAG, "Agent loop dynamically initialized on startTask")
            }
        }

        val loop = agentLoop ?: run {
            AppLogger.e(TAG, "Cannot start task — agent loop not initialized (missing API key?)")
            return false
        }

        showBanner(task)
        observeAgentState(loop.state)

        // Start the agent
        loop.start(task)

        return true
    }

    fun getAgentState(): StateFlow<AgentState>? {
        return agentLoop?.state
    }

    fun stopTask() {
        agentLoop?.stop()
        stateObserverJob?.cancel()
        hideBanner()
    }

    fun provideUserInput(input: String) {
        agentLoop?.provideUserInput(input)
        updateBannerFocus(false)
    }

    fun isAgentRunning(): Boolean = agentLoop?.state?.value?.isRunning == true

    private fun triggerSpeechInput() {
        serviceScope.launch {
            // Unfocus the banner so speech recognizer has absolute focus
            updateBannerFocus(false)
            val oldStatus = statusText
            statusText = "🎙️ Listening..."
            val task = speechInput.listenOnce()
            if (!task.isNullOrBlank()) {
                AppLogger.i(TAG, "Speech recognized from banner overlay: \"$task\"")
                startTask(task)
            } else {
                statusText = "Cancelled"
                delay(1500)
                val currentAgentState = agentLoop?.state?.value
                if (currentAgentState != null) {
                    statusText = when {
                        currentAgentState.isDone -> "✅ Done"
                        currentAgentState.needsUser -> "❓ ${currentAgentState.userMessage}"
                        currentAgentState.steps.isNotEmpty() -> currentAgentState.steps.last()
                        else -> oldStatus
                    }
                } else {
                    statusText = "Ready"
                }
            }
        }
    }
}
