package com.video.engine.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AiTerminalConsolePanelTest {

    private lateinit var panel: AiTerminalConsolePanel

    @Before
    fun setUp() {
        panel = AiTerminalConsolePanel()
        // Use synchronous ui dispatcher for local unit tests
        panel.uiDispatcher = { action -> action() }
    }

    @Test
    fun testLogFormattingAndMicroTimestamps() {
        panel.appendLog(">", "Analyzing video...")
        val logs = panel.getLogs()
        assertEquals(1, logs.size)
        val entry = logs.first()
        assertEquals(">", entry.marker)
        assertEquals("Analyzing video...", entry.message)
        assertTrue(entry.timestampMs > 0L)

        val transcript = panel.getTranscript()
        // Micro-timestamp regex: [HH:mm:ss.SSS] > Analyzing video...
        val regex = Regex("""\[\d{2}:\d{2}:\d{2}\.\d{3}\] > Analyzing video\.\.\.""")
        assertTrue("Transcript '$transcript' should match regex", regex.containsMatchIn(transcript))
    }

    @Test
    fun testStepMarkerPrefixes_ExecutionListener() {
        // onStepStarted with total steps
        panel.onStepStarted(1, 4, "Analyzing video...")
        val log1 = panel.getLogs().last()
        assertEquals("> [1/4]", log1.marker)
        assertEquals("Analyzing video...", log1.message)

        // onStepStarted without total steps
        panel.onStepStarted(2, 0, "Trimming silence...")
        val log2 = panel.getLogs().last()
        assertEquals(">", log2.marker)
        assertEquals("Trimming silence...", log2.message)

        // onStepCompleted
        panel.onStepCompleted(2, "Stripped 6 silence gaps")
        val log3 = panel.getLogs().last()
        assertEquals("[✓]", log3.marker)
        assertEquals("Stripped 6 silence gaps", log3.message)

        // onExecutionError
        panel.onExecutionError("Model quota exceeded")
        val log4 = panel.getLogs().last()
        assertEquals("[!]", log4.marker)
        assertEquals("Model quota exceeded", log4.message)

        // onExecutionFinished
        panel.onExecutionFinished("Workflow completed successfully")
        val log5 = panel.getLogs().last()
        assertEquals("[✓]", log5.marker)
        assertEquals("Workflow completed successfully", log5.message)

        // onLogAppended
        panel.onLogAppended("[$]", "autopilot --viral")
        val log6 = panel.getLogs().last()
        assertEquals("[$]", log6.marker)
        assertEquals("autopilot --viral", log6.message)

        assertEquals(6, panel.getLogCount())
    }

    @Test
    fun testTranscriptBuffering_Max200Lines() {
        // Append 250 log entries to verify 200 line ring-buffer capping
        for (i in 1..250) {
            panel.appendLog(">", "Processing task #$i")
        }

        assertEquals(AiTerminalConsolePanel.MAX_TRANSCRIPT_LINES, panel.getLogCount())
        assertEquals(200, panel.getLogCount())

        val logs = panel.getLogs()
        // Oldest 50 entries (1..50) must have been evicted
        assertEquals("Processing task #51", logs.first().message)
        assertEquals("Processing task #250", logs.last().message)

        val transcriptLines = panel.getTranscript().lines()
        assertEquals(200, transcriptLines.size)
        assertTrue(transcriptLines.first().contains("Processing task #51"))
        assertTrue(transcriptLines.last().contains("Processing task #250"))
    }

    @Test
    fun testClearLogs() {
        panel.appendLog(">", "Step 1")
        panel.appendLog(">", "Step 2")
        panel.appendLog("[✓]", "Done")
        assertEquals(3, panel.getLogCount())

        panel.clearLogs()
        assertEquals(0, panel.getLogCount())
        assertTrue(panel.getTranscript().isEmpty())
        assertTrue(panel.getLogs().isEmpty())
    }

    @Test
    fun testCommandListenerDispatching() {
        var receivedCommand: String? = null
        panel.setCommandListener { cmd ->
            receivedCommand = cmd
        }

        panel.dispatchCommand("trim --silence")
        assertEquals("trim --silence", receivedCommand)

        // Verify command execution was recorded with [$] marker
        val lastLog = panel.getLogs().last()
        assertEquals("[$]", lastLog.marker)
        assertEquals("trim --silence", lastLog.message)
    }

    @Test
    fun testEmptyCommandNotDispatched() {
        var receivedCommand: String? = null
        panel.setCommandListener { cmd ->
            receivedCommand = cmd
        }

        panel.dispatchCommand("   ")
        assertEquals(null, receivedCommand)
        assertEquals(0, panel.getLogCount())
    }

    @Test
    fun testChipClickedDispatchesCommand() {
        var receivedCommand: String? = null
        panel.setCommandListener { cmd ->
            receivedCommand = cmd
        }

        panel.onChipClicked("lut --teal-orange")
        assertEquals("lut --teal-orange", receivedCommand)

        val lastLog = panel.getLogs().last()
        assertEquals("[$]", lastLog.marker)
        assertEquals("lut --teal-orange", lastLog.message)
    }

    @Test
    fun testThreadSafetyConcurrentLogging() {
        val threadCount = 8
        val logsPerThread = 50
        val latch = CountDownLatch(threadCount)

        for (t in 0 until threadCount) {
            Thread {
                try {
                    for (i in 0 until logsPerThread) {
                        panel.appendLog(">", "Thread-$t log $i")
                    }
                } finally {
                    latch.countDown()
                }
            }.start()
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        // 8 * 50 = 400 logs added, buffer capped at 200
        assertEquals(AiTerminalConsolePanel.MAX_TRANSCRIPT_LINES, panel.getLogCount())
        assertFalse(panel.getTranscript().isEmpty())
    }
}
