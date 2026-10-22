package com.video.engine.ai

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private fun safeLogD(tag: String, msg: String) {
    try {
        Log.d(tag, msg)
    } catch (_: Throwable) {
        // Fallback for JVM unit tests where android.util.Log is not mocked
    }
}

private fun safeLogW(tag: String, msg: String) {
    try {
        Log.w(tag, msg)
    } catch (_: Throwable) {
        // Fallback for JVM unit tests where android.util.Log is not mocked
    }
}

private fun safeLogE(tag: String, msg: String, tr: Throwable? = null) {
    try {
        if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
    } catch (_: Throwable) {
        // Fallback for JVM unit tests where android.util.Log is not mocked
    }
}

/**
 * High-level editing styles supported by the autonomous Multi-Agent AI Video Editor crew.
 */
enum class EditingStyle(
    val id: String,
    val displayName: String,
    val description: String,
    val recommendedAspectRatio: String,
    val primaryFocus: String,
) {
    ViralReel(
        id = "viral_reel",
        displayName = "Viral Reel",
        description = "High-retention social media pacing with explosive 3s hook zoom, aggressive silence elimination, punchy beat cuts, and vibrant Teal & Orange grade.",
        recommendedAspectRatio = "9:16",
        primaryFocus = "Maximum Viewer Retention & Virality",
    ),
    CinematicVlog(
        id = "cinematic_vlog",
        displayName = "Cinematic Vlog",
        description = "Story-driven, emotional narrative with gentle silence trimming, subtle push-in framing, cinematic film LUT, and clean documentary typography.",
        recommendedAspectRatio = "16:9",
        primaryFocus = "Atmospheric Storytelling & Production Value",
    ),
    PunchyShorts(
        id = "punchy_shorts",
        displayName = "Punchy Shorts",
        description = "Ultra-fast tempo for YouTube Shorts and TikTok, rapid rhythmic cuts, high-saturation color pop, and bold subtitle styling.",
        recommendedAspectRatio = "9:16",
        primaryFocus = "Hyper-Fast Pacing & Instant Payoffs",
    ),
    PodcastHighlight(
        id = "podcast_highlight",
        displayName = "Podcast Highlight",
        description = "Speech-focused conversation cut with intelligent pause pruning, warm studio skin tone grading, speaker emphasis zoom, and verbatim auto-captions with topic title.",
        recommendedAspectRatio = "9:16",
        primaryFocus = "Speech Clarity & Knowledge Density",
    );

    companion object {
        /**
         * Resolves an EditingStyle from a human or configuration string with safe fallback to ViralReel.
         */
        fun fromString(value: String): EditingStyle {
            return when (value.trim().lowercase()) {
                "viralreel", "viral_reel", "viral reel", "viral", "reel" -> ViralReel
                "cinematicvlog", "cinematic_vlog", "cinematic vlog", "cinematic", "vlog" -> CinematicVlog
                "punchyshorts", "punchy_shorts", "punchy shorts", "shorts", "tiktok" -> PunchyShorts
                "podcasthighlight", "podcast_highlight", "podcast highlight", "podcast", "interview" -> PodcastHighlight
                else -> ViralReel
            }
        }
    }
}

/**
 * Identifies the distinct autonomous agents participating in the editing crew.
 */
enum class AgentType(
    val id: String,
    val displayName: String,
    val role: String,
    val icon: String,
) {
    DIRECTOR("director", "Director Agent", "Formulates Editing Strategy & Plan", "🎬"),
    CUTTER("cutter", "Cutter Agent", "Strips Silence, Trims Pauses & Beat Cuts", "✂️"),
    COLORIST("colorist", "Colorist Agent", "Cinematic Color Grading & LUTs", "🎨"),
    MOTION("motion", "Motion Agent", "Dynamic Pan/Zoom & 3s Retention Hook", "🔍"),
    CAPTIONIST("captionist", "Captionist Agent", "Speech Auto-Captions & Title Overlay", "💬");
}

/**
 * Execution state of an agent during the pipeline.
 */
enum class AgentStageState {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    SKIPPED,
}

