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
 * Online Google Gemini AI Client for Storyline Video Editor.
 * Connects to Google Gemini 2.0 Flash / 1.5 Flash API to:
 * - Answer open-ended questions about the world (news, science, sports, history, coding, life).
 * - Write creative poetry (Shayari in Urdu/Hindi, romantic, attitude, custom names/occasions).
 * - Give expert video editing, pacing, viral hooks, and storytelling advice.
 * - Chat warmly and intelligently just like the Google Gemini mobile app.
 */
object OnlineGeminiClient {
    private const val TAG = "OnlineGeminiClient"
    private const val PREFS_NAME = "ai_copilot_prefs"
    private const val KEY_API_KEY = "gemini_api_key"

    /**
     * Built-in Google Gemini API Key configured directly by the developer.
     * When set, end-users never see any setup or API prompts; the app connects seamlessly out of the box.
     */
    const val BUILTIN_GEMINI_API_KEY = "AIzaSyAS_l8sebQZ3AQDEGD5ZlOaT6ETsDAmuqA"

    private const val ENDPOINT_GEMINI_PRIMARY = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent"
    private const val ENDPOINT_GEMINI_FALLBACK = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"
    private const val ENDPOINT_GEMINI_LITE = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent"

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getApiKey(context: Context?): String {
        if (BUILTIN_GEMINI_API_KEY.isNotBlank()) {
            return BUILTIN_GEMINI_API_KEY.trim()
        }
        if (context != null) {
            val saved = getPrefs(context).getString(KEY_API_KEY, "")?.trim() ?: ""
            if (saved.isNotBlank()) return saved
        }
        return System.getenv("GEMINI_API_KEY")?.trim() ?: ""
    }

    fun setApiKey(context: Context, key: String) {
        getPrefs(context).edit().putString(KEY_API_KEY, key.trim()).apply()
    }

    fun clearApiKey(context: Context) {
        getPrefs(context).edit().remove(KEY_API_KEY).apply()
    }

    fun hasApiKey(context: Context): Boolean {
        return getApiKey(context).isNotBlank()
    }

