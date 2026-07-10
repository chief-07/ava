package com.ava.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.content.Intent
import android.os.Bundle
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
    suspend fun execute(action: AgentAction, root: AccessibilityNodeInfo?): String {
        return when (ActionType.valueOf(action.action.uppercase())) {
            ActionType.TAP -> tap(action.elementIndex, root)
            ActionType.LONG_PRESS -> longPress(action.elementIndex, root)
            ActionType.SCROLL_DOWN -> scroll(forward = true, root)
            ActionType.SCROLL_UP -> scroll(forward = false, root)
            ActionType.TYPE -> type(action.elementIndex, action.text, root)
            ActionType.BACK -> globalAction(AccessibilityService.GLOBAL_ACTION_BACK, "pressed Back")
            ActionType.HOME -> globalAction(AccessibilityService.GLOBAL_ACTION_HOME, "pressed Home")
            ActionType.NOTIFICATIONS -> globalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "opened notifications")
            ActionType.WAIT -> waitForChange()
            ActionType.ASK_USER -> "asking: ${action.message}"
            ActionType.DONE -> "done: ${action.message}"
            ActionType.SWIPE -> swipe(action.text)
            ActionType.OPEN_APP -> openApp(action.text)
        }
    }

    // ─── Individual actions ───────────────────────────────────────────────────

    private fun tap(elementIndex: Int, root: AccessibilityNodeInfo?): String {
        val node = ScreenReader.findNodeByIndex(root, elementIndex)
        return if (node != null && node.isClickable) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val label = node.text?.toString() ?: node.contentDescription?.toString() ?: "element $elementIndex"
            Log.d(TAG, "Tapped: $label")
            "tapped \"$label\""
        } else {
            // Fallback: tap by screen coordinates from bounds
            tapByCoordinates(elementIndex, root)
        }
    }

    private fun tapByCoordinates(elementIndex: Int, root: AccessibilityNodeInfo?): String {
        val node = ScreenReader.findNodeByIndex(root, elementIndex) ?: run {
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

    private fun longPress(elementIndex: Int, root: AccessibilityNodeInfo?): String {
        val node = ScreenReader.findNodeByIndex(root, elementIndex)
        if (node != null && node.isClickable) {
            val success = node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            if (success) {
                val label = node.text?.toString() ?: node.contentDescription?.toString() ?: "element $elementIndex"
                Log.d(TAG, "Long-pressed: $label")
                return "long-pressed \"$label\""
            }
        }
        return longPressByCoordinates(elementIndex, root)
    }

    private fun longPressByCoordinates(elementIndex: Int, root: AccessibilityNodeInfo?): String {
        val node = ScreenReader.findNodeByIndex(root, elementIndex) ?: run {
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
            ScreenReader.findNodeByIndex(root, elementIndex)
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

    private fun openApp(appName: String): String {
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
        return if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            service.startActivity(launchIntent)
            "opened app \"$appName\""
        } else {
            "could not launch app \"$appName\""
        }
    }
}