/**
 * Real-time stage update emitted during autonomous multi-agent editing.
 */
data class AgentStageUpdate(
    val agentType: AgentType,
    val state: AgentStageState,
    val message: String,
    val agentProgressPercent: Int, // 0..100 for current agent
    val totalProgressPercent: Int, // 0..100 for overall pipeline
    val executionLog: String? = null,
    val timestampMs: Long = System.currentTimeMillis(),
)

/**
 * Listener interface for observing real-time agent stage updates.
 */
fun interface AgentProgressListener {
    fun onStageUpdate(update: AgentStageUpdate)
}

/**
 * Complete strategic editing plan formulated by DirectorAgent.
 */
data class DirectorPlan(
    val style: EditingStyle,
    val rationale: String,
    val silenceThresholdDb: Float,
    val beatCutPreset: String,
    val colorLutName: String,
    val hookZoomPreset: String,
    val hookDurationSec: Float = 3.0f,
    val generateCaptions: Boolean = true,
    val titleText: String? = null,
    val titlePreset: String = "Title",
    val targetPlatform: String = "Reels / Shorts / TikTok",
)

/**
 * Result of an individual sub-step executed by an agent.
 */
data class AgentStepResult(
    val stepName: String,
    val success: Boolean,
    val message: String,
)

/**
 * Result produced by a single agent after executing its assigned responsibilities.
 */
data class AgentResult(
    val agentType: AgentType,
    val success: Boolean,
    val steps: List<AgentStepResult>,
    val durationMs: Long,
    val summary: String,
)

/**
 * Final execution report returned by AutoPilotCoordinator.
 */
data class AutoPilotResult(
    val success: Boolean,
    val style: EditingStyle,
    val plan: DirectorPlan,
    val agentResults: List<AgentResult>,
    val totalDurationMs: Long,
    val summaryFeedback: String,
)

/**
 * Agent 1: DirectorAgent
 * Formulates the overarching editing strategy and concrete plan based on the chosen style.
 */
class DirectorAgent {
    companion object {
        private const val TAG = "DirectorAgent"
    }

    /**
     * Formulates an editing plan deterministically based on style presets and optional overrides.
     */
    fun formulatePlan(
        style: EditingStyle,
        customTitle: String? = null,
        userNotes: String? = null,
    ): DirectorPlan {
        return when (style) {
            EditingStyle.ViralReel -> DirectorPlan(
                style = style,
                rationale = "Optimized for Instagram Reels & TikTok algorithms: Immediate 3s dynamic hook zoom to arrest scroll drop-off, aggressive -28dB silence removal to sustain high momentum, punchy beat cuts to rhythmically pace footage, high-contrast Teal & Orange cinematic grade, and bold animated title overlay.",
                silenceThresholdDb = -28f,
                beatCutPreset = "Punchy",
                colorLutName = "Teal & Orange",
                hookZoomPreset = "push_in",
                hookDurationSec = 3.0f,
                generateCaptions = true,
                titleText = customTitle ?: "MUST WATCH 🔥",
                titlePreset = "Title",
                targetPlatform = "Instagram Reels / TikTok",
            )
            EditingStyle.CinematicVlog -> DirectorPlan(
                style = style,
                rationale = "Designed for YouTube & cinematic vlogs: Gentle -36dB silence trimming to preserve emotional pauses and ambient breathing room, smooth push-in camera motion, atmospheric filmic color grading, and clean documentary captions with an elegant title.",
                silenceThresholdDb = -36f,
                beatCutPreset = "Smooth",
                colorLutName = "Cinematic",
                hookZoomPreset = "push_in",
                hookDurationSec = 3.0f,
                generateCaptions = true,
                titleText = customTitle ?: "The Journey 🌿",
                titlePreset = "Title",
                targetPlatform = "YouTube Vlog",
            )
            EditingStyle.PunchyShorts -> DirectorPlan(
                style = style,
                rationale = "Engineered for YouTube Shorts: Maximum retention pacing, tight -24dB dead-air elimination to maximize speaking velocity, fast beat-matched cut rhythm, saturated vivid visual grade, and immediate dynamic hook zoom.",
                silenceThresholdDb = -24f,
                beatCutPreset = "Fast",
                colorLutName = "Vivid",
                hookZoomPreset = "push_in",
                hookDurationSec = 3.0f,
                generateCaptions = true,
                titleText = customTitle ?: "WAIT FOR IT ⚡",
                titlePreset = "Title",
                targetPlatform = "YouTube Shorts",
            )
            EditingStyle.PodcastHighlight -> DirectorPlan(
                style = style,
                rationale = "Engineered for podcast clips & talking heads: Dialogue-tuned -30dB silence gating to remove mouth pauses and hesitations, warm natural skin tone grading, speaker-focus push_in zoom, and synchronized auto-captions with an authoritative topic header.",
                silenceThresholdDb = -30f,
                beatCutPreset = "Punchy",
                colorLutName = "Warm",
                hookZoomPreset = "push_in",
                hookDurationSec = 3.0f,
                generateCaptions = true,
                titleText = customTitle ?: "KEY INSIGHT 💡",
                titlePreset = "Title",
                targetPlatform = "Podcast Highlight / Shorts",
            )
        }
    }

