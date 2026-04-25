package com.video.engine

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions

class RemoteCommandManager(
    private val installationIdProvider: () -> String,
    private val sessionIdProvider: () -> String,
    private val onExecute: (command: String, commandId: String) -> Pair<Boolean, String>,
) {
    companion object {
        private const val TAG = "[RemoteCmd]"
    }

    private val firestore = FirebaseFirestore.getInstance()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var registration: ListenerRegistration? = null
    private val inFlightCommandIds = mutableSetOf<String>()

    fun start() {
        if (registration != null) return
        val installationId = installationIdProvider().trim()
        if (installationId.isEmpty()) {
            Log.w(TAG, "Skipping remote command listener: missing installation id")
            return
        }

        registration =
            firestore.collection("ops_installations")
                .document(installationId)
                .collection("commands")
                .whereEqualTo("status", "queued")
                .limit(12)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "Remote command listener failed: ${error.message}")
                        return@addSnapshotListener
                    }
                    val docs =
                        snapshot?.documents
                            ?.sortedBy { document ->
                                document.getLong("createdAtMs") ?: 0L
                            }
                            ?: return@addSnapshotListener
                    for (document in docs) {
                        val commandId = document.id
                        val command = document.getString("command").orEmpty().trim()
                        if (command.isEmpty()) continue
                        if (!inFlightCommandIds.add(commandId)) continue
                        handleCommand(installationId, commandId, command)
                    }
                }
    }

    fun close() {
        registration?.remove()
        registration = null
        inFlightCommandIds.clear()
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

        firestore.collection("ops_installations")
            .document(installationId)
            .collection("commands")
            .document(commandId)
            .set(payload, SetOptions.merge())
            .addOnFailureListener { error ->
                Log.w(TAG, "Failed to update command $commandId: ${error.message}")
            }
    }
}
