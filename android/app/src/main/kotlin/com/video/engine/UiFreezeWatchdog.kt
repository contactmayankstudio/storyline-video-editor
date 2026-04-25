package com.video.engine

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

class UiFreezeWatchdog(
    private val freezeThresholdMs: Long = 4_500L,
    private val sampleIntervalMs: Long = 1_000L,
    private val onFreezeDetected: (Long) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val monitorThread = HandlerThread("ui-freeze-watchdog").apply { start() }
    private val monitorHandler = Handler(monitorThread.looper)
    private val running = AtomicBoolean(false)

    @Volatile private var lastMainTickAtMs = 0L
    @Volatile private var freezeReported = false

    private val mainTickRunnable = object : Runnable {
        override fun run() {
            lastMainTickAtMs = SystemClock.uptimeMillis()
            freezeReported = false
            if (running.get()) {
                mainHandler.postDelayed(this, sampleIntervalMs)
            }
        }
    }

    private val monitorRunnable = object : Runnable {
        override fun run() {
            if (!running.get()) return
            val stallMs = (SystemClock.uptimeMillis() - lastMainTickAtMs).coerceAtLeast(0L)
            if (stallMs >= freezeThresholdMs && !freezeReported) {
                freezeReported = true
                onFreezeDetected(stallMs)
            }
            monitorHandler.postDelayed(this, sampleIntervalMs)
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        lastMainTickAtMs = SystemClock.uptimeMillis()
        freezeReported = false
        mainHandler.removeCallbacks(mainTickRunnable)
        monitorHandler.removeCallbacks(monitorRunnable)
        mainHandler.post(mainTickRunnable)
        monitorHandler.postDelayed(monitorRunnable, sampleIntervalMs)
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        mainHandler.removeCallbacks(mainTickRunnable)
        monitorHandler.removeCallbacks(monitorRunnable)
    }

    fun close() {
        stop()
        monitorThread.quitSafely()
    }
}