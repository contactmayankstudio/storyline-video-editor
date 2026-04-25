package com.video.engine

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

class RemoteCommandManager(
    private val installationIdProvider: () -> String,
    private val sessionIdProvider: () -> String,
    private val onExecute: (command: String, commandId: String) -> Pair<Boolean, String>,
) {
    companion object {
        private const val TAG = "[RemoteCmd]"
        private const val POLL_INTERVAL_MS = 3_500L
    }

    private val firestore = FirebaseFirestore.getInstance()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val inFlightCommandIds = mutableSetOf<String>()
    private var active = false
    private val pollRunnable =
        object : Runnable {
            override fun run() {
                if (!active) return
                pollLatestCommand()
                mainHandler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }

    fun start() {
        if (active) return
        val installationId = installationIdProvider().trim()
        if (installationId.isEmpty()) {
            Log.w(TAG, "Skipping remote command listener: missing installation id")
            return
        }
        active = true
        mainHandler.post(pollRunnable)
    }

    fun close() {
        active = false
        mainHandler.removeCallbacks(pollRunnable)
        inFlightCommandIds.clear()
    }

    private fun pollLatestCommand() {
        val installationId = installationIdProvider().trim()
        if (installationId.isEmpty()) return
        firestore.collection("ops_device_channels")
            .document(installationId)
            .get()
            .addOnSuccessListener { document ->
                if (!document.exists()) return@addOnSuccessListener
                val status = document.getString("status").orEmpty().trim()
                val commandId = document.getString("commandId").orEmpty().trim()
                val command = document.getString("command").orEmpty().trim()
                if (status != "queued" || commandId.isEmpty() || command.isEmpty()) return@addOnSuccessListener
                if (!inFlightCommandIds.add(commandId)) return@addOnSuccessListener
                Log.d(TAG, "Queued phone command detected id=$commandId command=$command")
                handleCommand(installationId, commandId, command)
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "Remote command poll failed: ${error.message}")
            }
    }

    private fun handleCommand(installationId: String, commandId: String, command: String) {
        markStatus(
            installationId = installationId,
            commandId = commandId,
            status = "running",
            resultMessage = "Dispatching $command",
            includeCompletedAt = false,
        )

        mainHandler.post {
            val (success, resultMessage) =
                runCatching { onExecute(command, commandId) }
                    .getOrElse { error ->
                        false to (error.message ?: "Command execution failed")
                    }

            markStatus(
                installationId = installationId,
                commandId = commandId,
                status = if (success) "completed" else "failed",
                resultMessage = resultMessage,
                includeCompletedAt = true,
            )
            inFlightCommandIds.remove(commandId)
        }
    }

    private fun markStatus(
        installationId: String,
        commandId: String,
        status: String,
        resultMessage: String,
        includeCompletedAt: Boolean,
    ) {
        val now = System.currentTimeMillis()
        val payload = hashMapOf<String, Any>(
            "status" to status,
            "updatedAt" to FieldValue.serverTimestamp(),
            "updatedAtMs" to now,
            "handledBySessionId" to sessionIdProvider().ifBlank { "unknown" },
            "handledBySource" to "android-client",
            "resultMessage" to resultMessage.take(240),
        )
        if (includeCompletedAt) {
            payload["completedAt"] = FieldValue.serverTimestamp()
        }

        firestore.collection("ops_device_channels")
            .document(installationId)
            .set(payload, SetOptions.merge())
            .addOnFailureListener { error ->
                Log.w(TAG, "Failed to update command $commandId: ${error.message}")
            }
    }
}
