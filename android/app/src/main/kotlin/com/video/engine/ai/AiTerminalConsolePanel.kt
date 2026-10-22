package com.video.engine.ai

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.video.engine.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Docked dark-themed CLI terminal console and text-only chat box for Storyline AI Copilot.
 * Streams real-time execution logs with micro-timestamps and distinct step markers,
 * completely replacing intrusive voice dialogs and guaranteeing zero occlusion of the video preview.
 */
open class AiTerminalConsolePanel @JvmOverloads constructor(
    context: Context? = null,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context ?: HeadlessContext(), attrs, defStyleAttr), AiExecutionListener {

    private class HeadlessContext : ContextWrapper(null)

    data class LogEntry(
        val marker: String,
        val message: String,
        val timestampMs: Long = System.currentTimeMillis()
    ) {
        fun toFormattedString(formatter: SimpleDateFormat): String {
            val timeStr = formatter.format(Date(timestampMs))
            return "[$timeStr] $marker $message"
        }
    }

    companion object {
        const val MAX_TRANSCRIPT_LINES = 200
        const val DEFAULT_TITLE = "AI VIDEO EDITOR CONSOLE // v2.0"
        const val STATUS_READY = "● READY"
        const val STATUS_RUNNING = "● RUNNING"
        const val STATUS_ERROR = "● ERROR"
    }

    private val logLock = Any()
    private val logEntries = ArrayList<LogEntry>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private var commandListener: ((String) -> Unit)? = null
    var onCloseListener: (() -> Unit)? = null
    internal var uiDispatcher: ((() -> Unit) -> Unit)? = null

    // UI View references
    private var terminalTitleText: TextView? = null
    private var terminalStatusBadge: TextView? = null
    private var terminalClearButton: TextView? = null
    private var terminalHistoryButton: TextView? = null
    private var terminalCloseButton: TextView? = null
    private var terminalLogRecyclerView: RecyclerView? = null
    private var terminalInputEditText: EditText? = null
    private var terminalRunButton: View? = null

    // Chips
    private var chipAutopilot: TextView? = null
    private var chipSilence: TextView? = null
    private var chipLut: TextView? = null
    private var chipBeatcut: TextView? = null
    private var chipCaptions: TextView? = null

    private var logAdapter: LogAdapter? = null

    init {
        if (context != null && context !is HeadlessContext) {
            LayoutInflater.from(context).inflate(R.layout.view_ai_terminal_console, this, true)
            bindViews(this)
        }
    }

    /**
     * Binds internal view references to the layout hierarchy and wires click/input listeners.
     */
    fun bindViews(root: View) {
        terminalTitleText = root.findViewById(R.id.terminalTitleText)
        terminalStatusBadge = root.findViewById(R.id.terminalStatusBadge)
        terminalClearButton = root.findViewById(R.id.terminalClearButton)
        terminalHistoryButton = root.findViewById(R.id.terminalHistoryButton)
        terminalCloseButton = root.findViewById(R.id.terminalCloseButton)
        terminalLogRecyclerView = root.findViewById(R.id.terminalLogRecyclerView)
        terminalInputEditText = root.findViewById(R.id.terminalInputEditText)
        terminalRunButton = root.findViewById(R.id.terminalRunButton)

        chipAutopilot = root.findViewById(R.id.chipAutopilot)
        chipSilence = root.findViewById(R.id.chipSilence)
        chipLut = root.findViewById(R.id.chipLut)
        chipBeatcut = root.findViewById(R.id.chipBeatcut)
        chipCaptions = root.findViewById(R.id.chipCaptions)

        // Setup RecyclerView
        terminalLogRecyclerView?.let { rv ->
            rv.layoutManager = LinearLayoutManager(rv.context).apply {
                stackFromEnd = true
            }
            logAdapter = LogAdapter(logEntries, timeFormat)
            rv.adapter = logAdapter
        }

        // Setup action buttons
        terminalClearButton?.setOnClickListener {
            clearLogs()
        }

        terminalHistoryButton?.setOnClickListener {
            appendLog("[!]", "Transcript buffer: ${getLogCount()} / $MAX_TRANSCRIPT_LINES lines loaded")
        }

        terminalCloseButton?.setOnClickListener {
            onCloseListener?.invoke() ?: run {
                visibility = View.GONE
            }
        }

        // Setup quick suggestion chips
        chipAutopilot?.setOnClickListener { onChipClicked("autopilot --viral") }
        chipSilence?.setOnClickListener { onChipClicked("trim --silence") }
        chipLut?.setOnClickListener { onChipClicked("lut --teal-orange") }
        chipBeatcut?.setOnClickListener { onChipClicked("beatcut --punchy") }
        chipCaptions?.setOnClickListener { onChipClicked("captions --auto") }

        // Setup RUN execution button
        terminalRunButton?.setOnClickListener {
            executeCurrentInput()
        }

        // Setup IME Action Send
        terminalInputEditText?.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                executeCurrentInput()
                true
            } else {
                false
            }
        }
    }

    /**
     * Executes the text currently present in the text-only chat input box.
     */
    fun executeCurrentInput() {
        val text = terminalInputEditText?.text?.toString()?.trim() ?: ""
        if (text.isNotEmpty()) {
            terminalInputEditText?.setText("")
            dispatchCommand(text)
        }
    }

    /**
     * Dispatches a command to the registered command listener and records it with command marker.
     */
    fun dispatchCommand(command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return
        appendLog("[$]", trimmed)
        commandListener?.invoke(trimmed)
    }

    /**
     * Handles clicking a CLI quick suggestion chip.
     */
    fun onChipClicked(commandText: String) {
        terminalInputEditText?.let { et ->
            et.setText(commandText)
            et.setSelection(commandText.length)
            et.requestFocus()
        } ?: run {
            dispatchCommand(commandText)
        }
    }

    /**
     * Appends a log line with the specified step marker and message.
     * Keeps history bounded to MAX_TRANSCRIPT_LINES (200 lines).
     */
    fun appendLog(marker: String, message: String) {
        val entry = LogEntry(marker = marker, message = message)
        val insertIndex: Int
        synchronized(logLock) {
            logEntries.add(entry)
            if (logEntries.size > MAX_TRANSCRIPT_LINES) {
                logEntries.removeAt(0)
            }
            insertIndex = logEntries.size - 1
        }

        safePost {
            val adapter = logAdapter
            val rv = terminalLogRecyclerView
            if (adapter != null && rv != null) {
                adapter.notifyItemInserted(insertIndex)
                rv.scrollToPosition(insertIndex)
            }
        }
    }

    /**
     * Clears all log entries from the buffer and refreshes UI.
     */
    fun clearLogs() {
        synchronized(logLock) {
            logEntries.clear()
        }
        safePost {
            logAdapter?.notifyDataSetChanged()
        }
    }

    /**
     * Returns full scrollback transcript as a single multiline string with timestamps and markers.
     */
    fun getTranscript(): String {
        synchronized(logLock) {
            return logEntries.joinToString("\n") { it.toFormattedString(timeFormat) }
        }
    }

    /**
     * Returns the total count of active log entries in the buffer.
     */
    fun getLogCount(): Int {
        synchronized(logLock) {
            return logEntries.size
        }
    }

    /**
     * Returns a snapshot of all log entries currently buffered.
     */
    fun getLogs(): List<LogEntry> {
        synchronized(logLock) {
            return ArrayList(logEntries)
        }
    }

    /**
     * Sets the callback invoked when user executes a command via RUN button or chips.
     */
    fun setCommandListener(listener: (String) -> Unit) {
        this.commandListener = listener
    }

    /**
     * Updates the status pill/badge in the terminal header.
     */
    fun setStatus(statusText: String, colorHex: String? = null) {
        safePost {
            terminalStatusBadge?.let { badge ->
                badge.text = statusText
                if (colorHex != null) {
                    try {
                        badge.setTextColor(Color.parseColor(colorHex))
                    } catch (_: Throwable) {
                        // ignore parse failure
                    }
                }
            }
        }
    }

    // ── AiExecutionListener Implementation ──

    override fun onStepStarted(stepIndex: Int, totalSteps: Int, message: String) {
        val stepPrefix = if (totalSteps > 0) "> [$stepIndex/$totalSteps]" else ">"
        appendLog(stepPrefix, message)
        setStatus(STATUS_RUNNING, "#00E5FF")
    }

    override fun onStepCompleted(stepIndex: Int, message: String) {
        appendLog("[✓]", message)
    }

    override fun onExecutionError(errorMessage: String) {
        appendLog("[!]", errorMessage)
        setStatus(STATUS_ERROR, "#F85149")
    }

    override fun onExecutionFinished(summary: String) {
        appendLog("[✓]", summary)
        setStatus(STATUS_READY, "#3FB950")
    }

    override fun onLogAppended(marker: String, message: String) {
        appendLog(marker, message)
    }

    // ── Helper for thread-safe UI updates ──

    private fun safePost(action: () -> Unit) {
        val dispatcher = uiDispatcher
        if (dispatcher != null) {
            dispatcher(action)
        } else {
            try {
                post(action)
            } catch (_: Throwable) {
                action()
            }
        }
    }

    // ── RecyclerView Adapter ──

    private class LogAdapter(
        private val entries: List<LogEntry>,
        private val timeFormat: SimpleDateFormat
    ) : RecyclerView.Adapter<LogViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
            val tv = TextView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                typeface = Typeface.MONOSPACE
                textSize = 11f
                setPadding(0, 2, 0, 2)
                setTextIsSelectable(true)
            }
            return LogViewHolder(tv)
        }

        override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
            if (position in entries.indices) {
                holder.bind(entries[position], timeFormat)
            }
        }

        override fun getItemCount(): Int = entries.size
    }

    private class LogViewHolder(private val textView: TextView) : RecyclerView.ViewHolder(textView) {
        fun bind(entry: LogEntry, timeFormat: SimpleDateFormat) {
            val timeStr = timeFormat.format(Date(entry.timestampMs))
            val fullText = "[$timeStr] ${entry.marker} ${entry.message}"
            val spannable = SpannableString(fullText)

            val markerColor = when {
                entry.marker.startsWith(">") -> parseColorSafe("#00E5FF", 0xFF00E5FF.toInt())
                entry.marker.contains("✓") -> parseColorSafe("#3FB950", 0xFF3FB950.toInt())
                entry.marker.contains("!") -> parseColorSafe("#D29922", 0xFFD29922.toInt())
                entry.marker.contains("$") -> parseColorSafe("#58A6FF", 0xFF58A6FF.toInt())
                else -> parseColorSafe("#F0F6FC", 0xFFF0F6FC.toInt())
            }

            val timeEnd = timeStr.length + 2
            if (timeEnd <= fullText.length) {
                spannable.setSpan(
                    ForegroundColorSpan(parseColorSafe("#8B949E", 0xFF8B949E.toInt())),
                    0,
                    timeEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            val markerStart = timeEnd + 1
            val markerEnd = markerStart + entry.marker.length
            if (markerEnd <= fullText.length) {
                spannable.setSpan(
                    ForegroundColorSpan(markerColor),
                    markerStart,
                    markerEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                spannable.setSpan(
                    StyleSpan(Typeface.BOLD),
                    markerStart,
                    markerEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            if (markerEnd < fullText.length) {
                spannable.setSpan(
                    ForegroundColorSpan(parseColorSafe("#F0F6FC", 0xFFF0F6FC.toInt())),
                    markerEnd,
                    fullText.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            textView.text = spannable
        }

        private fun parseColorSafe(colorStr: String, fallback: Int): Int {
            return try {
                Color.parseColor(colorStr)
            } catch (_: Throwable) {
                fallback
            }
        }
    }
}