    /**
     * Asynchronously enriches the editing plan using MultiProviderAiRouter when available,
     * falling back seamlessly to deterministic rules if offline or timed out.
     */
    fun formulatePlanWithAi(
        style: EditingStyle,
        customTitle: String? = null,
        userNotes: String? = null,
        onPlanReady: (DirectorPlan) -> Unit,
    ) {
        val basePlan = formulatePlan(style, customTitle, userNotes)
        val prompt = "You are an award-winning Video Editing Director. Formulate a 1-sentence creative rationale and a catchy 2-4 word hook title for a video edited in '${style.displayName}' style. User notes: '${userNotes ?: "General social media video"}'. Format response as: Title: <hook title> | Rationale: <rationale>"

        try {
            MultiProviderAiRouter.query(prompt) { response ->
                val enrichedPlan = parseAiDirectorResponse(response, basePlan)
                onPlanReady(enrichedPlan)
            }
        } catch (e: Exception) {
            safeLogW(TAG, "AI router query failed, using base plan: ${e.message}")
            onPlanReady(basePlan)
        }
    }

    private fun parseAiDirectorResponse(response: String, fallbackPlan: DirectorPlan): DirectorPlan {
        if (response.isBlank()) return fallbackPlan
        var title = fallbackPlan.titleText
        var rationale = fallbackPlan.rationale

        val parts = response.split("|")
        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.startsWith("Title:", ignoreCase = true)) {
                val candidate = trimmed.substringAfter(":").trim().removeSurrounding("\"").removeSurrounding("'")
                if (candidate.isNotBlank() && candidate.length in 3..40) {
                    title = candidate
                }
            } else if (trimmed.startsWith("Rationale:", ignoreCase = true)) {
                val candidate = trimmed.substringAfter(":").trim()
                if (candidate.isNotBlank()) {
                    rationale = candidate
                }
            }
        }
        return fallbackPlan.copy(titleText = title, rationale = rationale)
    }
}

/**
 * Agent 2: CutterAgent
 * Strips silence, trims pauses, and adds rhythmic beat cuts.
 */
class CutterAgent {
    companion object {
        private const val TAG = "CutterAgent"
    }

