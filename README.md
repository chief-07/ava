# AVA — AI Voice Agent

## Local setup
1. Add your Gemini API key in the app's setup screen
2. Enable Accessibility Service: Settings → Accessibility → AVA
3. Enable Draw Over Apps: Settings → Apps → AVA → Display over other apps

## Build
```
.\gradlew assembleDebug
```

## Install to device
```
.\gradlew installDebug
# OR
adb install app\build\outputs\apk\debug\app-debug.apk
```

## View logs
```
adb logcat -s AVA:AccessibilityService AVA:AgentLoop AVA:GeminiClient AVA:ActionExecutor AVA:OverlayService
```

## Project structure
```
app/src/main/java/com/ava/
  agent/
    AgentModels.kt       — data classes: UIElement, ScreenContext, AgentAction, AgentState
    ScreenReader.kt      — converts AccessibilityNodeInfo tree → LLM-readable context
    GeminiClient.kt      — Gemini Flash REST API client
    ActionExecutor.kt    — executes LLM decisions via AccessibilityService APIs
    AgentLoop.kt         — the main Read→Think→Act loop
  service/
    AVAAccessibilityService.kt  — the privileged Android service (core of everything)
  overlay/
    AVAOverlayService.kt — foreground service that manages the top banner
    AVABanner.kt         — Compose UI for the banner
  voice/
    SpeechInput.kt       — Android STT wrapper as a suspend function
  ui/
    MainActivity.kt      — setup screen + mic button
```
