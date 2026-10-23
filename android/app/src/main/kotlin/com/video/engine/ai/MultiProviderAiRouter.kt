package com.video.engine.ai

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
 * Multi-Provider AI Cascade Router with Automatic Quota-Exhaustion Fallback.
 * Seamlessly rotates between:
 * 1. Groq (qwen/qwen3.8-27b - Blazing fast, ~80ms)
 * 2. Google Gemini (gemini-2.5-flash - Deep world knowledge & reasoning)
 * 3. Cerebras (qwen-3.8-27b - High throughput backup)
 * 4. Mistral (mistral-small-latest - Secondary backup)
 * 5. SmartConversationalEngine (Offline built-in local brain - 0ms, zero network)
 *
 * The user NEVER sees provider names, quota errors, or model switches.
 */
object MultiProviderAiRouter {
    private const val TAG = "MultiAiRouter"

    private val KEY_GROQ get() = System.getenv("GROQ_API_KEY") ?: ""
    private val KEY_GEMINI get() = System.getenv("GEMINI_API_KEY") ?: ""
    private val KEY_CEREBRAS get() = System.getenv("CEREBRAS_API_KEY") ?: ""
    private val KEY_MISTRAL get() = System.getenv("MISTRAL_API_KEY") ?: ""

    private const val SYSTEM_PROMPT =
        "You are Storyline AI Editor, a top-tier professional video editor and creative co-creator. " +
        "You chat naturally and warmly in fluent Hinglish (or English if the user speaks English), just like a seasoned human film editor sitting next to the creator. " +
        "Understand user intents directly from natural conversation — NEVER ask users to type commands or syntax. " +
        "Answer questions about storytelling, ideas, editing, pacing, music, or anything else warmly and concisely in 2-4 sentences. " +
        "Never mention AI models, keys, or technical architecture. Always act as an intuitive, experienced human editor colleague."

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Query AI with automatic multi-tier fallback and Antigravity Device Telemetry.
     */
    fun query(
        prompt: String,
        context: android.content.Context? = null,
        onResponse: (String) -> Unit,
    ) {
        val systemPrompt = if (context != null) {
            val telemetry = DeviceTelemetryProvider.buildSystemContextPrompt(context)
            "$SYSTEM_PROMPT\n\n$telemetry"
        } else {
            SYSTEM_PROMPT
        }

        executor.execute {
            // Tier 0: Fast offline conversational intent check
            val conv = SmartConversationalEngine.process(prompt)
            if (conv != null) {
                val reply = if (conv.intent is VideoEditIntent.Conversational) {
                    (conv.intent as VideoEditIntent.Conversational).reply
                } else {
                    conv.feedbackMessage
                }
                mainHandler.post { onResponse(reply) }
                return@execute
            }

            // Tier 1: Groq (Fastest)
            if (KEY_GROQ.isNotBlank()) {
                tryGroq(prompt, systemPrompt)?.let { reply ->
                    mainHandler.post { onResponse(reply) }
                    return@execute
                }
            }

            // Tier 2: Gemini (Deep Knowledge)
            if (KEY_GEMINI.isNotBlank()) {
                tryGemini(prompt, systemPrompt)?.let { reply ->
                    mainHandler.post { onResponse(reply) }
                    return@execute
                }
            }

            // Tier 3: Cerebras
            if (KEY_CEREBRAS.isNotBlank()) {
                tryCerebras(prompt, systemPrompt)?.let { reply ->
                    mainHandler.post { onResponse(reply) }
                    return@execute
                }
            }

            // Tier 4: Mistral
            if (KEY_MISTRAL.isNotBlank()) {
                tryMistral(prompt, systemPrompt)?.let { reply ->
                    mainHandler.post { onResponse(reply) }
                    return@execute
                }
            }

            // Tier 5: Built-in Offline Brain (Instant 0ms)
            val offlineReply = SmartConversationalEngine.generateHelpfulFallback(prompt)
            mainHandler.post { onResponse(offlineReply) }
        }
    }