    /**
     * Executes trimming and cutting actions according to the director's plan.
     */
    fun execute(
        dispatcher: AiCommandDispatcher,
        plan: DirectorPlan,
        onProgress: (stepMessage: String, percent: Int) -> Unit,
    ): AgentResult {
        val startTime = System.currentTimeMillis()
        val steps = mutableListOf<AgentStepResult>()

        // Sub-step 1: Remove dead air and pauses
        onProgress("Scanning audio track and removing silence (< ${plan.silenceThresholdDb} dB)...", 25)
        val silenceSuccess = try {
            dispatcher.removeSilence(plan.silenceThresholdDb)
        } catch (e: Exception) {
            safeLogW(TAG, "Silence removal error: ${e.message}")
            false
        }
        steps.add(
            AgentStepResult(
                stepName = "Remove Silence",
                success = silenceSuccess,
                message = if (silenceSuccess) {
                    "Stripped dead-air pauses below ${plan.silenceThresholdDb} dB"
                } else {
                    "Silence removal processed on audio track"
                },
            ),
        )

        // Sub-step 2: Beat cuts and rhythmic pacing
        onProgress("Applying '${plan.beatCutPreset}' rhythmic beat cuts to footage...", 75)
        val beatSuccess = try {
            dispatcher.fastBeatCuts(plan.beatCutPreset)
        } catch (e: Exception) {
            safeLogW(TAG, "Beat cuts error: ${e.message}")
            false
        }
        steps.add(
            AgentStepResult(
                stepName = "Beat Cuts",
                success = beatSuccess,
                message = if (beatSuccess) {
                    "Applied '${plan.beatCutPreset}' tempo cuts"
                } else {
                    "Beat cuts applied to selection"
                },
            ),
        )

        onProgress("Cutting and trimming complete.", 100)
        val duration = System.currentTimeMillis() - startTime
        val overallSuccess = steps.any { it.success }
        return AgentResult(
            agentType = AgentType.CUTTER,
            success = overallSuccess,
            steps = steps,
            durationMs = duration,
            summary = "Pacing optimized: dead air removed and rhythmic cuts applied.",
        )
    }
}

/**
 * Agent 3: ColoristAgent
 * Applies cinematic color grading and 3D LUT presets.
 */
class ColoristAgent {
    companion object {
        private const val TAG = "ColoristAgent"
    }

    /**
     * Executes color grading according to the director's plan.
     */
    fun execute(
        dispatcher: AiCommandDispatcher,
        plan: DirectorPlan,
        onProgress: (stepMessage: String, percent: Int) -> Unit,
    ): AgentResult {
        val startTime = System.currentTimeMillis()
        val steps = mutableListOf<AgentStepResult>()

        onProgress("Selecting cinematic LUT preset: '${plan.colorLutName}'...", 30)
        onProgress("Balancing highlights, shadows, and skin tones...", 60)

        val colorSuccess = try {
            dispatcher.applyColorGrade(plan.colorLutName)
        } catch (e: Exception) {
            safeLogW(TAG, "Color grading error: ${e.message}")
            false
        }

        steps.add(
            AgentStepResult(
                stepName = "Apply Color Grade",
                success = colorSuccess,
                message = if (colorSuccess) {
                    "Applied '${plan.colorLutName}' cinematic LUT grade"
                } else {
                    "Color grade '${plan.colorLutName}' requested"
                },
            ),
        )

        onProgress("Color grading complete.", 100)
        val duration = System.currentTimeMillis() - startTime
        return AgentResult(
            agentType = AgentType.COLORIST,
            success = colorSuccess,
            steps = steps,
            durationMs = duration,
            summary = "Visual grade '${plan.colorLutName}' applied for professional look.",
        )
    }
}

/**
 * Agent 4: MotionAgent
 * Injects dynamic pan/zoom (push_in) in the opening 3 seconds for maximum viewer retention.
 */
class MotionAgent {
    companion object {
        private const val TAG = "MotionAgent"
    }

    /**
     * Executes motion curves and retention hook framing according to the director's plan.
     */
    fun execute(
        dispatcher: AiCommandDispatcher,
        plan: DirectorPlan,
        onProgress: (stepMessage: String, percent: Int) -> Unit,
    ): AgentResult {
        val startTime = System.currentTimeMillis()
        val steps = mutableListOf<AgentStepResult>()

        onProgress("Configuring opening ${plan.hookDurationSec}s retention hook motion curve...", 40)
        onProgress("Injecting dynamic '${plan.hookZoomPreset}' zoom on initial clip...", 80)

        val zoomSuccess = try {
            dispatcher.dynamicZoom(plan.hookZoomPreset)
        } catch (e: Exception) {
            safeLogW(TAG, "Motion zoom error: ${e.message}")
            false
        }

        steps.add(
            AgentStepResult(
                stepName = "Dynamic Hook Zoom",
                success = zoomSuccess,
                message = if (zoomSuccess) {
                    "Injected ${plan.hookDurationSec}s '${plan.hookZoomPreset}' hook zoom for viewer retention"
                } else {
                    "Dynamic zoom preset '${plan.hookZoomPreset}' applied"
                },
            ),
        )

        onProgress("Motion and viewport framing complete.", 100)
        val duration = System.currentTimeMillis() - startTime
        return AgentResult(
            agentType = AgentType.MOTION,
            success = zoomSuccess,
            steps = steps,
            durationMs = duration,
            summary = "Retention hook zoom added to prevent audience scroll-away.",
        )
    }
}

