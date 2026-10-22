package com.video.engine.ai

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Online Gemini AI Client for Storyline Video Editor.
 * Connects to Google Gemini 1.5 Flash API to answer open-ended questions about the world,
 * write scripts, provide creative ideas, and offer expert video editing advice.
 */
object OnlineGeminiClient {
    private const val TAG = "OnlineGeminiClient"
    private const val PREFS_NAME = "ai_copilot_prefs"
    private const val KEY_API_KEY = "gemini_api_key"
    private const val GEMINI_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getApiKey(context: Context): String {
        return getPrefs(context).getString(KEY_API_KEY, "") ?: ""
    }

    fun setApiKey(context: Context, key: String) {
        getPrefs(context).edit().putString(KEY_API_KEY, key.trim()).apply()
    }

    fun hasApiKey(context: Context): Boolean {
        return getApiKey(context).isNotBlank()
    }

    /**
     * Ask Gemini AI a question asynchronously.
     * If an API key is available and network succeeds, returns Gemini's answer.
     * Otherwise, invokes onFallback with SmartConversationalEngine's response.
     */
    fun query(
        context: Context,
        prompt: String,
        onSuccess: (String) -> Unit,
        onFallback: (String) -> Unit,
    ) {
        val apiKey = getApiKey(context)
        if (apiKey.isBlank()) {
            val fallback = SmartConversationalEngine.generateHelpfulFallback(prompt)
            onFallback(fallback)
            return
        }

        executor.execute {
            try {
                val url = URL("$GEMINI_ENDPOINT?key=$apiKey")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10000
                    readTimeout = 15000
                    doOutput = true
                    doInput = true
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                }

                // Construct Gemini API Payload
                val systemInstruction = "You are Storyline AI Copilot, a brilliant, friendly, human-like AI video editing assistant and world-knowledge expert. You speak natural, warm Hinglish (Hindi written in English letters) with video creators. When users ask general questions (news, world events, science, storytelling, scripts, ideas), answer intelligently, concisely, and accurately in 2-4 sentences. If they ask about video editing, guide them and suggest actions like 'cut clip', 'remove silence', 'teal and orange', 'speed 2x', etc."

                val body = JSONObject().apply {
                    put("system_instruction", JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", systemInstruction) })
                        })
                    })
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply { put("text", prompt) })
                            })
                        })
                    })
                    put("generationConfig", JSONObject().apply {
                        put("temperature", 0.7)
                        put("maxOutputTokens", 300)
                    })
                }

                OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
                    writer.write(body.toString())
                    writer.flush()
                }

                val responseCode = conn.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val response = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { reader ->
                        reader.readText()
                    }
                    val json = JSONObject(response)
                    val candidates = json.optJSONArray("candidates")
                    val firstCandidate = candidates?.optJSONObject(0)
                    val content = firstCandidate?.optJSONObject("content")
                    val parts = content?.optJSONArray("parts")
                    val text = parts?.optJSONObject(0)?.optString("text")?.trim().orEmpty()

                    if (text.isNotBlank()) {
                        mainHandler.post { onSuccess(text) }
                        return@execute
                    }
                } else {
                    val err = BufferedReader(InputStreamReader(conn.errorStream ?: conn.inputStream, "UTF-8")).use {
                        it.readText()
                    }
                    Log.e(TAG, "Gemini API error ($responseCode): $err")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Network error querying Gemini: ${e.message}", e)
            }

            // Fallback to SmartConversationalEngine on failure
            val fallback = SmartConversationalEngine.generateHelpfulFallback(prompt)
            mainHandler.post { onFallback(fallback) }
        }
    }
}
