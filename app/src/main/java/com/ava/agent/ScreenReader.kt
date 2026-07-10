package com.ava.agent

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Rect

private const val TAG = "AVA:ScreenReader"
private const val MAX_ELEMENTS = 60 // cap elements sent to LLM to keep prompt lean

/**
 * ScreenReader converts the Android AccessibilityNodeInfo tree into
 * a compact, LLM-readable ScreenContext.
 *
 * It traverses the entire visible UI tree and extracts only the
 * elements that matter — interactive ones plus key text labels.
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

        val elements = mutableListOf<UIElement>()
        traverseNode(root, elements)

        Log.d(TAG, "Built context: ${elements.size} elements for $appPackage")
        return ScreenContext(appPackage, activityName, elements.take(MAX_ELEMENTS))
    }

    // ─── Tree traversal ───────────────────────────────────────────────────────

    private fun traverseNode(
        node: AccessibilityNodeInfo,
        elements: MutableList<UIElement>
    ) {
        try {
            if (!node.isVisibleToUser) return

            val text = node.text?.toString() ?: ""
            val contentDesc = node.contentDescription?.toString() ?: ""
            val className = node.className?.toString()?.substringAfterLast('.') ?: "View"

            val isInteresting = node.isClickable || node.isScrollable ||
                    node.isEditable || text.isNotBlank() || contentDesc.isNotBlank()

            if (isInteresting) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)

                elements.add(
                    UIElement(
                        index = elements.size,
                        type = className,
                        text = text.take(80), // truncate long text
                        contentDesc = contentDesc.take(80),
                        isClickable = node.isClickable,
                        isScrollable = node.isScrollable,
                        isEditable = node.isEditable,
                        bounds = "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"
                    )
                )
            }

            // Recurse into children
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    traverseNode(child, elements)
                    child.recycle()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error traversing node: ${e.message}")
        }
    }

    /**
     * Find a specific node by its element index from the last context snapshot.
     * Used by ActionExecutor to tap/interact with a specific element.
     */
    fun findNodeByIndex(
        root: AccessibilityNodeInfo?,
        targetIndex: Int
    ): AccessibilityNodeInfo? {
        if (root == null) return null
        val elements = mutableListOf<UIElement>()
        val nodeMap = mutableMapOf<Int, AccessibilityNodeInfo>()
        traverseNodeWithMap(root, elements, nodeMap)
        return nodeMap[targetIndex]
    }

    private fun traverseNodeWithMap(
        node: AccessibilityNodeInfo,
        elements: MutableList<UIElement>,
        nodeMap: MutableMap<Int, AccessibilityNodeInfo>
    ) {
        try {
            if (!node.isVisibleToUser) return

            val text = node.text?.toString() ?: ""
            val contentDesc = node.contentDescription?.toString() ?: ""

            val isInteresting = node.isClickable || node.isScrollable ||
                    node.isEditable || text.isNotBlank() || contentDesc.isNotBlank()

            if (isInteresting) {
                val idx = elements.size
                nodeMap[idx] = node // keep reference (don't recycle)
                elements.add(UIElement(idx, "", text, contentDesc,
                    node.isClickable, node.isScrollable, node.isEditable, ""))
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    traverseNodeWithMap(child, elements, nodeMap)
                    // Note: do NOT recycle here — nodeMap holds references
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in node map traversal: ${e.message}")
        }
    }
}
