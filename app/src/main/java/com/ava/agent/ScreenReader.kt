package com.ava.agent

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Rect

private const val TAG = "AVA:ScreenReader"
private const val MAX_ELEMENTS = 80 // cap elements sent to LLM to keep prompt lean

/**
 * ScreenReader converts the Android AccessibilityNodeInfo tree into
 * a compact, LLM-readable ScreenContext.
 *
 * It traverses the entire visible UI tree and extracts only the
 * elements that matter — interactive ones plus key text labels.
 * Interactive elements (clickable/editable/scrollable) are prioritized
 * over static text nodes to ensure actionable elements are never clipped.
 */
object ScreenReader {

    /**
     * Build a ScreenContext from the root node of the active window.
     *
     * @param root      The root AccessibilityNodeInfo (from getRootInActiveWindow())
     * @param appPackage The current foreground app package name
     * @param activityName The current activity class name
     */
    fun buildContext(
        root: AccessibilityNodeInfo?,
        appPackage: String,
        activityName: String
    ): ScreenContext {
        if (root == null) {
            Log.w(TAG, "Root node is null — returning empty context")
            return ScreenContext(appPackage, activityName, emptyList())
        }

        val interactive = mutableListOf<UIElement>()
        val textOnly = mutableListOf<UIElement>()
        traverseNode(root, interactive, textOnly, depth = 0)

        // Prioritize interactive elements, then fill remaining slots with text-only
        val combined = mutableListOf<UIElement>()
        combined.addAll(interactive)
        val remaining = MAX_ELEMENTS - combined.size
        if (remaining > 0) {
            combined.addAll(textOnly.take(remaining))
        }

        // Re-index so element indices are sequential
        val reindexed = combined.mapIndexed { idx, el -> el.copy(index = idx) }

        Log.d(TAG, "Built context: ${reindexed.size} elements (${interactive.size} interactive, ${textOnly.size} text-only) for $appPackage")
        return ScreenContext(appPackage, activityName, reindexed)
    }

    // ─── Tree traversal ───────────────────────────────────────────────────────

    private fun traverseNode(
        node: AccessibilityNodeInfo,
        interactive: MutableList<UIElement>,
        textOnly: MutableList<UIElement>,
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
                    index = 0, // will be re-indexed after sorting
                    type = className,
                    text = text.take(80), // truncate long text
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
                    interactive.add(element)
                } else {
                    textOnly.add(element)
                }
            }

            // Recurse into children
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    traverseNode(child, interactive, textOnly, depth + 1)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error traversing node: ${e.message}")
        }
    }

    /**
     * Find a specific node by its element index from the last context snapshot.
     * Used by ActionExecutor to tap/interact with a specific element.
     *
     * IMPORTANT: This must produce the same ordering as buildContext
     * (interactive first, then text-only, re-indexed) so indices match.
     */
    fun findNodeByIndex(
        root: AccessibilityNodeInfo?,
        targetIndex: Int
    ): AccessibilityNodeInfo? {
        if (root == null) return null
        val interactiveNodes = mutableListOf<AccessibilityNodeInfo>()
        val textOnlyNodes = mutableListOf<AccessibilityNodeInfo>()
        traverseNodeForLookup(root, interactiveNodes, textOnlyNodes)

        val combined = mutableListOf<AccessibilityNodeInfo>()
        combined.addAll(interactiveNodes)
        val remaining = MAX_ELEMENTS - combined.size
        if (remaining > 0) {
            combined.addAll(textOnlyNodes.take(remaining))
        }

        return combined.getOrNull(targetIndex)
    }

    private fun traverseNodeForLookup(
        node: AccessibilityNodeInfo,
        interactiveNodes: MutableList<AccessibilityNodeInfo>,
        textOnlyNodes: MutableList<AccessibilityNodeInfo>
    ) {
        try {
            if (!node.isVisibleToUser) return

            val text = node.text?.toString() ?: ""
            val contentDesc = node.contentDescription?.toString() ?: ""

            val isInteractive = node.isClickable || node.isScrollable || node.isEditable
            val hasLabel = text.isNotBlank() || contentDesc.isNotBlank()
            val isInteresting = isInteractive || hasLabel

            if (isInteresting) {
                if (isInteractive) {
                    interactiveNodes.add(node)
                } else {
                    textOnlyNodes.add(node)
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    traverseNodeForLookup(child, interactiveNodes, textOnlyNodes)
                    // Note: do NOT recycle here — caller holds references
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in node lookup traversal: ${e.message}")
        }
    }
}
