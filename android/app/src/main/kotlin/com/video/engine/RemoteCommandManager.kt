package com.video.engine

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.content.Context
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import java.util.concurrent.Executors

class RemoteCommandManager(
    context: Context,
    private val installationIdProvider: () -> String,
    private val sessionIdProvider: () -> String,
    private val onExecute: (command: String, commandId: String) -> Pair<Boolean, String>,
) {
    companion object {
        private const val TAG = "[RemoteCmd]"
        private const val POLL_INTERVAL_MS = 3_500L
        private const val POLL_HEARTBEAT_EVERY = 6
    }

    private val opsEnabled = context.resources.getBoolean(R.bool.storyline_runtime_ops_enabled)
    private val firestore =
        if (opsEnabled) runCatching { FirebaseFirestore.getInstance() }.getOrNull() else null
    private val awsControlPlaneClient = if (opsEnabled) AwsControlPlaneClient(context) else null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val awsPollExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "aws-command-poll").apply { isDaemon = true }
    }
    private val inFlightCommandIds = mutableSetOf<String>()
    private var listenerRegistration: ListenerRegistration? = null
    private var active = false
    private var pollAttempt = 0
    private var pollRequestInFlight = false
    private var awsPollRequestInFlight = false
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
        if (!opsEnabled) {
            Log.i(TAG, "Remote command receiver disabled for this store build")
            return
        }
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
        awsPollRequestInFlight = false
        awsPollExecutor.shutdown()
    }

    private fun attachLiveListener(installationId: String) {
        val firestore = firestore ?: return
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
        val firestore = firestore ?: return
        val installationId = installationIdProvider().trim()
        if (installationId.isEmpty()) return
        if (pollRequestInFlight) return
        pollRequestInFlight = true
        pollAttempt += 1
        if (pollAttempt == 1 || pollAttempt % POLL_HEARTBEAT_EVERY == 0) {
            Log.d(TAG, "Polling command channel installation=$installationId attempt=$pollAttempt")
        }
        pollAwsCommands(installationId)
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

    private fun pollAwsCommands(installationId: String) {
        val awsControlPlaneClient = awsControlPlaneClient ?: return
        if (!awsControlPlaneClient.isConfigured() || awsPollRequestInFlight) return
        awsPollRequestInFlight = true
        awsPollExecutor.execute {
            try {
                val queued = awsControlPlaneClient.pollQueuedCommands(installationId)
                queued.forEach { command ->
                    if (!inFlightCommandIds.add(command.commandId)) return@forEach
                    Log.i(TAG, "Queued phone command detected via aws id=${command.commandId} command=${command.command}")
                    mainHandler.post {
                        handleCommand(
                            installationId = command.installationId,
                            commandId = command.commandId,
                            command = command.command,
                            backend = "aws",
                        )
                    }
                }
            } finally {
                awsPollRequestInFlight = false
            }
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
        handleCommand(installationId, commandId, command, backend = "firestore")
    }

    private fun handleCommand(installationId: String, commandId: String, command: String, backend: String) {
        Log.i(TAG, "Handling phone command id=$commandId command=$command installation=$installationId")
        markStatus(
            installationId = installationId,
            commandId = commandId,
            status = "running",
            resultMessage = "Dispatching $command",
            includeCompletedAt = false,
            backend = backend,
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
                backend = backend,
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
        backend: String,
    ) {
        val now = System.currentTimeMillis()
        if (backend == "aws") {
            val awsControlPlaneClient = awsControlPlaneClient ?: return
            awsPollExecutor.execute {
                awsControlPlaneClient.ackCommand(
                    installationId = installationId,
                    commandId = commandId,
                    status = status,
                    resultMessage = resultMessage,
                )
            }
            return
        }
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
        val firestore = firestore ?: return
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
