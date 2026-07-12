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
private const val MAX_CONVERSATION_TURNS = 8 // keep the last N full turns in memory

/**
 * GeminiClient sends screen context + task to Gemini Flash and
 * returns a parsed AgentAction.
 *
 * Uses Gemini's generateContent REST API via Ktor with multi-turn
 * conversation memory so the LLM retains awareness of past screens.
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

    // ─── Multi-turn conversation memory ──────────────────────────────────────

    private val conversationHistory = mutableListOf<JsonObject>()

    /** Reset conversation memory. Call when starting a new task. */
    fun resetConversation() {
        conversationHistory.clear()
        AppLogger.d(TAG, "Conversation memory cleared for new task")
    }

    /** Determine if a query specifically benefits from real-time external info (e.g. weather, news, scores) */
    fun requiresRealTimeSearch(task: String): Boolean {
        val lowercase = task.lowercase()
        val keywords = listOf(
            "weather", "temperature", "temp", "forecast", "rain", "snow", "humidity", "wind",
            "score", "match", "game", "play", "sports", "team", "won", "lose", "victory",
            "stock", "price", "current", "live", "today", "yesterday", "news", "latest", "rate",
            "time", "dollar", "crypto", "bitcoin", "currency", "exchange"
        )
        return keywords.any { lowercase.contains(it) }
    }

    // ─── Main method ─────────────────────────────────────────────────────────

    /**
     * Ask Gemini what action to take next given the task and screen context.
     * Maintains multi-turn conversation memory for screen awareness.
     */
    suspend fun decideNextAction(
        task: String,
        screenContext: ScreenContext,
        stepHistory: List<String>,
        searchResults: String = ""
    ): AgentAction {
        val userMessage = buildUserMessage(task, screenContext, stepHistory, searchResults)

        return try {
            val responseText = callGemini(userMessage)
            val action = parseAction(responseText)

            // Store this turn in conversation history for future context
            conversationHistory.add(buildJsonObject {
                put("role", "user")
                putJsonArray("parts") {
                    addJsonObject { put("text", userMessage) }
                }
            })
            conversationHistory.add(buildJsonObject {
                put("role", "model")
                putJsonArray("parts") {
                    addJsonObject { put("text", responseText) }
                }
            })

            // Trim conversation to last N turns (each turn = 2 entries)
            val maxEntries = MAX_CONVERSATION_TURNS * 2
            while (conversationHistory.size > maxEntries) {
                conversationHistory.removeAt(0)
            }

            action
        } catch (e: Throwable) {
            AppLogger.e(TAG, "Gemini call failed: ${e.message}", e)
            AgentAction(
                action = ActionType.ASK_USER.name,
                message = "I ran into an error: ${e.message}. Should I try again?"
            )
        }
    }

    // ─── System prompt ───────────────────────────────────────────────────────

    private fun buildSystemPrompt(): String = """
        You are AVA, an AI agent controlling an Android phone on behalf of the user.
        Your job is to complete the user's task by deciding one action at a time.
        
        RULES:
        - Always respond with ONLY valid JSON — no markdown, no explanation outside JSON.
        - Choose the most logical next action based on the current screen.
        - If the task is complete, respond with action "DONE".
        - If you are stuck or need user input, respond with action "ASK_USER".
        - Never perform irreversible actions (delete, send, purchase) without noting it in reasoning.
        - element indexes start at 0 and match the ELEMENTS list exactly.
        - Elements are indented to show hierarchy. Deeper indentation = nested inside a parent container.
        - Elements tagged (checked), (selected), or (focused) indicate their current toggle/selection/focus state.
        
        COGNITIVE & NAVIGATION GUIDELINES:
        - ANTI-REPETITION: Look at the "STEPS TAKEN SO FAR" history. If an action you tried previously resulted in no screen change, did not work, or was ineffective, DO NOT repeat the exact same action or tap the same element index. Try a different element, a different approach, or scroll.
        - DIRECT KNOWLEDGE RESPONSES: If the user's task is a general question, query, fact request, or translation (e.g., "who is a pediatrician", "who is a doctor", "what is photosynthesis", "translate X to French") that can be answered using your internal general knowledge, OR if the prompt contains grounding information under "=== GOOGLE SEARCH RESULTS ===" that answers the user's query (e.g., weather, sports scores, stocks), you must NOT interact with the phone screen or open any apps. Instead, immediately return action "DONE" and provide the complete, detailed answer in the "message" field. Only perform actions on the screen if the user explicitly requests device automation (e.g., "open WhatsApp", "search for cat videos on YouTube", "message Mom"). If the task is a compound task containing BOTH general questions/fact requests AND device automation requests (e.g., "who is the president of France and then open YouTube"), you must FIRST perform all required device automation actions on the screen. Once all screen automation is complete, you must then return action "DONE" and include the answer to the general question/fact request in the "message" field along with a summary of the actions taken. Do NOT return DONE on step 1 if there are screen automation steps remaining.
        - TASK TERMINATION SENSITIVITY: If the user's task is a search (e.g. "search for X on YouTube" or "find Y"), and the current screen already shows the search results, profile page, or target page, the task is 100% COMPLETE. You must return action "DONE" immediately. Do NOT repeat the search or tap search results again.
        - ON-PAGE ENGAGEMENT FOCUS: Once you successfully navigate to a website or open a target app screen, focus your actions entirely on the page contents/elements (such as links, buttons, or inputs on the page itself). Do NOT click back on the browser address bar or search bar to search again unless the page is completely empty, incorrect, or broken. Always search within the page itself: tap the website's search box, click a search icon, open the hamburger menu drawer, or default to searching via google.com and clicking the result.
        - BACKTRACKING RECOVERY: If you tap a link or button and it opens a wrong page, advertisement, or dead-end, immediately execute the "BACK" action to return to the previous screen. Do not try to proceed on an incorrect page.
        - STAY ON TARGET WEBSITE: Do not type into Chrome's top address/URL bar unless you need to navigate to a completely new website. If you are already on the correct website (e.g. to download something), look for buttons, hamburger menus, three-dot menus, or download links within the page itself.
        - SCREEN INTUITION & LLM KNOWLEDGE: Use your general knowledge as an LLM to interpret screen elements. For example, infer what unlabeled icons represent, read text context to identify forms or status changes, and use your reasoning to decide what element is most likely to contain the content you need.
        - TYPE THEN SUBMIT: After using the TYPE action to enter text into a search bar or form field, you must ALWAYS follow up with either (a) the ENTER action to press the keyboard's submit button, or (b) TAP on a visible Search/Go/Send button in the UI. Typing alone does NOT submit the text.
        - SELECTION STATE VERIFICATION: When executing multi-step actions (such as long-pressing to select an item), ALWAYS verify in the current SCREEN/ELEMENTS list that the selection state has successfully registered (e.g. look for selection indicators, checkboxes, trash can icons, or header text like "1 selected") BEFORE tapping follow-up menu buttons like "More options". If the selection state is not yet visible, use the WAIT action to let the screen update.
        - CONTEXT_VALIDATION: Always verify that the current screen, page header, active website URL, or app context matches the target of your task BEFORE performing actions. Never execute actions blindly.
        - RECIPIENT & CHAT VERIFICATION: Before typing or sending a message in a messaging app (e.g., WhatsApp, SMS, Telegram), you MUST verify that the active chat window header (usually a contact name or phone number) matches the target contact's name exactly. If there is an already open chat but it does NOT match the target name, do not type or send. You must tap the back button to return to the chat list, or tap search, type the target name, and tap the correct contact to open the correct chat. Never assume the currently opened chat is correct.
        
        AVAILABLE ACTIONS:
        TAP          - tap element by index. Requires: elementIndex
        LONG_PRESS   - tap and hold element by index. Requires: elementIndex
        SCROLL_DOWN  - scroll down the main scrollable area. No extra params.
        SCROLL_UP    - scroll up. No extra params.
        SWIPE        - swipe in a direction. Requires: text ("up", "down", "left", "right")
        TYPE         - type text into focused element. Requires: text. Optionally takes: elementIndex (to target a specific text box)
        ENTER        - press the keyboard's Enter/Search/Go/Send button to submit typed text. No extra params.
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
    """.trimIndent()

    // ─── User message construction ───────────────────────────────────────────

    private fun buildUserMessage(
        task: String,
        screen: ScreenContext,
        history: List<String>,
        searchResults: String
    ): String = buildString {
        appendLine("=== USER TASK ===")
        appendLine(task)

        if (searchResults.isNotBlank()) {
            appendLine("\n=== GOOGLE SEARCH RESULTS ===")
            appendLine(searchResults)
        }

        if (history.isNotEmpty()) {
            appendLine("\n=== STEPS TAKEN SO FAR ===")
            history.takeLast(20).forEachIndexed { i, step ->
                appendLine("${i + 1}. $step")
            }
        }

        appendLine("\n=== CURRENT SCREEN ===")
        appendLine(screen.toPromptString())

        appendLine("\n=== YOUR NEXT ACTION (JSON only) ===")
    }

    // ─── HTTP call ────────────────────────────────────────────────────────────

    private suspend fun callGemini(userMessage: String): String {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

        val requestBody = buildJsonObject {
            // System instruction — separated from conversation turns
            putJsonObject("systemInstruction") {
                putJsonArray("parts") {
                    addJsonObject { put("text", buildSystemPrompt()) }
                }
            }

            // Multi-turn conversation contents
            putJsonArray("contents") {
                // Include past conversation turns for memory
                conversationHistory.forEach { turn -> add(turn) }

                // Current user message
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        addJsonObject { put("text", userMessage) }
                    }
                }
            }



            putJsonObject("generationConfig") {
                put("temperature", 0.1)       // low temp = more deterministic actions
                put("maxOutputTokens", 512)   // actions are short JSON
                put("responseMimeType", "application/json")
                putJsonObject("responseSchema") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("action") { put("type", "STRING") }
                        putJsonObject("elementIndex") { put("type", "INTEGER") }
                        putJsonObject("text") { put("type", "STRING") }
                        putJsonObject("reasoning") { put("type", "STRING") }
                        putJsonObject("message") { put("type", "STRING") }
                    }
                    putJsonArray("required") {
                        add(JsonPrimitive("action"))
                        add(JsonPrimitive("reasoning"))
                    }
                }
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