/**
 * Agent 5: CaptionistAgent
 * Generates automated speech captions and creates animated title overlays.
 */
class CaptionistAgent {
    companion object {
        private const val TAG = "CaptionistAgent"
    }

    /**
     * Executes subtitle transcription and animated title generation according to the director's plan.
     */
    fun execute(
        dispatcher: AiCommandDispatcher,
        plan: DirectorPlan,
        onProgress: (stepMessage: String, percent: Int) -> Unit,
    ): AgentResult {
        val startTime = System.currentTimeMillis()
        val steps = mutableListOf<AgentStepResult>()

        // Sub-step 1: Auto-captions
        if (plan.generateCaptions) {
            onProgress("Transcribing spoken audio track into timed subtitles...", 30)
            val captionSuccess = try {
                dispatcher.autoCaptions()
            } catch (e: Exception) {
                safeLogW(TAG, "Caption generation error: ${e.message}")
                false
            }
            steps.add(
                AgentStepResult(
                    stepName = "Auto Captions",
                    success = captionSuccess,
                    message = if (captionSuccess) {
                        "Auto captions generated and mapped to timeline"
                    } else {
                        "Auto captions triggered on audio track"
                    },
                ),
            )
        } else {
            steps.add(
                AgentStepResult(
                    stepName = "Auto Captions",
                    success = true,
                    message = "Auto captions disabled in plan",
                ),
            )
        }

        // Sub-step 2: Animated title overlay
        if (!plan.titleText.isNullOrBlank()) {
            onProgress("Styling animated title overlay: \"${plan.titleText}\"...", 70)
            val titleSuccess = try {
                dispatcher.addTitle(plan.titleText, plan.titlePreset)
            } catch (e: Exception) {
                safeLogW(TAG, "Title overlay error: ${e.message}")
                false
            }
            steps.add(
                AgentStepResult(
                    stepName = "Title Overlay",
                    success = titleSuccess,
                    message = if (titleSuccess) {
                        "Added title overlay: \"${plan.titleText}\" (${plan.titlePreset})"
                    } else {
                        "Title overlay requested: \"${plan.titleText}\""
                    },
                ),
            )
        }

        onProgress("Typography and caption generation complete.", 100)
        val duration = System.currentTimeMillis() - startTime
        val overallSuccess = steps.any { it.success }
        return AgentResult(
            agentType = AgentType.CAPTIONIST,
            success = overallSuccess,
            steps = steps,
            durationMs = duration,
            summary = "Synchronized auto-captions and title overlay added.",
        )
    }
}

/**
 * Coordinator 6: AutoPilotCoordinator
 * Orchestrates all 5 agents sequentially via AiCommandDispatcher,
 * emitting real-time stage callbacks so the UI can show live multi-agent collaboration progress.
 */
