package com.video.engine.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutonomousAgentTeamTest {

    private class FakeDispatcher : AiCommandDispatcher {
        val calls = mutableListOf<String>()
        var splitResult = true
        var beatCutsResult = true
        var removeSilenceResult = true
        var dynamicZoomResult = true
        var applyColorGradeResult = true
        var autoCaptionsResult = true
        var addTitleResult = true
        var speedResult = true
        var togglePlaybackResult = true
        var undoResult = true
        var redoResult = true

        override fun splitClip(): Boolean {
            calls.add("splitClip")
            return splitResult
        }

        override fun fastBeatCuts(preset: String): Boolean {
            calls.add("fastBeatCuts:$preset")
            return beatCutsResult
        }

        override fun removeSilence(thresholdDb: Float): Boolean {
            calls.add("removeSilence:$thresholdDb")
            return removeSilenceResult
        }

        override fun dynamicZoom(preset: String): Boolean {
            calls.add("dynamicZoom:$preset")
            return dynamicZoomResult
        }

        override fun applyColorGrade(lutName: String): Boolean {
            calls.add("applyColorGrade:$lutName")
            return applyColorGradeResult
        }

        override fun autoCaptions(): Boolean {
            calls.add("autoCaptions")
            return autoCaptionsResult
        }

        override fun addTitle(text: String, preset: String): Boolean {
            calls.add("addTitle:$text:$preset")
            return addTitleResult
        }

        override fun setSpeed(speed: Float): Boolean {
            calls.add("setSpeed:$speed")
            return speedResult
        }

        override fun togglePlayback(): Boolean {
            calls.add("togglePlayback")
            return togglePlaybackResult
        }

        override fun undo(): Boolean {
            calls.add("undo")
            return undoResult
        }

        override fun redo(): Boolean {
            calls.add("redo")
            return redoResult
        }
    }

    // --- Style & Resolution Tests ---

    @Test
    fun editingStyle_resolutionFromStrings() {
        assertEquals(EditingStyle.ViralReel, EditingStyle.fromString("ViralReel"))
        assertEquals(EditingStyle.ViralReel, EditingStyle.fromString("viral reel"))
        assertEquals(EditingStyle.ViralReel, EditingStyle.fromString("viral"))
        assertEquals(EditingStyle.CinematicVlog, EditingStyle.fromString("CinematicVlog"))
        assertEquals(EditingStyle.CinematicVlog, EditingStyle.fromString("cinematic vlog"))
        assertEquals(EditingStyle.CinematicVlog, EditingStyle.fromString("vlog"))
        assertEquals(EditingStyle.PunchyShorts, EditingStyle.fromString("PunchyShorts"))
        assertEquals(EditingStyle.PunchyShorts, EditingStyle.fromString("shorts"))
        assertEquals(EditingStyle.PunchyShorts, EditingStyle.fromString("tiktok"))
        assertEquals(EditingStyle.PodcastHighlight, EditingStyle.fromString("PodcastHighlight"))
        assertEquals(EditingStyle.PodcastHighlight, EditingStyle.fromString("podcast"))
        // Fallback
        assertEquals(EditingStyle.ViralReel, EditingStyle.fromString("unknown_style_random"))
    }

    // --- DirectorAgent Tests ---

    @Test
    fun directorAgent_formulatesViralReelPlan() {
        val director = DirectorAgent()
        val plan = director.formulatePlan(EditingStyle.ViralReel)

        assertEquals(EditingStyle.ViralReel, plan.style)
        assertEquals(-28f, plan.silenceThresholdDb, 0.01f)
        assertEquals("Punchy", plan.beatCutPreset)
        assertEquals("Teal & Orange", plan.colorLutName)
        assertEquals("push_in", plan.hookZoomPreset)
        assertEquals(3.0f, plan.hookDurationSec, 0.01f)
        assertTrue(plan.generateCaptions)
        assertNotNull(plan.titleText)
        assertTrue(plan.rationale.isNotEmpty())
    }

    @Test
    fun directorAgent_formulatesCinematicVlogPlan() {
        val director = DirectorAgent()
        val plan = director.formulatePlan(EditingStyle.CinematicVlog, customTitle = "My Trip to Tokyo")

        assertEquals(EditingStyle.CinematicVlog, plan.style)
        assertEquals(-36f, plan.silenceThresholdDb, 0.01f)
        assertEquals("Smooth", plan.beatCutPreset)
        assertEquals("Cinematic", plan.colorLutName)
        assertEquals("push_in", plan.hookZoomPreset)
        assertEquals("My Trip to Tokyo", plan.titleText)
        assertTrue(plan.rationale.contains("vlog", ignoreCase = true))
    }

    @Test
    fun directorAgent_formulatesPunchyShortsPlan() {
        val director = DirectorAgent()
        val plan = director.formulatePlan(EditingStyle.PunchyShorts)

        assertEquals(EditingStyle.PunchyShorts, plan.style)
        assertEquals(-24f, plan.silenceThresholdDb, 0.01f)
        assertEquals("Fast", plan.beatCutPreset)
        assertEquals("Vivid", plan.colorLutName)
        assertEquals("push_in", plan.hookZoomPreset)
        assertTrue(plan.generateCaptions)
    }

    @Test
    fun directorAgent_formulatesPodcastHighlightPlan() {
        val director = DirectorAgent()
        val plan = director.formulatePlan(EditingStyle.PodcastHighlight, customTitle = "Episode 42 Takeaway")

        assertEquals(EditingStyle.PodcastHighlight, plan.style)
        assertEquals(-30f, plan.silenceThresholdDb, 0.01f)
        assertEquals("Warm", plan.colorLutName)
        assertEquals("Episode 42 Takeaway", plan.titleText)
        assertTrue(plan.generateCaptions)
    }

    // --- CutterAgent Tests ---

    @Test
    fun cutterAgent_executesSilenceAndBeatCuts() {
        val dispatcher = FakeDispatcher()
        val cutter = CutterAgent()
        val plan = DirectorAgent().formulatePlan(EditingStyle.ViralReel)

        val progressMessages = mutableListOf<String>()
        val result = cutter.execute(dispatcher, plan) { msg, _ -> progressMessages.add(msg) }

        assertTrue(result.success)
        assertEquals(AgentType.CUTTER, result.agentType)
        assertEquals(2, result.steps.size)
        assertTrue(dispatcher.calls.contains("removeSilence:-28.0"))
        assertTrue(dispatcher.calls.contains("fastBeatCuts:Punchy"))
        assertTrue(progressMessages.isNotEmpty())
    }

    // --- ColoristAgent Tests ---

    @Test
    fun coloristAgent_appliesColorLut() {
        val dispatcher = FakeDispatcher()
        val colorist = ColoristAgent()
        val plan = DirectorAgent().formulatePlan(EditingStyle.ViralReel)

        val result = colorist.execute(dispatcher, plan) { _, _ -> }

        assertTrue(result.success)
        assertEquals(AgentType.COLORIST, result.agentType)
        assertTrue(dispatcher.calls.contains("applyColorGrade:Teal & Orange"))
    }

    // --- MotionAgent Tests ---

    @Test
    fun motionAgent_appliesHookZoom() {
        val dispatcher = FakeDispatcher()
        val motion = MotionAgent()
        val plan = DirectorAgent().formulatePlan(EditingStyle.ViralReel)

        val result = motion.execute(dispatcher, plan) { _, _ -> }

        assertTrue(result.success)
        assertEquals(AgentType.MOTION, result.agentType)
        assertTrue(dispatcher.calls.contains("dynamicZoom:push_in"))
    }

    // --- CaptionistAgent Tests ---

    @Test
    fun captionistAgent_generatesCaptionsAndTitle() {
        val dispatcher = FakeDispatcher()
        val captionist = CaptionistAgent()
        val plan = DirectorAgent().formulatePlan(EditingStyle.ViralReel, customTitle = "HOOK REVEAL 🔥")

        val result = captionist.execute(dispatcher, plan) { _, _ -> }

        assertTrue(result.success)
        assertEquals(AgentType.CAPTIONIST, result.agentType)
        assertTrue(dispatcher.calls.contains("autoCaptions"))
        assertTrue(dispatcher.calls.contains("addTitle:HOOK REVEAL 🔥:Title"))
    }

    // --- AutoPilotCoordinator Tests ---

    @Test
    fun autoPilotCoordinator_executesAllAgentsSequentially() {
        val dispatcher = FakeDispatcher()
        val coordinator = AutoPilotCoordinator(dispatcher)
        val stagesEncountered = mutableListOf<AgentStageUpdate>()

        val result = coordinator.executeSync(
            style = EditingStyle.ViralReel,
            customTitle = "VIRAL TEST 🚀",
            progressListener = { update -> stagesEncountered.add(update) },
        )

        assertTrue("AutoPilot should succeed", result.success)
        assertEquals(EditingStyle.ViralReel, result.style)
        assertEquals(5, result.agentResults.size)

        // Verify dispatcher received all calls in order
        assertTrue(dispatcher.calls.contains("removeSilence:-28.0"))
        assertTrue(dispatcher.calls.contains("fastBeatCuts:Punchy"))
        assertTrue(dispatcher.calls.contains("applyColorGrade:Teal & Orange"))
        assertTrue(dispatcher.calls.contains("dynamicZoom:push_in"))
        assertTrue(dispatcher.calls.contains("autoCaptions"))
        assertTrue(dispatcher.calls.contains("addTitle:VIRAL TEST 🚀:Title"))

        // Verify stages emitted
        val agentTypes = stagesEncountered.map { it.agentType }.distinct()
        assertTrue(agentTypes.contains(AgentType.DIRECTOR))
        assertTrue(agentTypes.contains(AgentType.CUTTER))
        assertTrue(agentTypes.contains(AgentType.COLORIST))
        assertTrue(agentTypes.contains(AgentType.MOTION))
        assertTrue(agentTypes.contains(AgentType.CAPTIONIST))

        // Verify progress monotonically reached 100%
        val lastUpdate = stagesEncountered.lastOrNull()
        assertNotNull(lastUpdate)
        assertEquals(100, lastUpdate?.totalProgressPercent)
    }

    @Test
    fun autoPilotCoordinator_cancellationStopsExecution() {
        val dispatcher = FakeDispatcher()
        val coordinator = AutoPilotCoordinator(dispatcher)

        coordinator.cancel()
        val result = coordinator.executeSync(EditingStyle.CinematicVlog)

        assertFalse(result.success)
        assertTrue(result.summaryFeedback.contains("cancelled", ignoreCase = true))
    }
}
