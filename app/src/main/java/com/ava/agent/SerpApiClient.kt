package com.ava.agent

import com.ava.util.AppLogger
import io.ktor.client.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.*
import java.net.URLEncoder

private const val TAG = "AVA:SerpApiClient"

class SerpApiClient {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = HttpClient(Android) {
        install(ContentNegotiation) {
            json(json)
        }
    }

    suspend fun searchGoogle(query: String, apiKey: String): String {
        if (apiKey.isBlank()) return ""
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "https://serpapi.com/search.json?engine=google&q=$encodedQuery&api_key=$apiKey"
            AppLogger.d(TAG, "Querying SerpAPI for query: \"$query\"")

            val response: HttpResponse = client.get(url)
            val responseText = response.bodyAsText()
            if (response.status != HttpStatusCode.OK) {
                AppLogger.e(TAG, "SerpAPI error status ${response.status}: $responseText")
                return ""
            }

            parseSearchResponse(responseText)
        } catch (e: Throwable) {
            AppLogger.e(TAG, "SerpAPI search failed: ${e.message}", e)
            ""
        }
    }

    private fun parseSearchResponse(rawResponse: String): String {
        return try {
            val root = json.parseToJsonElement(rawResponse).jsonObject
            
            // 1. Try to extract answer box (Google direct answer)
            val answerBox = root["answer_box"]?.jsonObject
            if (answerBox != null) {
                val answer = answerBox["answer"]?.jsonPrimitive?.content
                    ?: answerBox["snippet"]?.jsonPrimitive?.content
                    ?: answerBox["result"]?.jsonPrimitive?.content
                if (!answer.isNullOrBlank()) {
                    return "Direct Answer: $answer"
                }
            }

            // 2. Try to extract sports results
            val sportsResults = root["sports_results"]?.jsonObject
            if (sportsResults != null) {
                val gameSpotlight = sportsResults["game_spotlight"]?.jsonObject
                if (gameSpotlight != null) {
                    val teams = gameSpotlight["teams"]?.jsonArray
                    val stage = gameSpotlight["stage"]?.jsonPrimitive?.content ?: ""
                    val status = gameSpotlight["status"]?.jsonPrimitive?.content ?: ""
                    if (teams != null && teams.size >= 2) {
                        val team1 = teams[0].jsonObject["name"]?.jsonPrimitive?.content ?: ""
                        val score1 = teams[0].jsonObject["score"]?.jsonPrimitive?.content ?: ""
                        val team2 = teams[1].jsonObject["name"]?.jsonPrimitive?.content ?: ""
                        val score2 = teams[1].jsonObject["score"]?.jsonPrimitive?.content ?: ""
                        return "Sports Spotlight: $team1 ($score1) vs $team2 ($score2) - $stage $status"
                    }
                }
            }

            // 3. Fallback: Parse organic search results (top 3 snippets)
            val organic = root["organic_results"]?.jsonArray
            if (organic != null && organic.isNotEmpty()) {
                val resultsBuilder = StringBuilder()
                val limit = minOf(organic.size, 3)
                for (i in 0 until limit) {
                    val item = organic[i].jsonObject
                    val title = item["title"]?.jsonPrimitive?.content ?: ""
                    val snippet = item["snippet"]?.jsonPrimitive?.content ?: ""
                    resultsBuilder.appendLine("- Title: $title\n  Snippet: $snippet")
                }
                resultsBuilder.toString().trim()
            } else {
                ""
            }
        } catch (e: Throwable) {
            AppLogger.e(TAG, "Failed to parse SerpAPI response: ${e.message}")
            ""
        }
    }

    fun close() = client.close()
}
