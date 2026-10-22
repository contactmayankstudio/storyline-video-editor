package com.video.engine.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent storage for Storyline AI Copilot chat messages using SharedPreferences and JSON.
 * Retains conversation history across app sessions and supports clearing/deleting history.
 */
object AiChatHistoryStore {
    private const val PREFS_NAME = "storyline_ai_chat_history"
    private const val KEY_MESSAGES = "chat_messages_v1"
    private const val MAX_SAVED_MESSAGES = 100

    data class ChatMessage(
        val sender: String, // "user" or "assistant"
        val text: String,
        val isSuccess: Boolean = true,
        val timestamp: Long = System.currentTimeMillis(),
    )

    @Synchronized
    fun getHistory(context: Context): List<ChatMessage> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_MESSAGES, null) ?: return emptyList()
        val list = mutableListOf<ChatMessage>()
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.optJSONObject(i) ?: continue
                val sender = obj.optString("sender", "assistant")
                val text = obj.optString("text", "")
                val isSuccess = obj.optBoolean("isSuccess", true)
                val timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                if (text.isNotBlank()) {
                    list.add(ChatMessage(sender, text, isSuccess, timestamp))
                }
            }
        } catch (_: Throwable) {}
        return list
    }

    @Synchronized
    fun addMessage(context: Context, message: ChatMessage) {
        val existing = getHistory(context).toMutableList()
        existing.add(message)
        if (existing.size > MAX_SAVED_MESSAGES) {
            existing.removeAt(0)
        }
        saveHistory(context, existing)
    }

    @Synchronized
    fun updateLastAssistantMessage(context: Context, newText: String, isSuccess: Boolean = true) {
        val existing = getHistory(context).toMutableList()
        val lastAssistantIdx = existing.indexOfLast { it.sender == "assistant" }
        if (lastAssistantIdx >= 0) {
            val old = existing[lastAssistantIdx]
            existing[lastAssistantIdx] = old.copy(text = newText, isSuccess = isSuccess)
            saveHistory(context, existing)
        } else {
            addMessage(context, ChatMessage("assistant", newText, isSuccess))
        }
    }

    @Synchronized
    fun clearHistory(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_MESSAGES).apply()
    }

    private fun saveHistory(context: Context, list: List<ChatMessage>) {
        try {
            val array = JSONArray()
            for (item in list) {
                val obj = JSONObject().apply {
                    put("sender", item.sender)
                    put("text", item.text)
                    put("isSuccess", item.isSuccess)
                    put("timestamp", item.timestamp)
                }
                array.put(obj)
            }
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_MESSAGES, array.toString()).apply()
        } catch (_: Throwable) {}
    }
}
