package com.ava.agent

import kotlinx.serialization.Serializable

// ─── LLM Action Types ────────────────────────────────────────────────────────

/** Every action AVA can perform on the device. */
enum class ActionType {
    TAP,            // tap an element by index
    LONG_PRESS,     // tap and hold an element by index
    SCROLL_UP,      // scroll the focused scrollable up
    SCROLL_DOWN,    // scroll the focused scrollable down
    SWIPE,          // directional swipe (left/right/up/down)
    TYPE,           // type text into a focused field
    BACK,           // press the Back button
    HOME,           // press the Home button
    NOTIFICATIONS,  // pull down the notification shade
    WAIT,           // wait for a screen change (e.g. page loading)
    ASK_USER,       // agent needs clarification or hits a wall
    DONE,           // task complete
    OPEN_APP,       // instantly launch an app by name
    TAKE_SCREENSHOT, // take screenshot
    SET_VOLUME,     // adjust music volume
    SET_BRIGHTNESS  // adjust screen brightness
}

/** A single decision returned by the LLM. */
@Serializable
data class AgentAction(
    val action: String,         // matches ActionType name
    val elementIndex: Int = -1, // which element to act on (-1 = none)
    val text: String = "",      // text to type, or swipe direction
    val reasoning: String = "", // why the agent chose this action
    val message: String = ""    // message for ASK_USER / DONE summary
)

/** One node in the UI tree — passed to LLM as screen context. */
data class UIElement(
    val index: Int,
    val type: String,           // Button, TextView, EditText, etc.
    val text: String,
    val contentDesc: String,
    val isClickable: Boolean,
    val isScrollable: Boolean,
    val isEditable: Boolean,
    val bounds: String          // "left,top,right,bottom"
) {
    /** Compact text representation sent to the LLM. */
    override fun toString(): String {
        val label = text.ifBlank { contentDesc }.ifBlank { type }
        val flags = buildList {
            if (isClickable) add("tap")
            if (isScrollable) add("scroll")
            if (isEditable) add("type")
        }.joinToString("/")
        return "[$index] $label ($flags) @$bounds"
    }
}

/** The full context of the current screen, ready to send to the LLM. */
data class ScreenContext(
    val appPackage: String,
    val activityName: String,
    val elements: List<UIElement>
) {
    fun toPromptString(): String = buildString {
        appendLine("SCREEN: $appPackage / $activityName")
        appendLine("ELEMENTS:")
        elements.forEach { appendLine("  $it") }
    }
}

/** Running state of the agent during a task. */
data class AgentState(
    val task: String,
    val steps: MutableList<String> = mutableListOf(),
    val isRunning: Boolean = true,
    val isDone: Boolean = false,
    val needsUser: Boolean = false,
    val userMessage: String = ""
)
