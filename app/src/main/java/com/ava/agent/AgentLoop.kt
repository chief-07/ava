package com.ava.agent

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
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

    // ─── Control ───────────────────────────────────────────────────────────────

    fun start(task: String) {
        loopJob?.cancel()
        _state.value = AgentState(task = task, isRunning = true)
        loopJob = scope.launch { runLoop(task) }
        Log.i(TAG, "Started task: $task")
    }

    fun stop() {
        loopJob?.cancel()
        _state.update { it.copy(isRunning = false) }
        Log.i(TAG, "Task stopped by user")
    }

    fun provideUserInput(input: String) {
        // Resume the loop after the user answered a question
        if (_state.value.needsUser) {
            val currentTask = _state.value.task
            val enrichedTask = "$currentTask\nUser clarification: $input"
            start(enrichedTask)
        }
    }

    // ─── Main loop ─────────────────────────────────────────────────────────────

    private suspend fun runLoop(task: String) {
        val steps = mutableListOf<String>()
        var stepCount = 0
        var sameScreenCount = 0
        var lastScreenHash = ""

        while (stepCount < MAX_STEPS && _state.value.isRunning) {
            stepCount++

            // 1. READ — get current screen
            val root = service.rootInActiveWindow
            val appPackage = service.rootInActiveWindow?.packageName?.toString() ?: "unknown"
            val screen = ScreenReader.buildContext(root, appPackage, "")

            // Stuck detection: same screen hash N times
            val screenHash = screen.elements.joinToString { it.text + it.contentDesc }
            if (screenHash == lastScreenHash) {
                sameScreenCount++
                if (sameScreenCount >= STUCK_THRESHOLD) {
                    Log.w(TAG, "Stuck detected after $sameScreenCount identical screens")
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
            Log.d(TAG, "Step $stepCount — asking Gemini")
            val action = gemini.decideNextAction(task, screen, steps)
            Log.d(TAG, "Action: ${action.action} — ${action.reasoning}")

            // 3. ACT — execute
            val actionType = try {
                ActionType.valueOf(action.action.uppercase())
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Unknown action type: ${action.action}")
                ActionType.ASK_USER
            }

            when (actionType) {
                ActionType.DONE -> {
                    val summary = action.message.ifBlank { "Task complete." }
                    steps.add("✅ Done: $summary")
                    emit(steps, isDone = true)
                    Log.i(TAG, "Task complete: $summary")
                    return
                }
                ActionType.ASK_USER -> {
                    steps.add("❓ ${action.message}")
                    emit(steps, needsUser = true, userMessage = action.message)
                    Log.i(TAG, "Needs user: ${action.message}")
                    return
                }
                else -> {
                    val stepDesc = executor.execute(action, root)
                    steps.add("→ $stepDesc")
                    emit(steps)
                    // Small delay to let the screen settle after action
                    delay(800)
                }
            }
        }

        // Hit step cap
        if (stepCount >= MAX_STEPS) {
            steps.add("⚠️ Reached step limit ($MAX_STEPS steps)")
            emit(steps, needsUser = true, userMessage = "I've taken $MAX_STEPS steps but haven't finished. Should I keep going?")
        }
    }

    // ─── State emission ────────────────────────────────────────────────────────

    private fun emit(
        steps: List<String>,
        isDone: Boolean = false,
        needsUser: Boolean = false,
        userMessage: String = "",
        thinking: Boolean = false
    ) {
        val currentTask = _state.value.task
        _state.value = AgentState(
            task = currentTask,
            steps = steps.toMutableList(),
            isRunning = !isDone && !needsUser,
            isDone = isDone,
            needsUser = needsUser,
            userMessage = userMessage
        )
    }

    fun cancel() {
        scope.cancel()
    }
}