    fun query(
        prompt: String,
        onResponse: (String) -> Unit,
    ) = query(prompt, null, onResponse)

    private fun tryGroq(prompt: String, systemPrompt: String): String? {
        return try {
            val url = URL("https://api.groq.com/openai/v1/chat/completions")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 6000
                readTimeout = 8000
                doOutput = true
                doInput = true
                setRequestProperty("Authorization", "Bearer $KEY_GROQ")
                setRequestProperty("Content-Type", "application/json")
            }

            val body = JSONObject().apply {
                put("model", "qwen/qwen3.8-27b")
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
                put("temperature", 0.7)
                put("max_tokens", 350)
            }

            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body.toString()); it.flush() }

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val resp = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
                val json = JSONObject(resp)
                val content = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")?.trim()
                if (!content.isNullOrBlank()) {
                    Log.d(TAG, "Answered via Tier 1 (Groq)")
                    return content
                }
            } else {
                Log.w(TAG, "Tier 1 Groq returned ${conn.responseCode}, switching to next tier...")
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Tier 1 Groq exception: ${e.message}, switching...")
            null
        }
    }

    private fun tryGemini(prompt: String, systemPrompt: String): String? {
        return try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$KEY_GEMINI")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 12000
                doOutput = true
                doInput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            }

            val body = JSONObject().apply {
                put("system_instruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", systemPrompt) })
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
                    put("maxOutputTokens", 350)
                })
            }

            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body.toString()); it.flush() }

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val resp = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
                val json = JSONObject(resp)
                val candidates = json.optJSONArray("candidates")
                val text = candidates?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text")?.trim()
                if (!text.isNullOrBlank()) {
                    Log.d(TAG, "Answered via Tier 2 (Gemini)")
                    return text
                }
            } else {
                Log.w(TAG, "Tier 2 Gemini returned ${conn.responseCode}, switching to next tier...")
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Tier 2 Gemini exception: ${e.message}, switching...")
            null
        }
    }

    private fun tryCerebras(prompt: String, systemPrompt: String): String? {
        return try {
            val url = URL("https://api.cerebras.ai/v1/chat/completions")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 6000
                readTimeout = 8000
                doOutput = true
                doInput = true
                setRequestProperty("Authorization", "Bearer $KEY_CEREBRAS")
                setRequestProperty("Content-Type", "application/json")
            }

            val body = JSONObject().apply {
                put("model", "qwen-3.8-27b")
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
                put("temperature", 0.7)
                put("max_tokens", 350)
            }

            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body.toString()); it.flush() }

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val resp = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
                val json = JSONObject(resp)
                val content = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")?.trim()
                if (!content.isNullOrBlank()) {
                    Log.d(TAG, "Answered via Tier 3 (Cerebras)")
                    return content
                }
            } else {
                Log.w(TAG, "Tier 3 Cerebras returned ${conn.responseCode}, switching to next tier...")
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Tier 3 Cerebras exception: ${e.message}, switching...")
            null
        }
    }

    private fun tryMistral(prompt: String, systemPrompt: String): String? {
        return try {
            val url = URL("https://api.mistral.ai/v1/chat/completions")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 6000
                readTimeout = 8000
                doOutput = true
                doInput = true
                setRequestProperty("Authorization", "Bearer $KEY_MISTRAL")
                setRequestProperty("Content-Type", "application/json")
            }

            val body = JSONObject().apply {
                put("model", "mistral-small-latest")
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
                put("temperature", 0.7)
                put("max_tokens", 350)
            }

            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body.toString()); it.flush() }

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val resp = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
                val json = JSONObject(resp)
                val content = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")?.trim()
                if (!content.isNullOrBlank()) {
                    Log.d(TAG, "Answered via Tier 4 (Mistral)")
                    return content
                }
            } else {
                Log.w(TAG, "Tier 4 Mistral returned ${conn.responseCode}, switching to next tier...")
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Tier 4 Mistral exception: ${e.message}, switching...")
            null
        }
    }
}