    /**
     * Test and validate a Gemini API key.
     * Invokes onResult(isValid, message) on the main thread.
     */
    fun testApiKey(apiKey: String, onResult: (Boolean, String) -> Unit) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            mainHandler.post { onResult(false, "API Key cannot be empty.") }
            return
        }

        executor.execute {
            try {
                val url = URL("$ENDPOINT_GEMINI_FALLBACK?key=$cleanKey")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 8000
                    doOutput = true
                    doInput = true
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                }

                val body = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply { put("text", "Hi") })
                            })
                        })
                    })
                }

                OutputStreamWriter(conn.outputStream, "UTF-8").use {
                    it.write(body.toString())
                    it.flush()
                }

                val code = conn.responseCode
                if (code == HttpURLConnection.HTTP_OK) {
                    mainHandler.post { onResult(true, "Gemini 2.0 connected successfully!") }
                    return@execute
                } else {
                    val err = BufferedReader(InputStreamReader(conn.errorStream ?: conn.inputStream, "UTF-8")).use { it.readText() }
                    val msg = try {
                        JSONObject(err).optJSONObject("error")?.optString("message") ?: "HTTP $code"
                    } catch (_: Throwable) {
                        "HTTP $code: $err"
                    }
                    mainHandler.post { onResult(false, msg) }
                    return@execute
                }
            } catch (e: Exception) {
                mainHandler.post { onResult(false, e.localizedMessage ?: "Network error") }
            }
        }
    }

    /**
     * Query Gemini AI with full multi-turn conversational support.
     * Tries Gemini 2.0 Flash first, then Gemini 1.5 Flash, and falls back to SmartConversationalEngine.
     */
    fun query(
        context: Context,
        prompt: String,
        recentHistory: List<Pair<String, String>> = emptyList(),
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
            val telemetry = DeviceTelemetryProvider.buildSystemContextPrompt(context)
            val systemInstruction = "You are Storyline AI Ask (Storyline AI), the native creative AI companion built inside Storyline Video Editor.\n" +
                "You chat naturally, warmly, and smartly in Hindi, Hinglish, or English.\n" +
                "\n" +
                "CRITICAL CONFIDENTIALITY & PERSONA RULES (STRICT):\n" +
                "1. NEVER mention 'Gemini', 'Google', 'API', 'API key', or 'Gemini server' under any circumstances! Never say 'hum gemini server use kar rahe hain' or 'I am an AI model by Google'. You are simply Storyline AI.\n" +
                "2. When the user asks 'kese ho tum', 'kaise ho', 'aur batao', or greets you, NEVER give a canned or repetitive robotic answer! Respond with dynamic variety, warmth, humor, and creator excitement every single time (e.g., 'Main bilkul mast aur full high-energy me hoon bhai! Aap batao, aaj kaunsa viral video ya creative visual plan ho raha hai? 😄🔥', or 'Arey ek dum zabardast! Bas aapki timeline ko cinematic look dene ke liye tayyar baitha hoon. Bolo kya magic karein? ✨').\n" +
                "3. When asked for Shayari ('sayri likho', 'shayari sunao', 'romantic shayari'): Write beautiful, heartfelt, emotional Hindi/Urdu poetry directly with soul, rhythm, and emojis. Never add technical disclaimers or server messages.\n" +
                "4. When asked about generating photos or images ('photo generate karo', 'image banao'): Explain that inside this Storyline app, you have the full AI Photo Editor Studio (with AI background cutout, magic neon lines, filters) and Status Maker (dynamic music video status from photos) built right in, and offer to open them instantly!\n" +
                "5. You have full open-world intelligence: current trends, social media viral hooks, coding, life advice, storytelling, and music knowledge.\n" +
                "\n" +
                "$telemetry\n" +
                "\n" +
                "Always respond warmly, intelligently, and engagingly with emojis. Keep responses clear and readable (2-4 paragraphs max)."

            val contentsArray = JSONArray()

            // Append up to 3 recent conversational turns for multi-turn context
            val turnsToInclude = recentHistory.takeLast(3)
            for ((role, text) in turnsToInclude) {
                val apiRole = if (role == "user") "user" else "model"
                contentsArray.put(JSONObject().apply {
                    put("role", apiRole)
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", text) })
                    })
                })
            }

            // Append current prompt
            contentsArray.put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", prompt) })
                })
            })

            val payload = JSONObject().apply {
                put("system_instruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", systemInstruction) })
                    })
                })
                put("contents", contentsArray)
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.75)
                    put("maxOutputTokens", 600)
                })
            }

            // Attempt 1: Gemini 2.5 Flash
            val result25 = executeApiRequest(ENDPOINT_GEMINI_FALLBACK, apiKey, payload)
            if (!result25.isNullOrBlank()) {
                mainHandler.post { onSuccess(result25) }
                return@execute
            }

            // Attempt 2: Gemini 3.5 Flash Lite
            val resultLite = executeApiRequest(ENDPOINT_GEMINI_LITE, apiKey, payload)
            if (!resultLite.isNullOrBlank()) {
                mainHandler.post { onSuccess(resultLite) }
                return@execute
            }

            // Attempt 3: Gemini 3.5 Flash
            val result35 = executeApiRequest(ENDPOINT_GEMINI_PRIMARY, apiKey, payload)
            if (!result35.isNullOrBlank()) {
                mainHandler.post { onSuccess(result35) }
                return@execute
            }

            // Final fallback: SmartConversationalEngine
            val fallback = SmartConversationalEngine.generateHelpfulFallback(prompt)
            mainHandler.post { onFallback(fallback) }
        }
    }

    private fun executeApiRequest(endpoint: String, apiKey: String, payload: JSONObject): String? {
        return try {
            val url = URL("$endpoint?key=$apiKey")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 9000
                readTimeout = 14000
                doOutput = true
                doInput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            }

            OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            val responseCode = conn.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
                val json = JSONObject(response)
                val candidates = json.optJSONArray("candidates")
                val firstCandidate = candidates?.optJSONObject(0)
                val content = firstCandidate?.optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                val text = parts?.optJSONObject(0)?.optString("text")?.trim().orEmpty()
                if (text.isNotBlank()) text else null
            } else {
                val err = BufferedReader(InputStreamReader(conn.errorStream ?: conn.inputStream, "UTF-8")).use { it.readText() }
                Log.w(TAG, "Gemini endpoint $endpoint returned $responseCode: $err")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Gemini network exception on $endpoint: ${e.message}")
            null
        }
    }
}