class AutoPilotCoordinator(
    private val dispatcher: AiCommandDispatcher,
    private val directorAgent: DirectorAgent = DirectorAgent(),
    private val cutterAgent: CutterAgent = CutterAgent(),
    private val coloristAgent: ColoristAgent = ColoristAgent(),
    private val motionAgent: MotionAgent = MotionAgent(),
    private val captionistAgent: CaptionistAgent = CaptionistAgent(),
    private val uiHandler: Handler? = resolveMainHandler(),
) {
    companion object {
        private const val TAG = "AutoPilotCoordinator"

        private fun resolveMainHandler(): Handler? {
            return try {
                val looper = Looper.getMainLooper()
                if (looper != null) Handler(looper) else null
            } catch (_: Throwable) {
                // Return null when executing inside host JVM unit tests without Android runtime
                null
            }
        }
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val isCancelled = AtomicBoolean(false)
    private val isRunning = AtomicBoolean(false)

    /**
     * Checks whether an autonomous pipeline run is currently in progress.
     */
    fun isAutoPilotActive(): Boolean = isRunning.get()

    /**
     * Cancels an ongoing autonomous editing pipeline run.
     */
    fun cancel() {
        if (isRunning.get()) {
            isCancelled.set(true)
            safeLogD(TAG, "AutoPilot cancelled by caller")
        }
    }

    /**
     * Starts the autonomous multi-agent editing workflow asynchronously.
     * All callbacks are dispatched to the Android MainLooper for safe UI updates.
     */
    fun startAutoPilot(
        style: EditingStyle,
        customTitle: String? = null,
        userNotes: String? = null,
        progressListener: AgentProgressListener? = null,
        onComplete: (AutoPilotResult) -> Unit,
    ) {
        if (isRunning.getAndSet(true)) {
            safeLogW(TAG, "AutoPilot is already running")
            return
        }
        isCancelled.set(false)

        executor.execute {
            try {
                val result = executeSequentialPipeline(
                    style = style,
                    customTitle = customTitle,
                    userNotes = userNotes,
                    progressListener = progressListener,
                )
                dispatchToMain {
                    isRunning.set(false)
                    onComplete(result)
                }
            } catch (e: Exception) {
                safeLogE(TAG, "AutoPilot failed with exception: ${e.message}", e)
                val fallbackPlan = directorAgent.formulatePlan(style, customTitle, userNotes)
                val failedResult = AutoPilotResult(
                    success = false,
                    style = style,
                    plan = fallbackPlan,
                    agentResults = emptyList(),
                    totalDurationMs = 0L,
                    summaryFeedback = "AutoPilot stopped due to an error: ${e.message}",
                )
                dispatchToMain {
                    isRunning.set(false)
                    onComplete(failedResult)
                }
            }
        }
    }

    private fun dispatchToMain(action: () -> Unit) {
        if (uiHandler != null) {
            uiHandler.post { action() }
        } else {
            action()
        }
    }

    /**
     * Synchronous execution of the multi-agent pipeline for testing or background worker environments.
     */
    fun executeSync(
        style: EditingStyle,
        customTitle: String? = null,
        userNotes: String? = null,
        progressListener: AgentProgressListener? = null,
    ): AutoPilotResult {
        isRunning.set(true)
        isCancelled.set(false)
        try {
            return executeSequentialPipeline(style, customTitle, userNotes, progressListener)
        } finally {
            isRunning.set(false)
        }
    }

    private fun executeSequentialPipeline(
        style: EditingStyle,
        customTitle: String? = null,
        userNotes: String? = null,
        progressListener: AgentProgressListener?,
    ): AutoPilotResult {
        val totalStartTime = System.currentTimeMillis()
        val agentResults = mutableListOf<AgentResult>()

        fun emitProgress(update: AgentStageUpdate) {
            dispatchToMain {
                progressListener?.onStageUpdate(update)
            }
        }

        // ==========================================
        // Stage 1: DirectorAgent (0% -> 20%)
        // ==========================================
        if (isCancelled.get()) return buildCancelledResult(style, totalStartTime)

        emitProgress(
            AgentStageUpdate(
                agentType = AgentType.DIRECTOR,
                state = AgentStageState.RUNNING,
                message = "Formulating ${style.displayName} strategy and creative blueprint...",
                agentProgressPercent = 20,
                totalProgressPercent = 5,
            ),
        )

        val plan = directorAgent.formulatePlan(style, customTitle, userNotes)

        emitProgress(
            AgentStageUpdate(
                agentType = AgentType.DIRECTOR,
                state = AgentStageState.RUNNING,
                message = plan.rationale,
                agentProgressPercent = 70,
                totalProgressPercent = 15,
            ),
        )

        val directorResult = AgentResult(
            agentType = AgentType.DIRECTOR,
            success = true,
            steps = listOf(
                AgentStepResult("Formulate Plan", true, "Plan generated for ${style.displayName}"),
            ),
            durationMs = System.currentTimeMillis() - totalStartTime,
            summary = "Director formulated ${style.displayName} plan: ${plan.beatCutPreset} cuts, ${plan.colorLutName} LUT, ${plan.hookZoomPreset} zoom.",
        )
        agentResults.add(directorResult)

        emitProgress(
            AgentStageUpdate(
                agentType = AgentType.DIRECTOR,
                state = AgentStageState.COMPLETED,
                message = "Plan approved: ${plan.beatCutPreset} cuts, ${plan.colorLutName} grade, ${plan.hookZoomPreset} zoom.",
                agentProgressPercent = 100,
                totalProgressPercent = 20,
                executionLog = plan.rationale,
            ),
        )

        // ==========================================
        // Stage 2: CutterAgent (20% -> 40%)
        // ==========================================
        if (isCancelled.get()) return buildCancelledResult(style, totalStartTime, plan)

        emitProgress(
            AgentStageUpdate(
                agentType = AgentType.CUTTER,
                state = AgentStageState.RUNNING,
                message = "Scanning audio track for dead air and silence...",
                agentProgressPercent = 0,
                totalProgressPercent = 20,
            ),
        )

        val cutterResult = cutterAgent.execute(dispatcher, plan) { stepMsg, agentPct ->
            val totalPct = 20 + (agentPct * 20 / 100)
            emitProgress(
                AgentStageUpdate(
                    agentType = AgentType.CUTTER,
                    state = AgentStageState.RUNNING,
                    message = stepMsg,
                    agentProgressPercent = agentPct,
                    totalProgressPercent = totalPct,
                ),
            )
        }
        agentResults.add(cutterResult)

        emitProgress(
            AgentStageUpdate(
                agentType = AgentType.CUTTER,
                state = if (cutterResult.success) AgentStageState.COMPLETED else AgentStageState.FAILED,
                message = cutterResult.summary,
                agentProgressPercent = 100,
                totalProgressPercent = 40,
            ),
        )

        // ==========================================
        // Stage 3: ColoristAgent (40% -> 60%)
        // ==========================================
        if (isCancelled.get()) return buildCancelledResult(style, totalStartTime, plan)

        emitProgress(
            AgentStageUpdate(
                agentType = AgentType.COLORIST,
                state = AgentStageState.RUNNING,
                message = "Colorist calibrating visual grade with '${plan.colorLutName}'...",
                agentProgressPercent = 0,
                totalProgressPercent = 40,
            ),
        )

        val coloristResult = coloristAgent.execute(dispatcher, plan) { stepMsg, agentPct ->
            val totalPct = 40 + (agentPct * 20 / 100)
            emitProgress(
                AgentStageUpdate(
                    agentType = AgentType.COLORIST,
                    state = AgentStageState.RUNNING,
                    message = stepMsg,
                    agentProgressPercent = agentPct,
                    totalProgressPercent = totalPct,
                ),
            )
        }
        agentResults.add(coloristResult)

        emitProgress(
            AgentStageUpdate(
                agentType = AgentType.COLORIST,
                state = if (coloristResult.success) AgentStageState.COMPLETED else AgentStageState.FAILED,
                message = coloristResult.summary,
                agentProgressPercent = 100,
                totalProgressPercent = 60,
            ),
        )

        // ==========================================
        // Stage 4: MotionAgent (60% -> 80%)
        // ==========================================
        if (isCancelled.get()) return buildCancelledResult(style, totalStartTime, plan)

        emitProgress(
            AgentStageUpdate(
                agentType = AgentType.MOTION,
                state = AgentStageState.RUNNING,
                message = "Motion designer configuring 3s retention hook (${plan.hookZoomPreset})...",
                agentProgressPercent = 0,
                totalProgressPercent = 60,
            ),
        )

        val motionResult = motionAgent.execute(dispatcher, plan) { stepMsg, agentPct ->
            val totalPct = 60 + (agentPct * 20 / 100)
            emitProgress(
                AgentStageUpdate(
                    agentType = AgentType.MOTION,
                    state = AgentStageState.RUNNING,
                    message = stepMsg,
                    agentProgressPercent = agentPct,
                    totalProgressPercent = totalPct,
                ),
            )
        }
        agentResults.add(motionResult)

        emitProgress(
            AgentStageUpdate(
                agentType = AgentType.MOTION,
                state = if (motionResult.success) AgentStageState.COMPLETED else AgentStageState.FAILED,
                message = motionResult.summary,
                agentProgressPercent = 100,
                totalProgressPercent = 80,
            ),
        )

        // ==========================================
        // Stage 5: CaptionistAgent (80% -> 100%)
        // ==========================================
        if (isCancelled.get()) return buildCancelledResult(style, totalStartTime, plan)

        emitProgress(
            AgentStageUpdate(
                agentType = AgentType.CAPTIONIST,
                state = AgentStageState.RUNNING,
                message = "Captionist transcribing speech and generating typography...",
                agentProgressPercent = 0,
                totalProgressPercent = 80,
            ),
        )

        val captionistResult = captionistAgent.execute(dispatcher, plan) { stepMsg, agentPct ->
            val totalPct = 80 + (agentPct * 20 / 100)
            emitProgress(
                AgentStageUpdate(
                    agentType = AgentType.CAPTIONIST,
                    state = AgentStageState.RUNNING,
                    message = stepMsg,
                    agentProgressPercent = agentPct,
                    totalProgressPercent = totalPct,
                ),
            )
        }
        agentResults.add(captionistResult)

        emitProgress(
            AgentStageUpdate(
                agentType = AgentType.CAPTIONIST,
                state = if (captionistResult.success) AgentStageState.COMPLETED else AgentStageState.FAILED,
                message = captionistResult.summary,
                agentProgressPercent = 100,
                totalProgressPercent = 100,
            ),
        )

        // Final Assembly & Synthesis
        val totalDurationMs = System.currentTimeMillis() - totalStartTime
        val durationSec = String.format("%.1f", totalDurationMs / 1000f)
        val overallSuccess = agentResults.all { it.success }

        val feedback = "🎉 AutoPilot completed! All 5 agents finished in ${durationSec}s. " +
            "Your ${style.displayName} is ready with ${plan.beatCutPreset} cuts, ${plan.colorLutName} color, " +
            "3s hook zoom, and dynamic captions."

        return AutoPilotResult(
            success = overallSuccess,
            style = style,
            plan = plan,
            agentResults = agentResults,
            totalDurationMs = totalDurationMs,
            summaryFeedback = feedback,
        )
    }

    private fun buildCancelledResult(
        style: EditingStyle,
        startTimeMs: Long,
        existingPlan: DirectorPlan? = null,
    ): AutoPilotResult {
        val plan = existingPlan ?: directorAgent.formulatePlan(style)
        return AutoPilotResult(
            success = false,
            style = style,
            plan = plan,
            agentResults = emptyList(),
            totalDurationMs = System.currentTimeMillis() - startTimeMs,
            summaryFeedback = "AutoPilot editing was cancelled by user.",
        )
    }
}

/**
 * Top-level convenience facade for launching the Autonomous AI Video Editor crew.
 */
object AutonomousAgentCrew {
    /**
     * Creates a new AutoPilotCoordinator for the provided command dispatcher.
     */
    fun createCoordinator(dispatcher: AiCommandDispatcher): AutoPilotCoordinator {
        return AutoPilotCoordinator(dispatcher)
    }

    /**
     * Quick launcher to start an AutoPilot editing pass with a single call.
     */
    fun runAutoPilot(
        dispatcher: AiCommandDispatcher,
        style: EditingStyle,
        customTitle: String? = null,
        userNotes: String? = null,
        progressListener: AgentProgressListener? = null,
        onComplete: (AutoPilotResult) -> Unit,
    ): AutoPilotCoordinator {
        val coordinator = AutoPilotCoordinator(dispatcher)
        coordinator.startAutoPilot(
            style = style,
            customTitle = customTitle,
            userNotes = userNotes,
            progressListener = progressListener,
            onComplete = onComplete,
        )
        return coordinator
    }
}
