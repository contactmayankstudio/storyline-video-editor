package com.video.engine

/**
 * Callback interface for export operations.
 * Allows native code to report progress and completion back to Kotlin.
 */
interface ExportCallback {
    /**
     * Called when export progress updates.
     * @param progress Progress percentage (0-100)
     */
    fun onExportProgress(progress: Int)

    /**
     * Called when export completes (success or failure).
     * @param success True if export succeeded
     * @param outputPath The output file path if successful, null otherwise
     * @param error Error message if failed, null otherwise
     */
    fun onExportCompleted(success: Boolean, outputPath: String?, error: String?)
}