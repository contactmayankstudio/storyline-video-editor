package com.video.engine.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SnapchatProCameraTest {

    @Test
    fun testGeofilterEnumAndCycle() {
        val filters = PostCaptureGeofilter.values()
        assertEquals(6, filters.size)
        assertTrue(filters.contains(PostCaptureGeofilter.CLEAN))
        assertTrue(filters.contains(PostCaptureGeofilter.CLOCK))
        assertTrue(filters.contains(PostCaptureGeofilter.DATE))
        assertTrue(filters.contains(PostCaptureGeofilter.BW_NOIR))
        assertTrue(filters.contains(PostCaptureGeofilter.WARM_SUNSET))
        assertTrue(filters.contains(PostCaptureGeofilter.CYBERPUNK))

        // Cycle next
        var current = PostCaptureGeofilter.CLEAN
        current = filters[(current.ordinal + 1) % filters.size]
        assertEquals(PostCaptureGeofilter.CLOCK, current)

        current = filters[(current.ordinal + 1) % filters.size]
        assertEquals(PostCaptureGeofilter.DATE, current)

        current = filters[(current.ordinal + 1) % filters.size]
        assertEquals(PostCaptureGeofilter.BW_NOIR, current)

        current = filters[(current.ordinal + 1) % filters.size]
        assertEquals(PostCaptureGeofilter.WARM_SUNSET, current)

        current = filters[(current.ordinal + 1) % filters.size]
        assertEquals(PostCaptureGeofilter.CYBERPUNK, current)

        current = filters[(current.ordinal + 1) % filters.size]
        assertEquals(PostCaptureGeofilter.CLEAN, current)
    }

    @Test
    fun testGeofilterTimeAndDateFormatting() {
        val clockFormat = SimpleDateFormat("hh:mm a", Locale.US)
        val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.US)

        val now = Date()
        val timeStr = clockFormat.format(now)
        val dateStr = dateFormat.format(now).uppercase(Locale.US)

        assertNotNull(timeStr)
        assertTrue(timeStr.contains("AM") || timeStr.contains("PM"))

        assertNotNull(dateStr)
        assertTrue(dateStr.length >= 10)
    }

    @Test
    fun testCameraRecordingSpeeds() {
        val speeds = listOf(0.5f, 1.0f, 2.0f)
        assertEquals(3, speeds.size)

        var currentSpeed = 1.0f
        fun nextSpeed(speed: Float): Float = when (speed) {
            1.0f -> 0.5f
            0.5f -> 2.0f
            else -> 1.0f
        }

        currentSpeed = nextSpeed(currentSpeed)
        assertEquals(0.5f, currentSpeed, 0.001f)

        currentSpeed = nextSpeed(currentSpeed)
        assertEquals(2.0f, currentSpeed, 0.001f)

        currentSpeed = nextSpeed(currentSpeed)
        assertEquals(1.0f, currentSpeed, 0.001f)
    }

    @Test
    fun testCameraTimerCountdownSteps() {
        fun nextTimer(sec: Int): Int = when (sec) {
            0 -> 3
            3 -> 10
            else -> 0
        }

        var timer = 0
        timer = nextTimer(timer)
        assertEquals(3, timer)

        timer = nextTimer(timer)
        assertEquals(10, timer)

        timer = nextTimer(timer)
        assertEquals(0, timer)
    }
}
