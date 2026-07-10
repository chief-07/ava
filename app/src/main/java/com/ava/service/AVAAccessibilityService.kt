package com.ava.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.ava.agent.AgentLoop
import com.ava.agent.GeminiClient
import com.ava.overlay.AVAOverlayService

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
class AVAAccessibilityService : AccessibilityService() {

    private var agentLoop: AgentLoop? = null
    private var geminiClient: GeminiClient? = null

    companion object {
        // Singleton reference so other components can reach the service
        var instance: AVAAccessibilityService? = null
            private set
    }

    // ─── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "AVA Accessibility Service connected ✅")

        // Load API key from secure storage (set via MainActivity)
        val prefs = getSharedPreferences("ava_config", MODE_PRIVATE)
        val apiKey = prefs.getString("gemini_api_key", "") ?: ""

        if (apiKey.isNotBlank()) {
            geminiClient = GeminiClient(apiKey)
            agentLoop = AgentLoop(this, geminiClient!!)
            Log.i(TAG, "Agent loop initialized with Gemini client")
        } else {
            Log.w(TAG, "No API key configured — agent loop not started")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        agentLoop?.cancel()
        geminiClient?.close()
        Log.i(TAG, "AVA Accessibility Service destroyed")
    }

    // ─── Accessibility events ──────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // We subscribe to all events (typeAllMask in config) so we can detect
        // screen changes during the agent loop. The AgentLoop uses this signal
        // to know when to re-read the screen after an action.
        //
        // For now we just log; later we'll use this for reactive waiting.
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                Log.v(TAG, "Window changed: ${event.packageName} / ${event.className}")
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                Log.v(TAG, "Content changed in: ${event.packageName}")
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
        agentLoop?.stop()
    }

    // ─── Public API (called by AVAOverlayService) ──────────────────────────────

    fun startTask(task: String) {
        val loop = agentLoop ?: run {
            Log.e(TAG, "Cannot start task — agent loop not initialized")
            return
        }

        // Start the overlay service so the banner appears
        val overlayIntent = Intent(this, AVAOverlayService::class.java).apply {
            action = AVAOverlayService.ACTION_START
            putExtra(AVAOverlayService.EXTRA_TASK, task)
        }
        startService(overlayIntent)

        // Start the agent
        loop.start(task)

        // Connect overlay to agent state updates
        AVAOverlayService.instance?.observeAgentState(loop.state)
    }

    fun stopTask() {
        agentLoop?.stop()
        val overlayIntent = Intent(this, AVAOverlayService::class.java).apply {
            action = AVAOverlayService.ACTION_STOP
        }
        startService(overlayIntent)
    }

    fun provideUserInput(input: String) {
        agentLoop?.provideUserInput(input)
    }

    fun isAgentRunning(): Boolean = agentLoop?.state?.value?.isRunning == true
}
