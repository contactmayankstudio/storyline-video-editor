package com.video.engine

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source

class RemoteCommandManager(
    private val installationIdProvider: () -> String,
    private val sessionIdProvider: () -> String,
    private val onExecute: (command: String, commandId: String) -> Pair<Boolean, String>,
) {
    companion object {
        private const val TAG = "[RemoteCmd]"
        private const val POLL_INTERVAL_MS = 3_500L
        private const val POLL_HEARTBEAT_EVERY = 6
    }

    private val firestore = FirebaseFirestore.getInstance()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val inFlightCommandIds = mutableSetOf<String>()
    private var listenerRegistration: ListenerRegistration? = null
    private var active = false
    private var pollAttempt = 0
    private var pollRequestInFlight = false
    private var lastObservedState = ""
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
        Log.i(TAG, "Starting phone command receiver installation=$installationId session=${sessionIdProvider().ifBlank { "unknown" }}")
        attachLiveListener(installationId)
        mainHandler.post(pollRunnable)
    }

    fun close() {
        active = false
        mainHandler.removeCallbacks(pollRunnable)
        listenerRegistration?.remove()
        listenerRegistration = null
        inFlightCommandIds.clear()
        pollRequestInFlight = false
    }

    private fun attachLiveListener(installationId: String) {
        listenerRegistration?.remove()
        listenerRegistration =
            firestore.collection("ops_device_channels")
                .document(installationId)
                .addSnapshotListener(MetadataChanges.INCLUDE) { document, error ->
                    if (!active) return@addSnapshotListener
                    if (error != null) {
                        Log.w(TAG, "Live command listener failed: ${error.message}")
                        return@addSnapshotListener
                    }
                    if (document == null) {
                        Log.w(TAG, "Live command listener returned null snapshot")
                        return@addSnapshotListener
                    }
                    processSnapshot(
                        installationId = installationId,
                        documentExists = document.exists(),
                        status = document.getString("status").orEmpty().trim(),
                        commandId = document.getString("commandId").orEmpty().trim(),
                        command = document.getString("command").orEmpty().trim(),
                        updatedAtMs = document.getLong("updatedAtMs") ?: -1L,
                        sourceTag = "listener",
                        fromCache = document.metadata.isFromCache,
                        hasPendingWrites = document.metadata.hasPendingWrites(),
                    )
                }
    }

    private fun pollLatestCommand() {
        val installationId = installationIdProvider().trim()
        if (installationId.isEmpty()) return
        if (pollRequestInFlight) return
        pollRequestInFlight = true
        pollAttempt += 1
        if (pollAttempt == 1 || pollAttempt % POLL_HEARTBEAT_EVERY == 0) {
            Log.d(TAG, "Polling command channel installation=$installationId attempt=$pollAttempt")
        }
        firestore.collection("ops_device_channels")
            .document(installationId)
            .get(Source.SERVER)
            .addOnSuccessListener { document ->
                processSnapshot(
                    installationId = installationId,
                    documentExists = document.exists(),
                    status = document.getString("status").orEmpty().trim(),
                    commandId = document.getString("commandId").orEmpty().trim(),
                    command = document.getString("command").orEmpty().trim(),
                    updatedAtMs = document.getLong("updatedAtMs") ?: -1L,
                    sourceTag = "poll_server",
                    fromCache = document.metadata.isFromCache,
                    hasPendingWrites = document.metadata.hasPendingWrites(),
                )
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "Remote command poll failed: ${error.message}")
                firestore.collection("ops_device_channels")
                    .document(installationId)
                    .get(Source.CACHE)
                    .addOnSuccessListener { document ->
                        processSnapshot(
                            installationId = installationId,
                            documentExists = document.exists(),
                            status = document.getString("status").orEmpty().trim(),
                            commandId = document.getString("commandId").orEmpty().trim(),
                            command = document.getString("command").orEmpty().trim(),
                            updatedAtMs = document.getLong("updatedAtMs") ?: -1L,
                            sourceTag = "poll_cache",
                            fromCache = document.metadata.isFromCache,
                            hasPendingWrites = document.metadata.hasPendingWrites(),
                        )
                    }
                    .addOnFailureListener { cacheError ->
                        Log.w(TAG, "Remote command cache poll failed: ${cacheError.message}")
                    }
                    .addOnCompleteListener {
                        pollRequestInFlight = false
                    }
            }
            .addOnCompleteListener {
                pollRequestInFlight = false
            }
    }

    private fun processSnapshot(
        installationId: String,
        documentExists: Boolean,
        status: String,
        commandId: String,
        command: String,
        updatedAtMs: Long,
        sourceTag: String,
        fromCache: Boolean,
        hasPendingWrites: Boolean,
    ) {
        val observedState =
            listOf(
                documentExists.toString(),
                status,
                commandId,
                command,
                updatedAtMs.toString(),
                fromCache.toString(),
                hasPendingWrites.toString(),
            ).joinToString("|")
        if (observedState != lastObservedState) {
            lastObservedState = observedState
            Log.i(
                TAG,
                "Observed channel source=$sourceTag exists=$documentExists status=$status commandId=$commandId command=$command updatedAtMs=$updatedAtMs fromCache=$fromCache pending=$hasPendingWrites",
            )
        }
        if (!documentExists) return
        if (status != "queued" || commandId.isEmpty() || command.isEmpty()) return
        if (!inFlightCommandIds.add(commandId)) return
        Log.i(TAG, "Queued phone command detected via $sourceTag id=$commandId command=$command")
        handleCommand(installationId, commandId, command)
    }

    private fun handleCommand(installationId: String, commandId: String, command: String) {
        Log.i(TAG, "Handling phone command id=$commandId command=$command installation=$installationId")
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
            Log.i(TAG, "Command execution finished id=$commandId command=$command success=$success message=$resultMessage")

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

        Log.d(TAG, "Updating command state id=$commandId status=$status message=$resultMessage")
        firestore.collection("ops_device_channels")
            .document(installationId)
            .set(payload, SetOptions.merge())
            .addOnSuccessListener {
                Log.d(TAG, "Updated command state id=$commandId status=$status")
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "Failed to update command $commandId: ${error.message}")
            }
    }
}
