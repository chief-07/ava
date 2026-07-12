package com.ava.agent

import java.util.regex.Pattern

enum class OfflineActionType {
    HOME, BACK, NOTIFICATIONS, TAKE_SCREENSHOT, VOLUME, BRIGHTNESS, OPEN_APP
}

data class OfflineIntent(
    val type: OfflineActionType,
    val text: String = ""
)

object OfflineIntentParser {
    fun parse(command: String): List<OfflineIntent>? {
        val subCommands = command.split(Regex("(?i)\\b(?:then|and)\\b|;"))
        val intents = mutableListOf<OfflineIntent>()

        for (cmd in subCommands) {
            val trimmed = cmd.trim().lowercase()
            if (trimmed.isEmpty()) continue

            val intent = parseSingle(trimmed) ?: return null // fallback online if any fails
            intents.add(intent)
        }
        return if (intents.isEmpty()) null else intents
    }

    private fun parseSingle(cmd: String): OfflineIntent? {
        val openAppPattern = Pattern.compile("^(?:open|launch|start) (.+)$")
        val volumePattern = Pattern.compile("^(?:volume|vol) (up|down|mute)$")
        val brightnessPattern = Pattern.compile("^(?:brightness|bright) (up|down)$")

        val openAppMatcher = openAppPattern.matcher(cmd)
        if (openAppMatcher.matches()) {
            return OfflineIntent(OfflineActionType.OPEN_APP, openAppMatcher.group(1)!!.trim())
        }

        val volumeMatcher = volumePattern.matcher(cmd)
        if (volumeMatcher.matches()) {
            return OfflineIntent(OfflineActionType.VOLUME, volumeMatcher.group(1)!!)
        }

        val brightnessMatcher = brightnessPattern.matcher(cmd)
        if (brightnessMatcher.matches()) {
            return OfflineIntent(OfflineActionType.BRIGHTNESS, brightnessMatcher.group(1)!!)
        }

        return when (cmd) {
            "go home", "home", "press home" -> OfflineIntent(OfflineActionType.HOME)
            "go back", "back", "press back" -> OfflineIntent(OfflineActionType.BACK)
            "open notifications", "notifications", "show notifications" -> OfflineIntent(OfflineActionType.NOTIFICATIONS)
            "take a screenshot", "take screenshot", "screenshot" -> OfflineIntent(OfflineActionType.TAKE_SCREENSHOT)
            else -> null
        }
    }
}
