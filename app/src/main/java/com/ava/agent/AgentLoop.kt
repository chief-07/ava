package com.ava.agent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.ava.util.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

private const val TAG = "AVA:AgentLoop"
private const val MAX_STEPS = 30       // safety cap — stop after N steps
private const val STUCK_THRESHOLD = 3  // same screen N times in a row = stuck

/**
 * AgentLoop is the brain of AVA.
 *
 * It runs the Read → Think → Act loop:
 *   1. READ  — get current screen context from ScreenReader
 *   2. THINK — ask Gemini what to do next
 *   3. ACT   — execute that action via ActionExecutor
 *   4. REPEAT until DONE, ASK_USER, or MAX_STEPS
 *
 * The loop emits AgentState updates so the overlay can react in real time.
 */
class AgentLoop(
    private val service: AccessibilityService,
    private val gemini: GeminiClient
) {
    private val executor = ActionExecutor(service)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Public state flow — the overlay observes this
    private val _state = MutableStateFlow(AgentState(task = ""))
    val state: StateFlow<AgentState> = _state.asStateFlow()

    private var loopJob: Job? = null
    private var lastLat = 0.0
    private var lastLon = 0.0
    private var lastLocationName = ""

    // ─── Control ───────────────────────────────────────────────────────────────

    fun start(task: String, resetMemory: Boolean = true, latitude: Double = 0.0, longitude: Double = 0.0, locationName: String = "") {
        loopJob?.cancel()
        if (resetMemory) {
            lastLat = latitude
            lastLon = longitude
            lastLocationName = locationName
        }
        val currentSteps = if (resetMemory) mutableListOf() else _state.value.steps.toMutableList()
        _state.value = AgentState(task = task, steps = currentSteps, isRunning = true)
        loopJob = scope.launch {
            try {
                runLoop(task, resetMemory)
            } catch (e: Throwable) {
                AppLogger.e(TAG, "Agent loop crashed: ${e.message}", e)
                _state.update { it.copy(isRunning = false, isError = true) }
            }
        }
        AppLogger.i(TAG, "Started task: \"$task\"")
    }

    fun stop() {
        loopJob?.cancel()
        _state.update { it.copy(isRunning = false) }
        AppLogger.i(TAG, "Task stopped by user")
    }

    fun provideUserInput(input: String) {
        val currentState = _state.value
        if (currentState.needsUser || currentState.isError || currentState.isDone) {
            val currentTask = currentState.task
            val enrichedTask = "$currentTask\nUser feedback: $input"
            AppLogger.i(TAG, "Resuming/correcting task with user feedback: \"$input\"")
            start(enrichedTask, resetMemory = false)
        }
    }

    // ─── Main loop ─────────────────────────────────────────────────────────────

    private suspend fun runLoop(task: String, resetMemory: Boolean) {
        val steps = if (resetMemory) mutableListOf() else _state.value.steps.toMutableList()
        var stepCount = steps.size
        var sameScreenCount = 0
        var lastScreenHash = ""

        val wasInitiallyInSplitScreen = isInMultiWindowMode(service)
        var didOpenNotifications = false

        val context = service.applicationContext
        val prefs = context.getSharedPreferences("ava_config", Context.MODE_PRIVATE)
        val serpApiKey = prefs.getString("serp_api_key", "") ?: ""

        var searchResults = ""
        if (resetMemory && serpApiKey.isNotBlank() && gemini.requiresRealTimeSearch(task)) {
            AppLogger.d(TAG, "Task matches real-time search requirements. Querying SerpAPI at location '$lastLocationName'...")
            val searchClient = SerpApiClient()
            try {
                searchResults = searchClient.searchGoogle(task, serpApiKey, lastLocationName)
                AppLogger.d(TAG, "SerpAPI search results: $searchResults")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error fetching SerpAPI search results: ${e.message}")
            } finally {
                searchClient.close()
            }
        }

        // Reset conversation memory only for new tasks
        if (resetMemory) {
            gemini.resetConversation()
        }

        try {
            while (stepCount < MAX_STEPS && _state.value.isRunning) {
                stepCount++

                // 1. READ — get current screen
                service.clearCache()
                val screen = ScreenReader.buildContext(service)

                // Stuck detection: same screen hash N times
                val screenHash = screen.elements.joinToString { it.text + it.contentDesc }
                if (screenHash == lastScreenHash) {
                    sameScreenCount++
                    if (sameScreenCount >= STUCK_THRESHOLD) {
                        AppLogger.w(TAG, "Stuck detected after $sameScreenCount identical screens")
                        emit(
                            steps,
                            needsUser = true,
                            userMessage = "I seem to be stuck on the same screen. Can you help me proceed, or should I stop?"
                        )
                        return
                    }
                } else {
                    sameScreenCount = 0
                    lastScreenHash = screenHash
                }

                // 2. THINK — ask Gemini
                emit(steps, thinking = true)
                AppLogger.d(TAG, "Step $stepCount: Sending screen context to Gemini...")
                val currentSearchResults = if (stepCount == steps.size + 1) searchResults else ""
                val action = gemini.decideNextAction(task, screen, steps, currentSearchResults)
                AppLogger.d(TAG, "Gemini selected: ${action.action} (Reasoning: ${action.reasoning})")

                // 3. ACT — execute
                val actionType = try {
                    ActionType.valueOf(action.action.uppercase())
                } catch (e: IllegalArgumentException) {
                    AppLogger.e(TAG, "Unknown action type returned by LLM: ${action.action}")
                    ActionType.ASK_USER
                }

                if (actionType == ActionType.NOTIFICATIONS) {
                    didOpenNotifications = true
                }

                when (actionType) {
                    ActionType.DONE -> {
                        val summary = action.message.ifBlank { "Task complete." }
                        steps.add("Done: $summary")
                        emit(steps, isDone = true, userMessage = summary)
                        AppLogger.i(TAG, "Task completed successfully: $summary")
                        return
                    }
                    ActionType.ASK_USER -> {
                        steps.add(action.message)
                        val isErr = action.message.contains("error", ignoreCase = true)
                        emit(steps, needsUser = !isErr, isError = isErr, userMessage = action.message)
                        AppLogger.i(TAG, "Agent needs user response (isError=$isErr): ${action.message}")
                        return
                    }
                    else -> {
                        val stepDesc = executor.execute(action, task)
                        steps.add(stepDesc)
                        emit(steps)
                        AppLogger.d(TAG, "Executed: $stepDesc")
                        // Small delay to let the screen settle after action
                        delay(1200)
                    }
                }
            }

            // Hit step cap
            if (stepCount >= MAX_STEPS) {
                steps.add("Reached step limit ($MAX_STEPS steps)")
                emit(steps, needsUser = true, userMessage = "I've taken $MAX_STEPS steps but haven't finished. Should I keep going?")
                AppLogger.w(TAG, "Task reached step safety cap ($MAX_STEPS steps)")
            }
        } finally {
            withContext(NonCancellable) {
                AppLogger.d(TAG, "Entering cleanup phase...")
                if (didOpenNotifications) {
                    AppLogger.i(TAG, "Cleanup: Dismissing notification shade")
                    if (android.os.Build.VERSION.SDK_INT >= 31) {
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
                    } else {
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    }
                    delay(300)
                }
                if (executor.didToggleSplitScreen) {
                    AppLogger.i(TAG, "Cleanup: Restoring screen from multi-window/split-screen mode")
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
                    executor.didToggleSplitScreen = false
                    delay(300)
                }
            }
        }
    }

    // ─── State emission ────────────────────────────────────────────────────────

    private fun emit(
        steps: List<String>,
        isDone: Boolean = false,
        needsUser: Boolean = false,
        userMessage: String = "",
        isError: Boolean = false,
        thinking: Boolean = false
    ) {
        val currentTask = _state.value.task
        _state.value = AgentState(
            task = currentTask,
            steps = steps.toMutableList(),
            isRunning = !isDone && !needsUser && !isError,
            isDone = isDone,
            needsUser = needsUser,
            userMessage = userMessage,
            isError = isError
        )
    }

    fun cancel() {
        scope.cancel()
    }

    private fun isInMultiWindowMode(service: AccessibilityService): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= 24) {
            val windows = service.windows
            for (window in windows) {
                if (window.type == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER) {
                    return true
                }
            }
        }
        return false
    }
}
