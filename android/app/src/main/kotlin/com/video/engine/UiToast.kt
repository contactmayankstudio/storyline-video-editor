package com.video.engine

import android.content.Context
import android.util.Log

/**
 * Editor-wide transient feedback is intentionally silent.
 *
 * The user asked to remove system toast popups from editing actions like
 * import, split, delete, and save. Keep the old Toast API shape so existing
 * call sites can stay simple, but route everything to logs instead of UI.
 */
object UiToast {
    private const val TAG = "[UI]"

    const val LENGTH_SHORT: Int = 0
    const val LENGTH_LONG: Int = 1

    fun makeText(
        context: Context,
        text: CharSequence,
        duration: Int,
    ): SilentToast {
        return SilentToast(text = text.toString(), duration = duration)
    }

    class SilentToast internal constructor(
        private val text: String,
        private val duration: Int,
    ) {
        fun show() {
            val durationLabel = if (duration == LENGTH_LONG) "long" else "short"
            Log.d(TAG, "Toast suppressed ($durationLabel): $text")
        }
    }
}
