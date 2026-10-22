package com.video.engine.ai

/**
 * Event callback interface for streaming live execution steps and logs
 * from autonomous AI editing agents to the terminal console UI.
 */
interface AiExecutionListener {
    /**
     * Called when a new workflow step begins execution.
     * @param stepIndex 1-based or 0-based step index
     * @param totalSteps Total number of planned steps in the workflow
     * @param message Human-readable step description (e.g. "Analyzing video...")
     */
    fun onStepStarted(stepIndex: Int, totalSteps: Int, message: String)

    /**
     * Called when a workflow step completes successfully.
     * @param stepIndex Step index that completed
     * @param message Completion detail or action result
     */
    fun onStepCompleted(stepIndex: Int, message: String)

    /**
     * Called when an error occurs during step execution.
     * @param errorMessage Detailed failure description
     */
    fun onExecutionError(errorMessage: String)

    /**
     * Called when the complete autonomous workflow has finished executing.
     * @param summary Summary of total changes applied (e.g. "Applied 4 actions in 2.3s")
     */
    fun onExecutionFinished(summary: String)

    /**
     * Called when a generic or custom CLI log line is produced.
     * @param marker Step marker prefix (e.g. ">", "[✓]", "[!]", CLI command marker)
     * @param message Log message content
     */
    fun onLogAppended(marker: String, message: String)
}
