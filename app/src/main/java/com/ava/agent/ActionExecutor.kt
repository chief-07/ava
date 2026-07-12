package com.ava.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay

private const val TAG = "AVA:ActionExecutor"

/**
 * ActionExecutor takes a parsed AgentAction and carries it out
 * on the device via the AccessibilityService APIs.
 *
 * All actions are dispatched through the service reference —
 * ActionExecutor itself has no permissions, it delegates everything
 * to the service which holds the BIND_ACCESSIBILITY_SERVICE grant.
 */
class ActionExecutor(private val service: AccessibilityService) {

    /**
     * Execute the given action. Returns a human-readable description
     * of what was done (appended to the step history shown in the overlay).
     */
    suspend fun execute(action: AgentAction, task: String = ""): String {
        val root = try {
            service.rootInActiveWindow
        } catch (e: Exception) {
            null
        }
        return when (ActionType.valueOf(action.action.uppercase())) {
            ActionType.TAP -> tap(action.elementIndex)
            ActionType.LONG_PRESS -> longPress(action.elementIndex)
            ActionType.SCROLL_DOWN -> scroll(forward = true, root)
            ActionType.SCROLL_UP -> scroll(forward = false, root)
            ActionType.TYPE -> type(action.elementIndex, action.text, root)
            ActionType.ENTER -> pressEnter(root)
            ActionType.BACK -> globalAction(AccessibilityService.GLOBAL_ACTION_BACK, "pressed Back")
            ActionType.HOME -> globalAction(AccessibilityService.GLOBAL_ACTION_HOME, "pressed Home")
            ActionType.NOTIFICATIONS -> globalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "opened notifications")
            ActionType.WAIT -> waitForChange()
            ActionType.ASK_USER -> "asking: ${action.message}"
            ActionType.DONE -> "done: ${action.message}"
            ActionType.SWIPE -> swipe(action.text)
            ActionType.OPEN_APP -> openApp(action.text, task)
            ActionType.TAKE_SCREENSHOT -> takeScreenshot()
            ActionType.SET_VOLUME -> setVolume(action.text)
            ActionType.SET_BRIGHTNESS -> setBrightness(action.text)
        }
    }

    // ─── Individual actions ───────────────────────────────────────────────────

    private fun tap(elementIndex: Int): String {
        val node = ScreenReader.getNode(elementIndex)
        return if (node != null && node.isClickable) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val label = node.text?.toString() ?: node.contentDescription?.toString() ?: "element $elementIndex"
            Log.d(TAG, "Tapped: $label")
            "tapped \"$label\""
        } else {
            // Fallback: tap by screen coordinates from bounds
            tapByCoordinates(elementIndex)
        }
    }

    private fun tapByCoordinates(elementIndex: Int): String {
        val node = ScreenReader.getNode(elementIndex) ?: run {
            Log.w(TAG, "Element $elementIndex not found")
            return "could not find element $elementIndex"
        }
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val x = bounds.centerX().toFloat()
        val y = bounds.centerY().toFloat()
        dispatchTap(x, y)
        return "tapped coordinates ($x, $y)"
    }

    private fun dispatchTap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        service.dispatchGesture(gesture, null, null)
        Log.d(TAG, "Dispatched tap at ($x, $y)")
    }

    private fun longPress(elementIndex: Int): String {
        val node = ScreenReader.getNode(elementIndex)
        if (node != null && node.isClickable) {
            val success = node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            if (success) {
                val label = node.text?.toString() ?: node.contentDescription?.toString() ?: "element $elementIndex"
                Log.d(TAG, "Long-pressed: $label")
                return "long-pressed \"$label\""
            }
        }
        return longPressByCoordinates(elementIndex)
    }

    private fun longPressByCoordinates(elementIndex: Int): String {
        val node = ScreenReader.getNode(elementIndex) ?: run {
            Log.w(TAG, "Element $elementIndex not found for long-press")
            return "could not find element $elementIndex"
        }
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val x = bounds.centerX().toFloat()
        val y = bounds.centerY().toFloat()
        dispatchLongPress(x, y)
        val label = node.text?.toString() ?: node.contentDescription?.toString() ?: "element $elementIndex"
        return "long-pressed \"$label\" at ($x, $y)"
    }

    private fun dispatchLongPress(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 1000)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        service.dispatchGesture(gesture, null, null)
        Log.d(TAG, "Dispatched long press at ($x, $y)")
    }

    private fun scroll(forward: Boolean, root: AccessibilityNodeInfo?): String {
        // Find the first scrollable element and scroll it
        val scrollable = findScrollable(root)
        return if (scrollable != null) {
            val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                         else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            scrollable.performAction(action)
            if (forward) "scrolled down" else "scrolled up"
        } else {
            // Fallback: swipe gesture on the center of the screen
            val dir = if (forward) "down" else "up"
            swipe(dir)
        }
    }

    private fun swipe(direction: String): String {
        val displayMetrics = service.resources.displayMetrics
        val w = displayMetrics.widthPixels.toFloat()
        val h = displayMetrics.heightPixels.toFloat()
        val cx = w / 2
        val cy = h / 2

        val (startX, startY, endX, endY) = when (direction.lowercase()) {
            "up"    -> listOf(cx, cy + 300, cx, cy - 300)
            "down"  -> listOf(cx, cy - 300, cx, cy + 300)
            "left"  -> listOf(cx + 300, cy, cx - 300, cy)
            "right" -> listOf(cx - 300, cy, cx + 300, cy)
            else    -> listOf(cx, cy + 300, cx, cy - 300) // default: swipe up
        }

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 300)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        service.dispatchGesture(gesture, null, null)
        return "swiped $direction"
    }

    private fun type(elementIndex: Int, text: String, root: AccessibilityNodeInfo?): String {
        val editable = if (elementIndex >= 0) {
            ScreenReader.getNode(elementIndex)
        } else {
            findEditable(root)
        }

        return if (editable != null) {
            val args = Bundle().apply {
                putString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.d(TAG, "Typed \"$text\" into element $elementIndex")
            val label = editable.text?.toString() ?: editable.contentDescription?.toString() ?: "element $elementIndex"
            "typed \"${text.take(20)}\" into \"$label\""
        } else {
            val fallback = findEditable(root)
            if (fallback != null) {
                val args = Bundle().apply {
                    putString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                fallback.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                "typed \"${text.take(20)}\" into focused field"
            } else {
                Log.w(TAG, "No editable field found to type into")
                "could not find a text field to type into"
            }
        }
    }

    private fun pressEnter(root: AccessibilityNodeInfo?): String {
        val editable = findEditable(root)
        if (editable != null) {
            // API 30+ supports ACTION_IME_ENTER directly
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val success = editable.performAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                )
                if (success) {
                    Log.d(TAG, "Pressed Enter via ACTION_IME_ENTER")
                    return "pressed Enter on keyboard"
                }
            }
            // Fallback: append newline to trigger IME submit on older devices
            val currentText = editable.text?.toString() ?: ""
            val args = Bundle().apply {
                putString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, currentText + "\n")
            }
            editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.d(TAG, "Pressed Enter via newline fallback")
            return "submitted text input"
        }
        Log.w(TAG, "No editable field found to press Enter")
        return "could not find focused field to press Enter"
    }

    private fun globalAction(action: Int, description: String): String {
        service.performGlobalAction(action)
        return description
    }

    private suspend fun waitForChange(): String {
        // The AccessibilityService event listener will wake the agent loop
        // when a screen change is detected. Here we just sleep as a fallback.
        delay(2000)
        return "waited for screen change"
    }

    // ─── Node finders ─────────────────────────────────────────────────────────

    private fun findScrollable(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        root ?: return null
        if (root.isScrollable) return root
        for (i in 0 until root.childCount) {
            val result = findScrollable(root.getChild(i))
            if (result != null) return result
        }
        return null
    }

    private fun findEditable(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        root ?: return null
        if (root.isEditable && root.isFocused) return root
        if (root.isEditable) return root // fallback: first editable
        for (i in 0 until root.childCount) {
            val result = findEditable(root.getChild(i))
            if (result != null) return result
        }
        return null
    }

    private fun openApp(appName: String, task: String): String {
        val pm = service.packageManager
        val packages = pm.getInstalledPackages(0)
        var targetPackage: String? = null
        for (pkg in packages) {
            val appInfo = pkg.applicationInfo ?: continue
            val label = appInfo.loadLabel(pm).toString()
            if (label.equals(appName, ignoreCase = true) || label.contains(appName, ignoreCase = true)) {
                if (pm.getLaunchIntentForPackage(pkg.packageName) != null) {
                    targetPackage = pkg.packageName
                    break
                }
            }
        }

        if (targetPackage == null) {
            Log.w(TAG, "App not found by name: $appName")
            return "app \"$appName\" not found"
        }

        val launchIntent = pm.getLaunchIntentForPackage(targetPackage)
            ?: return "could not launch app \"$appName\""

        val prefs = service.getSharedPreferences("ava_config", Context.MODE_PRIVATE)
        val splitScreenPref = prefs.getBoolean("use_split_screen", false)
        // If it's a simple "open app only" task, do NOT split screen
        val useSplitScreen = splitScreenPref && !isSimpleOpenAppTask(task, appName)

        if (useSplitScreen) {
            try {
                Log.d(TAG, "Attempting to launch app in multitasking mode")
                
                // Try launching in Freeform/Popup Mode first using reflection
                val options = android.app.ActivityOptions.makeBasic()
                val setLaunchWindowingMode = android.app.ActivityOptions::class.java.getMethod(
                    "setLaunchWindowingMode", Int::class.javaPrimitiveType
                )
                setLaunchWindowingMode.invoke(options, 5) // 5 = freeform window mode
                
                val metrics = service.resources.displayMetrics
                val w = metrics.widthPixels
                val h = metrics.heightPixels
                options.setLaunchBounds(android.graphics.Rect(
                    (w * 0.1).toInt(), (h * 0.25).toInt(), 
                    (w * 0.9).toInt(), (h * 0.75).toInt()
                ))
                
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                service.startActivity(launchIntent, options.toBundle())
                return "opened app \"$appName\" in popup window"
            } catch (freeformEx: Exception) {
                Log.w(TAG, "Freeform launch option failed, falling back to Split-Screen: ${freeformEx.message}")
                
                // Fallback to Split-Screen mode
                try {
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_NEW_TASK)
                    service.startActivity(launchIntent)
                    return "opened app \"$appName\" in split-screen"
                } catch (splitEx: Exception) {
                    Log.e(TAG, "Multitasking fallback failed, launching full screen: ${splitEx.message}")
                }
            }
        }

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        service.startActivity(launchIntent)
        return "opened app \"$appName\""
    }

    private fun takeScreenshot(): String {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val success = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
            if (success) "took screenshot and saved to gallery"
            else "failed to trigger system screenshot"
        } else {
            Log.w(TAG, "Screenshot global action is not supported on Android versions below 9")
            "screenshots not supported on this device version"
        }
    }

    private fun parsePercentage(text: String): Int? {
        val cleaned = text.filter { it.isDigit() }
        return cleaned.toIntOrNull()?.coerceIn(0, 100)
    }

    private fun setVolume(text: String): String {
        val audioManager = service.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return "failed to access volume service"

        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (text.equals("up", ignoreCase = true)) {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            return "increased volume"
        }
        if (text.equals("down", ignoreCase = true)) {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            return "decreased volume"
        }

        val percentage = parsePercentage(text)
        return if (percentage != null) {
            val targetVolume = (maxVolume * percentage) / 100
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, AudioManager.FLAG_SHOW_UI)
            "set volume to $percentage%"
        } else {
            "invalid volume command: \"$text\""
        }
    }

    private fun setBrightness(text: String): String {
        val contentResolver = service.contentResolver
        if (!Settings.System.canWrite(service)) {
            Log.w(TAG, "Modify system settings permission not granted for brightness control")
            try {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:${service.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                service.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch write settings intent: ${e.message}")
            }
            return "WRITE_SETTINGS permission required: opening settings to grant it"
        }

        val currentBrightness = try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (_: Exception) {
            128
        }

        if (text.equals("up", ignoreCase = true)) {
            val target = (currentBrightness + 40).coerceIn(10, 255)
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, target)
            return "increased brightness"
        }
        if (text.equals("down", ignoreCase = true)) {
            val target = (currentBrightness - 40).coerceIn(10, 255)
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, target)
            return "decreased brightness"
        }

        val percentage = parsePercentage(text)
        return if (percentage != null) {
            val targetBrightness = (255 * percentage) / 100
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, targetBrightness)
            "set brightness to $percentage%"
        } else {
            "invalid brightness command: \"$text\""
        }
    }

    private fun isSimpleOpenAppTask(task: String, appName: String): Boolean {
        val cleanTask = task.lowercase().trim()
        val cleanAppName = appName.lowercase().trim()
        
        if (cleanTask.isEmpty()) return false

        // Common open app prefixes
        val prefixes = listOf("open ", "launch ", "start ", "go to ", "open up ")
        
        for (prefix in prefixes) {
            if (cleanTask.startsWith(prefix)) {
                val remaining = cleanTask.substring(prefix.length).trim()
                // e.g. "youtube" or "youtube please" or "youtube only"
                if (remaining == cleanAppName || 
                    (remaining.contains(cleanAppName) && remaining.length <= cleanAppName.length + 8)) {
                    return true
                }
                // Check if it's a short command containing the app name and no other action verbs
                val verbs = listOf("search", "type", "send", "find", "click", "tap", "message", "post", "check")
                if (remaining.contains(cleanAppName) && verbs.none { remaining.contains(it) } && remaining.length < 30) {
                    return true
                }
            }
        }
        
        // Direct matches or short commands without other action verbs
        if (cleanTask == cleanAppName || (cleanTask.length < 20 && cleanTask.contains(cleanAppName))) {
            val verbs = listOf("search", "type", "send", "find", "click", "tap", "message", "post", "check", "open", "launch", "start", "go")
            val hasOtherVerbs = verbs.filterNot { it == "open" || it == "launch" || it == "start" || it == "go" }
                .any { cleanTask.contains(it) }
            if (!hasOtherVerbs) {
                return true
            }
        }
        
        return false
    }
}
