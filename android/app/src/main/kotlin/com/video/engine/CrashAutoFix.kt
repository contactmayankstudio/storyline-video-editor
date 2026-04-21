package com.video.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object CrashAutoFix {
    private const val TAG = "CrashAutoFix"
    private const val API_URL = "https://crash-fix-server.vercel.app/api/analyze"
    private const val PREFS = "crash_fix_queue"
    private const val KEY_QUEUE = "pending_crashes"

    // Crash report karo — quota nahi hai to queue mein save ho, baad mein retry
    fun reportCrash(context: Context, throwable: Throwable) {
        val stackTrace = throwable.stackTraceToString()
        CoroutineScope(Dispatchers.IO).launch {
            val sent = sendToApi(stackTrace)
            if (!sent) {
                saveToQueue(context, stackTrace)
                Log.d(TAG, "Queued for retry")
            }
        }
    }

    // App start hone pe pending crashes retry karo
    fun retryPending(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            val queue = loadQueue(context).toMutableList()
            if (queue.isEmpty()) return@launch
            Log.d(TAG, "Retrying ${queue.size} pending crashes")
            val remaining = mutableListOf<String>()
            for (stackTrace in queue) {
                if (!sendToApi(stackTrace)) remaining.add(stackTrace)
            }
            saveQueue(context, remaining)
        }
    }

    private suspend fun sendToApi(stackTrace: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().put("stackTrace", stackTrace).toString()
                val conn = URL(API_URL).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 10000
                conn.doOutput = true
                conn.outputStream.write(body.toByteArray())
                val code = conn.responseCode
                val response = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                Log.d(TAG, "Fix: $response")
                code == 200  // 202 = queued on server side, treat as retry needed
            } catch (e: Exception) {
                Log.w(TAG, "API call failed: ${e.message}")
                false
            }
        }
    }

    private fun saveToQueue(context: Context, stackTrace: String) {
        val queue = loadQueue(context).toMutableList()
        queue.add(stackTrace)
        saveQueue(context, queue)
    }

    private fun loadQueue(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_QUEUE, "[]") ?: "[]"
        return (0 until JSONArray(json).length()).map { JSONArray(json).getString(it) }
    }

    private fun saveQueue(context: Context, queue: List<String>) {
        val arr = JSONArray().apply { queue.forEach { put(it) } }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_QUEUE, arr.toString()).apply()
    }
}
