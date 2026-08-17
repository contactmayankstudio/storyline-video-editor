package com.video.engine.photo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.hardware.HardwareBuffer

object PhotoNativeBridge {
    
    // 3. Kotlin Coroutines for async execution (No UI freezing)
    suspend fun applyBackgroundRemovalAsync(buffer: HardwareBuffer): Boolean = withContext(Dispatchers.Default) {
        nativeApplyBackgroundRemoval(buffer)
    }

    // 1. HardwareBuffer for Zero-copy memory (No OOM errors)
    private external fun nativeApplyBackgroundRemoval(buffer: HardwareBuffer): Boolean
}
