package com.ava.agent

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.graphics.Rect

private const val TAG = "AVA:ScreenReader"
private const val MAX_ELEMENTS = 80

/**
 * ScreenReader converts visible AccessibilityNodeInfo trees from all active windows
 * (split-screen aware) into a compact, LLM-readable ScreenContext.
 *
 * It caches the active Node references to allow O(1) lookup during action execution.
 */
object ScreenReader {

    // Thread-safe cache of AccessibilityNodeInfo from the last buildContext call
    private val cachedNodes = mutableListOf<AccessibilityNodeInfo>()

    /**
     * Build a ScreenContext by traversing all visible application and system windows.
     * Caches the traversed node references for direct indexed lookup.
     */
    fun buildContext(service: AccessibilityService): ScreenContext {
        val interactiveElements = mutableListOf<UIElement>()
        val textOnlyElements = mutableListOf<UIElement>()
        
        val interactiveNodes = mutableListOf<AccessibilityNodeInfo>()
        val textOnlyNodes = mutableListOf<AccessibilityNodeInfo>()

        // Get package name of active window
        val activeRoot = service.rootInActiveWindow
        val activePackage = activeRoot?.packageName?.toString() ?: "unknown"

        val windows = try {
            service.windows
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve windows: ${e.message}")
            emptyList<AccessibilityWindowInfo>()
        }

        // Filter for visible application and system windows (e.g. notifications shade)
        val appWindows = windows.filter {
            (it.type == AccessibilityWindowInfo.TYPE_APPLICATION || 
             it.type == AccessibilityWindowInfo.TYPE_SYSTEM) && 
            it.root != null
        }

        if (appWindows.isEmpty()) {
            // Fallback to active window root if windows list is empty
            if (activeRoot != null) {
                traverseNode(activeRoot, interactiveElements, textOnlyElements, interactiveNodes, textOnlyNodes, depth = 0)
            }
        } else {
            appWindows.forEach { window ->
                val root = window.root
                if (root != null) {
                    traverseNode(root, interactiveElements, textOnlyElements, interactiveNodes, textOnlyNodes, depth = 0)
                }
            }
        }

        val finalElements = mutableListOf<UIElement>()
        synchronized(cachedNodes) {
            // Release old references
            cachedNodes.clear()
            
            // Add interactive elements first
            val interactiveLimit = minOf(interactiveElements.size, MAX_ELEMENTS)
            for (i in 0 until interactiveLimit) {
                finalElements.add(interactiveElements[i])
                cachedNodes.add(interactiveNodes[i])
            }
            
            // Fill remaining budget with text-only elements
            val remaining = MAX_ELEMENTS - finalElements.size
            if (remaining > 0) {
                val textLimit = minOf(textOnlyElements.size, remaining)
                for (i in 0 until textLimit) {
                    finalElements.add(textOnlyElements[i])
                    cachedNodes.add(textOnlyNodes[i])
                }
            }
        }

        // Re-index so element indices align exactly with cachedNodes list indices
        val reindexed = finalElements.mapIndexed { idx, el -> el.copy(index = idx) }

        Log.d(TAG, "Built split-screen context: ${reindexed.size} elements cached (Active package: $activePackage)")
        return ScreenContext(activePackage, "", reindexed)
    }

    private fun traverseNode(
        node: AccessibilityNodeInfo,
        interactiveElements: MutableList<UIElement>,
        textOnlyElements: MutableList<UIElement>,
        interactiveNodes: MutableList<AccessibilityNodeInfo>,
        textOnlyNodes: MutableList<AccessibilityNodeInfo>,
        depth: Int
    ) {
        try {
            if (!node.isVisibleToUser) return

            val text = node.text?.toString() ?: ""
            val contentDesc = node.contentDescription?.toString() ?: ""
            val className = node.className?.toString()?.substringAfterLast('.') ?: "View"

            val isInteractive = node.isClickable || node.isScrollable || node.isEditable
            val hasLabel = text.isNotBlank() || contentDesc.isNotBlank()
            val isInteresting = isInteractive || hasLabel

            if (isInteresting) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)

                val element = UIElement(
                    index = 0, // will be re-indexed sequentially
                    type = className,
                    text = text.take(80),
                    contentDesc = contentDesc.take(80),
                    isClickable = node.isClickable,
                    isScrollable = node.isScrollable,
                    isEditable = node.isEditable,
                    isChecked = node.isChecked,
                    isSelected = node.isSelected,
                    isFocused = node.isFocused,
                    bounds = "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}",
                    depth = depth
                )

                if (isInteractive) {
                    interactiveElements.add(element)
                    interactiveNodes.add(node)
                } else {
                    textOnlyElements.add(element)
                    textOnlyNodes.add(node)
                }
            }

            // Recurse into children
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    traverseNode(child, interactiveElements, textOnlyElements, interactiveNodes, textOnlyNodes, depth + 1)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error traversing node: ${e.message}")
        }
    }

    /**
     * Retrieve the AccessibilityNodeInfo node at the specified elementIndex.
     * Takes O(1) time and guarantees that indices align with the screen context.
     */
    fun getNode(elementIndex: Int): AccessibilityNodeInfo? {
        return synchronized(cachedNodes) {
            cachedNodes.getOrNull(elementIndex)
        }
    }
}
