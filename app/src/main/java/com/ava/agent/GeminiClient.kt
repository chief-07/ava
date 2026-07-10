package com.ava.agent

import android.util.Log
import io.ktor.client.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

private const val TAG = "AVA:GeminiClient"

/**
 * GeminiClient sends screen context + task to Gemini Flash and
 * returns a parsed AgentAction.
 *
 * Uses Gemini's generateContent REST API via Ktor.
 */
class GeminiClient(private val apiKey: String) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = HttpClient(Android) {
        install(ContentNegotiation) {
            json(json)
        }
        install(Logging) {
            logger = object : Logger {
                override fun log(message: String) {
                    Log.v(TAG, message)
                }
            }
            level = LogLevel.NONE // set to BODY for debugging
        }
    }

    private val model = "gemini-2.0-flash" // update to latest available free model

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

        return try {
            val responseText = callGemini(prompt)
            parseAction(responseText)
        } catch (e: Exception) {
            Log.e(TAG, "Gemini call failed: ${e.message}")
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
            
            AVAILABLE ACTIONS:
            TAP          - tap element by index. Requires: elementIndex
            SCROLL_DOWN  - scroll down the main scrollable area. No extra params.
            SCROLL_UP    - scroll up. No extra params.
            TYPE         - type text into focused element. Requires: text
            BACK         - press device back button. No extra params.
            HOME         - press home button. No extra params.
            NOTIFICATIONS - open notification shade. No extra params.
            WAIT         - wait for the screen to change (e.g. loading). No extra params.
            ASK_USER     - you are stuck or need info. Requires: message (your question)
            DONE         - task is complete. Requires: message (brief summary of what was done)
            OPEN_APP     - instantly launch an app by its name. Requires: text (the name of the app, e.g. "YouTube", "Calculator")
            
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

    private suspend fun callGemini(prompt: String): String {
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
        Log.d(TAG, "Raw response: $responseText")
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract content from response: ${e.message}")
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse action JSON: $responseText — ${e.message}")
            AgentAction(
                action = ActionType.ASK_USER.name,
                message = "I got confused reading the response. Should I try again?"
            )
        }
    }

    fun close() = client.close()
}
