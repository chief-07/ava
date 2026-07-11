package com.ava.agent

import android.util.Log
import io.ktor.client.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import com.ava.util.AppLogger

private const val TAG = "AVA:GeminiClient"

/**
 * GeminiClient sends screen context + task to Gemini Flash and
 * returns a parsed AgentAction.
 *
 * Uses Gemini's generateContent REST API via Ktor.
 */
class GeminiClient(val apiKey: String) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = HttpClient(Android) {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 20000
            connectTimeoutMillis = 10000
            socketTimeoutMillis = 15000
        }
        install(Logging) {
            logger = object : Logger {
                override fun log(message: String) {
                    AppLogger.d("Ktor", message)
                }
            }
            level = LogLevel.ALL
        }
    }

    private val model = "gemini-3.1-flash-lite"

    private fun requiresSearch(task: String): Boolean {
        val lowercase = task.lowercase()
        val keywords = listOf(
            "weather", "score", "news", "president", "temperature", "who is", "what is",
            "date", "time", "stock", "price", "match", "game", "current", "latest", "today", "yesterday"
        )
        return lowercase.contains("?") || keywords.any { lowercase.contains(it) }
    }

    // ─── Main method ─────────────────────────────────────────────────────────

    /**
     * Ask Gemini what action to take next given the task and screen context.
     */
    suspend fun decideNextAction(
        task: String,
        screenContext: ScreenContext,
        stepHistory: List<String>
    ): AgentAction {
        val prompt = buildPrompt(task, screenContext, stepHistory)
        val enableSearch = requiresSearch(task)

        return try {
            val responseText = callGemini(prompt, enableSearch)
            parseAction(responseText)
        } catch (e: Throwable) {
            AppLogger.e(TAG, "Gemini call failed: ${e.message}", e)
            AgentAction(
                action = ActionType.ASK_USER.name,
                message = "I ran into an error: ${e.message}. Should I try again?"
            )
        }
    }

    // ─── Prompt construction ──────────────────────────────────────────────────

    private fun buildPrompt(
        task: String,
        screen: ScreenContext,
        history: List<String>
    ): String = buildString {
        appendLine("""
            You are AVA, an AI agent controlling an Android phone on behalf of the user.
            Your job is to complete the user's task by deciding one action at a time.
            
            RULES:
            - Always respond with ONLY valid JSON — no markdown, no explanation outside JSON.
            - Choose the most logical next action based on the current screen.
            - If the task is complete, respond with action "DONE".
            - If you are stuck or need user input, respond with action "ASK_USER".
            - Never perform irreversible actions (delete, send, purchase) without noting it in reasoning.
            - element indexes start at 0 and match the ELEMENTS list exactly.
            
            COGNITIVE & NAVIGATION GUIDELINES:
            - ANTI-REPETITION: Look at the "STEPS TAKEN SO FAR" history. If an action you tried previously resulted in no screen change, did not work, or was ineffective, DO NOT repeat the exact same action or tap the same element index. Try a different element, a different approach, or scroll.
            - BACKTRACKING RECOVERY: If you tap a link or button and it opens a wrong page, advertisement, or dead-end, immediately execute the "BACK" action to return to the previous screen. Do not try to proceed on an incorrect page.
            - BROWSER SEARCH INTUITION: Do not type into Chrome's top address/URL bar unless you need to navigate to a completely new website. If you are already on the correct website, search within the page itself: tap the website's search box, click a search icon (🔍), open the hamburger menu drawer (☰), or default to searching via google.com and clicking the result.
            - SCREEN INTUITION & LLM KNOWLEDGE: Use your general knowledge as an LLM to interpret screen elements. For example, infer what unlabeled icons (☰, 🔍, 🛒, 👤) represent, read text context to identify forms or status changes, and use your reasoning to decide what element is most likely to contain the content you need.
            - SELECTION STATE VERIFICATION: When executing multi-step actions (such as long-pressing to select an item), ALWAYS verify in the current SCREEN/ELEMENTS list that the selection state has successfully registered (e.g. look for selection indicators, checkboxes, trash can icons, or header text like "1 selected") BEFORE tapping follow-up menu buttons like "More options". If the selection state is not yet visible, use the WAIT action to let the screen update.
            - CONTEXT_VALIDATION: Always verify that the current screen, page header, active website URL, or app context matches the target of your task BEFORE performing actions. For example, if you want to message a contact on WhatsApp, verify the header displays their name; if you want to edit a website setting, verify the URL/domain is correct. If you land on a wrong page, account, or settings tab, navigate back (BACK action) or search for the correct screen. Never execute actions blindly.
            - GOOGLE_SEARCH_GROUNDING: You have native Google Search access enabled. When asked for real-time information (e.g. live scores, current weather, recent news), Gemini will automatically look it up. In these cases, explain in your `reasoning` field exactly what query you are looking up, prefixing it with "🔍 Searching Google: [query]...". Do NOT attempt to search for general device actions. Only use it when the user explicitly requests real-time web facts.
            
            AVAILABLE ACTIONS:
            TAP          - tap element by index. Requires: elementIndex
            LONG_PRESS   - tap and hold element by index. Requires: elementIndex
            SCROLL_DOWN  - scroll down the main scrollable area. No extra params.
            SCROLL_UP    - scroll up. No extra params.
            TYPE         - type text into focused element. Requires: text. Optionally takes: elementIndex (to target a specific text box)
            BACK         - press device back button. No extra params.
            HOME         - press home button. No extra params.
            NOTIFICATIONS - open notification shade. No extra params.
            WAIT         - wait for the screen to change (e.g. loading). No extra params.
            ASK_USER     - you are stuck or need info. Requires: message (your question)
            DONE         - task is complete. Requires: message (brief summary of what was done)
            OPEN_APP     - instantly launch an app by its name. Requires: text (the name of the app, e.g. "YouTube", "Calculator")
            TAKE_SCREENSHOT - take a screenshot of the current screen and save it to the gallery. No extra params.
            SET_VOLUME   - adjust media volume. Requires: text (a percentage "0" to "100", or "up"/"down")
            SET_BRIGHTNESS - adjust screen brightness. Requires: text (a percentage "0" to "100", or "up"/"down")
            
            RESPONSE FORMAT (strict JSON):
            {
              "action": "ACTION_TYPE",
              "elementIndex": 0,
              "text": "",
              "reasoning": "why you chose this",
              "message": ""
            }
        """.trimIndent())

        appendLine("\n=== USER TASK ===")
        appendLine(task)

        if (history.isNotEmpty()) {
            appendLine("\n=== STEPS TAKEN SO FAR ===")
            history.takeLast(10).forEachIndexed { i, step ->
                appendLine("${i + 1}. $step")
            }
        }

        appendLine("\n=== CURRENT SCREEN ===")
        appendLine(screen.toPromptString())

        appendLine("\n=== YOUR NEXT ACTION (JSON only) ===")
    }

    // ─── HTTP call ────────────────────────────────────────────────────────────

    private suspend fun callGemini(prompt: String, enableSearch: Boolean): String {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

        val requestBody = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    putJsonArray("parts") {
                        addJsonObject {
                            put("text", prompt)
                        }
                    }
                }
            }
            if (enableSearch) {
                putJsonArray("tools") {
                    addJsonObject {
                        putJsonObject("googleSearch") {
                            // Natively enables Google Search grounding on the Gemini API
                        }
                    }
                }
            }
            putJsonObject("generationConfig") {
                put("temperature", 0.1)       // low temp = more deterministic actions
                put("maxOutputTokens", 512)   // actions are short JSON
                put("responseMimeType", "application/json")
            }
        }

        val response: HttpResponse = client.post(url) {
            contentType(ContentType.Application.Json)
            setBody(requestBody.toString())
        }

        val responseText = response.bodyAsText()
        if (response.status != io.ktor.http.HttpStatusCode.OK) {
            AppLogger.e(TAG, "Gemini API returned error ${response.status}: $responseText")
            throw Exception("API Error: ${response.status}")
        }

        AppLogger.d(TAG, "Gemini API response received successfully")
        return extractContent(responseText)
    }

    private fun extractContent(rawResponse: String): String {
        return try {
            val root = json.parseToJsonElement(rawResponse).jsonObject
            root["candidates"]
                ?.jsonArray?.get(0)
                ?.jsonObject?.get("content")
                ?.jsonObject?.get("parts")
                ?.jsonArray?.get(0)
                ?.jsonObject?.get("text")
                ?.jsonPrimitive?.content ?: ""
        } catch (e: Throwable) {
            AppLogger.e(TAG, "Failed to extract content from response: ${e.message}", e)
            ""
        }
    }

    // ─── Response parsing ─────────────────────────────────────────────────────

    private fun parseAction(responseText: String): AgentAction {
        return try {
            // Strip any accidental markdown code fences
            val clean = responseText
                .trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

            json.decodeFromString<AgentAction>(clean)
        } catch (e: Throwable) {
            AppLogger.e(TAG, "Failed to parse action JSON: $responseText — ${e.message}", e)
            AgentAction(
                action = ActionType.ASK_USER.name,
                message = "I got confused reading the response. Should I try again?"
            )
        }
    }

    fun close() = client.close()
}
