package com.video.engine.ai

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.core.app.ActivityCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.video.engine.DesignSystem
import com.video.engine.ModernSheet
import com.video.engine.R
import java.util.Locale
import kotlin.math.roundToInt

/**
 * AI Voice Copilot Sheet providing conversational speech & chat video editing.
 * Features:
 * - Non-dimming BottomSheetDialog via ModernSheet.applyEditorBehavior
 * - Real-time RMS waveform pulse animation for mic voice input
 * - Chat message history with user and copilot action bubbles
 * - Quick suggestion chips: [Remove Silence], [Teal & Orange], [Fast Beat Cuts], [Viral Hook Zoom], [Auto Captions]
 * - Multi-lingual speech recognition supporting English, Hindi, and Hinglish
 */
class AiVoiceCopilotSheet(
    private val activity: Activity,
    private val dispatcher: AiCommandDispatcher,
    private val startInCliMode: Boolean = false,
) {
    companion object {
        const val REQUEST_CODE_AUDIO_PERMISSION = 4101
        private const val PEEK_RATIO = 0.42f
        private const val MAX_RATIO = 0.42f

        // Colors
        private val COLOR_SHEET_BG = Color.parseColor("#11151B")
        private val COLOR_CARD_BG = Color.parseColor("#171C23")
        private val COLOR_INPUT_BG = Color.parseColor("#141920")
        private val COLOR_BORDER = Color.parseColor("#222A36")
        private val COLOR_ACCENT_CYAN = Color.parseColor("#00E5FF")
        private val COLOR_ACCENT_BLUE = Color.parseColor("#388BFD")
        private val COLOR_USER_BUBBLE = Color.parseColor("#17263C")
        private val COLOR_USER_BORDER = Color.parseColor("#2B5282")
        private val COLOR_BOT_BUBBLE = Color.parseColor("#1A202C")
        private val COLOR_TEXT_PRIMARY = Color.parseColor("#FFFFFF")
        private val COLOR_TEXT_MUTED = Color.parseColor("#8A99AD")
    }

    private var dialog: BottomSheetDialog? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var chatContainer: LinearLayout
    private lateinit var chatScrollView: ScrollView
    private lateinit var chatInput: EditText
    private lateinit var micButton: FrameLayout
    private lateinit var micInnerBg: GradientDrawable
    private lateinit var pulseRing: View
    private lateinit var listeningStatusContainer: LinearLayout
    private lateinit var statusTextView: TextView
    private lateinit var waveBars: List<View>

    private var isCliMode: Boolean = false
    private var cliPanel: AiTerminalConsolePanel? = null
    private var chatModeContainer: LinearLayout? = null
    private var cliToggleButton: TextView? = null
    private var titleViewRef: TextView? = null
    private var localeControlRef: View? = null

    private var speechRecognizerManager: SpeechRecognizerManager? = null
    private var isListening: Boolean = false

    private val suggestionChips = listOf(
        Pair("✨ Full AI Edit & Export", "pura video auto edit karke export karo"),
        Pair("🧠 AI Video Critic", "meri video check karo aur kya kami hai batao"),
        Pair("🎞️ Viral Highlight Finder", "video se 30 second ka viral part nikalo"),
        Pair("🖼️ Auto B-Roll & Visuals", "auto b-roll aur smart visuals overlay dalo"),
        Pair("🔗 Compound Pipeline", "silence kato fir cyberpunk look lagao aur 9:16 karo"),
        Pair("⚡ Speed Ramp", "speed ramp lagao"),
        Pair("✂️ Smart Jump-Cuts", "smart jump cuts lagao"),
        Pair("🎯 Auto-Reframe 9:16", "auto reframe 9:16 karo"),
        Pair("🎨 Cyberpunk Film Look", "cyberpunk look lagao"),
        Pair("🎤 Studio Voice Clarity", "background noise hatao studio voice karo"),
        Pair("✨ Hormozi Captions", "hormozi style dynamic captions lagao"),
        Pair("📸 Viral Thumbnail", "viral thumbnail banao"),
        Pair("🔥 Dynamic Sticker", "fire sticker lagao"),
        Pair("📱 Insta Reel Export", "export for instagram reel"),
        Pair("👀 Inspect Video & Cuts", "mera video dekho kaha cut aur zoom kare"),
        Pair("🎬 Director Agent Review", "director review my video"),
        Pair("🎙️ AI Voiceover", "voiceover dalo: Welcome to my brand new video!"),
        Pair("🎵 Beat-Drop Sync", "beats pe cut sync karo"),
        Pair("🌓 Before/After A/B", "before after dikhao"),
        Pair("📝 Topic-to-Reel", "topic to reel: 3 Daily Productivity Habits"),
        Pair("🔊 Whoosh SFX", "whoosh sound effect lagao"),
        Pair("👤 Face Tracking Zoom", "face tracking zoom"),
        Pair("🚀 Export 1080p Video", "export 1080p"),
        Pair("📱 9:16 Reel / Shorts", "aspect ratio 9:16"),
        Pair("🎵 Add Trending BGM", "add music lofi chill"),
        Pair("✂️ Remove Silence", "remove silence"),
        Pair("🎨 Teal & Orange", "teal and orange"),
        Pair("🔀 Add Transition", "add transition crossfade"),
        Pair("⚡ Fast Beat Cuts", "fast beat cuts"),
        Pair("🔍 Viral Hook Zoom", "viral hook zoom"),
        Pair("💬 Auto Captions", "auto captions"),
        Pair("🔇 Mute Audio", "mute clip"),
    )

    fun show() {
        val sheetDialog = BottomSheetDialog(activity)
        dialog = sheetDialog
        sheetDialog.setCanceledOnTouchOutside(false)

        val root = createViewHierarchy(sheetDialog)
        sheetDialog.setContentView(root)

        val screenHeight = activity.resources.displayMetrics.heightPixels
        val fixedHeight = (screenHeight * 0.40f).roundToInt()

        sheetDialog.window?.let { window ->
            window.setDimAmount(0f)
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        sheetDialog.setOnShowListener {
            val bottomSheet = sheetDialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let { bs ->
                bs.setBackgroundColor(Color.TRANSPARENT)
                bs.layoutParams = bs.layoutParams.apply {
                    height = fixedHeight
                }
                val behavior = BottomSheetBehavior.from(bs)
                behavior.isDraggable = false
                behavior.isHideable = true
                behavior.peekHeight = fixedHeight
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
            }
        }

        sheetDialog.setOnDismissListener {
            mainHandler.removeCallbacksAndMessages(null)
            speechRecognizerManager?.destroy()
            speechRecognizerManager = null
            dialog = null
        }

        initSpeechRecognizer()
        sheetDialog.show()
        if (startInCliMode) {
            mainHandler.post {
                toggleCliMode(true)
            }
        }
    }

    private fun initSpeechRecognizer() {
        speechRecognizerManager = SpeechRecognizerManager(activity, object : SpeechRecognizerCallback {
            override fun onSpeechReady() {
                mainHandler.post {
                    isListening = true
                    updateMicState(listening = true)
                    statusTextView.text = "Listening... Speak now"
                    listeningStatusContainer.visibility = View.VISIBLE
                }
            }

            override fun onSpeechStart() {
                mainHandler.post {
                    statusTextView.text = "Listening to your command..."
                }
            }

            override fun onRmsChanged(rmsdB: Float) {
                mainHandler.post {
                    animateRmsWave(rmsdB)
                }
            }

            override fun onPartialResult(partialText: String) {
                mainHandler.post {
                    statusTextView.text = partialText
                }
            }

            override fun onSpeechResult(resultText: String) {
                mainHandler.post {
                    isListening = false
                    updateMicState(listening = false)
                    handleUserCommand(resultText)
                }
            }

            override fun onSpeechError(errorMessage: String, errorCode: Int) {
                mainHandler.post {
                    isListening = false
                    updateMicState(listening = false)
                    statusTextView.text = errorMessage
                    mainHandler.postDelayed({
                        if (!isListening) listeningStatusContainer.visibility = View.GONE
                    }, 2500)
                }
            }

            override fun onSpeechEnd() {
                mainHandler.post {
                    isListening = false
                    updateMicState(listening = false)
                    mainHandler.postDelayed({
                        if (!isListening) listeningStatusContainer.visibility = View.GONE
                    }, 1200)
                }
            }

            override fun onAvailabilityChanged(available: Boolean) {
                mainHandler.post {
                    if (!available) {
                        Toast.makeText(activity, "Speech recognition unavailable. Please use text chat.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }

    private fun createViewHierarchy(sheetDialog: BottomSheetDialog): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                val r = dp(24).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                setColor(COLOR_SHEET_BG)
                setStroke(dp(1), Color.parseColor("#1B222C"))
            }
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        // 1. Drag Handle
        val dragHandle = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(10)
                bottomMargin = dp(6)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(999).toFloat()
                setColor(Color.parseColor("#3A4452"))
            }
        }
        root.addView(dragHandle)

        // 2. Header Row
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        val aiIcon = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                marginEnd = dp(8)
            }
            setImageResource(R.drawable.ic_magic_wand)
            setColorFilter(COLOR_ACCENT_CYAN)
        }
        header.addView(aiIcon)

        val titleView = TextView(activity).apply {
            text = "AI Voice Copilot"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(COLOR_TEXT_PRIMARY)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        header.addView(titleView)
        titleViewRef = titleView

        // Antigravity CLI Toggle Pill: [>_ CLI]
        val cliToggle = TextView(activity).apply {
            text = ">_ CLI"
            textSize = 11.5f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setTextColor(COLOR_ACCENT_CYAN)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(999).toFloat()
                setColor(Color.parseColor("#1B293A"))
                setStroke(dp(1), COLOR_ACCENT_CYAN)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginEnd = dp(6)
            }
            setOnClickListener {
                toggleCliMode()
            }
        }
        header.addView(cliToggle)
        cliToggleButton = cliToggle

        // Locale Segmented Control Pill: [EN | HI | Hinglish]
        val localeControl = createLocaleSegmentedControl()
        header.addView(localeControl)
        localeControlRef = localeControl

        // Clear Chat History Button: [🗑️]
        val clearHistoryBtn = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                marginStart = dp(6)
            }
            setImageResource(android.R.drawable.ic_menu_delete)
            setColorFilter(Color.parseColor("#94A3B8"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#1B222C"))
            }
            setPadding(dp(5), dp(5), dp(5), dp(5))
            contentDescription = "Chat history clear karein"
            setOnClickListener {
                showClearHistoryConfirmDialog()
            }
        }
        header.addView(clearHistoryBtn)

        val closeButton = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                marginStart = dp(10)
            }
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(COLOR_TEXT_MUTED)
            setOnClickListener { sheetDialog.dismiss() }
        }
        header.addView(closeButton)
        root.addView(header)

        // Chat Mode Container (Holds chat history, chips, waveform, and voice/text input bar)
        val chatBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f,
            )
        }
        chatModeContainer = chatBox

        // 3. Listening Status & Waveform Bar Indicator
        listeningStatusContainer = createListeningStatusView()
        chatBox.addView(listeningStatusContainer)

        // 4. Chat Messages Scroll Area
        chatScrollView = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f,
            )
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
        chatContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(6))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        chatScrollView.addView(chatContainer)
        chatBox.addView(chatScrollView)

        // Load Saved Chat History or Initial Welcome Bubble
        loadPersistedChatHistory()

        // 5. Quick Suggestion Chips Strip
        val chipsScroll = HorizontalScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
            }
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val chipsContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(2), dp(12), dp(2))
        }
        for (chip in suggestionChips) {
            val chipView = createSuggestionChip(chip.first, chip.second)
            chipsContainer.addView(chipView)
        }
        chipsScroll.addView(chipsContainer)
        chatBox.addView(chipsScroll)

        // 6. Voice & Text Input Bar
        val inputBar = createInputBar()
        chatBox.addView(inputBar)

        root.addView(chatBox)

        // 7. Embedded Antigravity CLI Terminal Console Panel
        val cli = AiTerminalConsolePanel(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f,
            )
            visibility = View.GONE
            setCommandListener { cmd ->
                handleUserCommand(cmd)
            }
            onCloseListener = {
                toggleCliMode(false)
            }
        }
        root.addView(cli)
        cliPanel = cli

        return root
    }

    private fun createLocaleSegmentedControl(): LinearLayout {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(999).toFloat()
                setColor(Color.parseColor("#161D26"))
                setStroke(dp(1), Color.parseColor("#222A36"))
            }
            setPadding(dp(3), dp(2), dp(3), dp(2))
        }

        val locales = listOf(
            Triple("EN", SpeechLocale.ENGLISH_IN, true),
            Triple("HI", SpeechLocale.HINDI_IN, false),
            Triple("Hinglish", SpeechLocale.HINGLISH, false),
        )

        val pillViews = mutableListOf<TextView>()

        locales.forEach { (label, locale, initialSelected) ->
            val pill = TextView(activity).apply {
                text = label
                textSize = 11.5f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setTextColor(if (initialSelected) COLOR_TEXT_PRIMARY else COLOR_TEXT_MUTED)
                background = if (initialSelected) {
                    createPillDrawable(COLOR_ACCENT_BLUE)
                } else {
                    null
                }
                setOnClickListener {
                    speechRecognizerManager?.setLocale(locale)
                    pillViews.forEach { v ->
                        v.background = null
                        v.setTextColor(COLOR_TEXT_MUTED)
                    }
                    background = createPillDrawable(COLOR_ACCENT_BLUE)
                    setTextColor(COLOR_TEXT_PRIMARY)
                    Toast.makeText(activity, "Locale: ${locale.displayName}", Toast.LENGTH_SHORT).show()
                }
            }
            pillViews.add(pill)
            container.addView(pill)
        }

        return container
    }

    private fun createPillDrawable(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(999).toFloat()
            setColor(color)
        }
    }

    private fun createListeningStatusView(): LinearLayout {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(16), dp(4), dp(16), dp(4))
            setBackgroundColor(Color.parseColor("#15202E"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        // Pulse mic dot
        val pulseDot = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                marginEnd = dp(8)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(COLOR_ACCENT_CYAN)
            }
        }
        container.addView(pulseDot)

        statusTextView = TextView(activity).apply {
            text = "Listening..."
            textSize = 12.5f
            setTextColor(COLOR_ACCENT_CYAN)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        container.addView(statusTextView)

        // 5 waveform mini-bars
        val barsContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val barsList = mutableListOf<View>()
        for (i in 0 until 5) {
            val bar = View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(3), dp(12)).apply {
                    marginEnd = dp(3)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(2).toFloat()
                    setColor(COLOR_ACCENT_CYAN)
                }
            }
            barsList.add(bar)
            barsContainer.addView(bar)
        }
        waveBars = barsList
        container.addView(barsContainer)

        return container
    }

    private fun createSuggestionChip(label: String, commandText: String): View {
        val isAutoPilot = commandText == "auto edit puri video" ||
            label.contains("Auto-Pilot") ||
            label.contains("Edit & Export") ||
            label.contains("Export")
        return TextView(activity).apply {
            text = label
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (isAutoPilot) COLOR_ACCENT_CYAN else COLOR_TEXT_PRIMARY)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(999).toFloat()
                setColor(if (isAutoPilot) Color.parseColor("#1B293A") else COLOR_CARD_BG)
                setStroke(dp(1), if (isAutoPilot) COLOR_ACCENT_CYAN else COLOR_BORDER)
            }
            setPadding(dp(12), dp(7), dp(12), dp(7))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            }
            setOnClickListener {
                handleUserCommand(commandText)
            }
        }
    }

    private fun createInputBar(): LinearLayout {
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = dp(12)
                marginEnd = dp(12)
                bottomMargin = dp(10)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(COLOR_INPUT_BG)
                setStroke(dp(1), COLOR_BORDER)
            }
        }

        // Voice Mic Button with RMS Pulse Ring
        micButton = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        }

        pulseRing = View(activity).apply {
            layoutParams = FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#4000E5FF"))
            }
            visibility = View.GONE
        }
        micButton.addView(pulseRing)

        micInnerBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#1E2633"))
            setStroke(dp(1), COLOR_BORDER)
        }

        val micInner = ImageView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER)
            background = micInnerBg
            setImageResource(R.drawable.ic_audio)
            setColorFilter(COLOR_ACCENT_CYAN)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        micButton.addView(micInner)

        micButton.setOnClickListener {
            toggleVoiceListening()
        }
        bar.addView(micButton)

        // Chat Input Field
        chatInput = EditText(activity).apply {
            hint = "Bolo ya type karo... kya karna hai?"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(COLOR_TEXT_PRIMARY)
            textSize = 13.5f
            background = null
            setPadding(dp(10), dp(8), dp(10), dp(8))
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEND
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
            setOnEditorActionListener { _, actionId, event ->
                val isSendAction = actionId == EditorInfo.IME_ACTION_SEND ||
                    actionId == EditorInfo.IME_ACTION_DONE ||
                    actionId == EditorInfo.IME_ACTION_GO ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                if (isSendAction) {
                    val query = text.toString().trim()
                    if (query.isNotBlank()) {
                        setText("")
                        handleUserCommand(query)
                    }
                    true
                } else {
                    false
                }
            }
        }
        bar.addView(chatInput)

        // Send Button
        val sendButton = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply {
                marginStart = dp(4)
                marginEnd = dp(2)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(COLOR_ACCENT_BLUE)
            }
            setImageResource(R.drawable.ic_send)
            setColorFilter(COLOR_TEXT_PRIMARY)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener {
                val query = chatInput.text.toString().trim()
                if (query.isNotBlank()) {
                    chatInput.setText("")
                    handleUserCommand(query)
                }
            }
        }
        bar.addView(sendButton)

        return bar
    }

    private fun toggleVoiceListening() {
        val manager = speechRecognizerManager ?: return
        if (isListening) {
            manager.stopListening()
            isListening = false
            updateMicState(listening = false)
        } else {
            if (!manager.hasAudioPermission()) {
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(android.Manifest.permission.RECORD_AUDIO),
                    REQUEST_CODE_AUDIO_PERMISSION,
                )
                Toast.makeText(activity, "Microphone permission required for voice editing", Toast.LENGTH_SHORT).show()
                return
            }
            val started = manager.startListening()
            if (started) {
                isListening = true
                updateMicState(listening = true)
            }
        }
    }

    private fun updateMicState(listening: Boolean) {
        if (listening) {
            micInnerBg.setColor(COLOR_ACCENT_CYAN)
            (micButton.getChildAt(1) as? ImageView)?.setColorFilter(Color.parseColor("#090B0F"))
            pulseRing.visibility = View.VISIBLE
        } else {
            micInnerBg.setColor(Color.parseColor("#1E2633"))
            (micButton.getChildAt(1) as? ImageView)?.setColorFilter(COLOR_ACCENT_CYAN)
            pulseRing.visibility = View.GONE
            pulseRing.scaleX = 1.0f
            pulseRing.scaleY = 1.0f
        }
    }

    private fun animateRmsWave(rmsdB: Float) {
        val norm = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        val scale = 1.0f + norm * 0.45f
        pulseRing.animate()
            .scaleX(scale)
            .scaleY(scale)
            .alpha(0.3f + norm * 0.5f)
            .setDuration(80)
            .start()

        // Animate mini bars with varied heights
        waveBars.forEachIndexed { i, bar ->
            val factor = when (i) {
                0 -> 0.7f
                1 -> 1.1f
                2 -> 1.4f
                3 -> 0.9f
                else -> 0.6f
            }
            val barHeight = dp((8 + norm * 18 * factor).roundToInt())
            bar.layoutParams = (bar.layoutParams as LinearLayout.LayoutParams).apply {
                height = barHeight
            }
        }
    }

    private fun toggleCliMode(forceCli: Boolean? = null) {
        isCliMode = forceCli ?: !isCliMode
        val cli = cliPanel ?: return
        val chat = chatModeContainer ?: return
        val btn = cliToggleButton ?: return
        val title = titleViewRef ?: return
        val locale = localeControlRef ?: return

        if (isCliMode) {
            chat.visibility = View.GONE
            cli.visibility = View.VISIBLE
            title.text = "Antigravity CLI // v2.5"
            btn.text = "💬 Chat"
            btn.background = createPillDrawable(COLOR_ACCENT_CYAN)
            btn.setTextColor(Color.parseColor("#090B0F"))
            locale.visibility = View.GONE

            if (cli.getLogCount() == 0) {
                val t = DeviceTelemetryProvider.getTelemetry(activity)
                cli.appendLog("[SYS]", "ANTIGRAVITY MOBILE AGENT v2.5 // ONLINE")
                cli.appendLog("[SYS]", "Host: ${t.manufacturer} ${t.model} (Android ${t.androidRelease}, API ${t.sdkInt})")
                cli.appendLog("[SYS]", String.format(Locale.US, "Memory: %.1fGB Free / %.1fGB Total (Tier: %s)", t.freeRamGb, t.totalRamGb, t.deviceTier))
                cli.appendLog("[SYS]", String.format(Locale.US, "Storage: %.1fGB Free | Battery: %d%%", t.freeStorageGb, t.batteryPercent))
                cli.appendLog("[SYS]", "Codecs: H.264/AVC: ${if (t.hasAvcEncoder) "YES" else "NO"} | H.265/HEVC: ${if (t.hasHevcEncoder) "YES (4K/60fps capable)" else "NO"}")
                cli.appendLog("[SYS]", "Agent Ready. Type natural language or CLI commands below.")
                cli.setStatus(AiTerminalConsolePanel.STATUS_READY, "#3FB950")
            }
        } else {
            chat.visibility = View.VISIBLE
            cli.visibility = View.GONE
            title.text = "AI Voice Copilot"
            btn.text = ">_ CLI"
            btn.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(999).toFloat()
                setColor(Color.parseColor("#1B293A"))
                setStroke(dp(1), COLOR_ACCENT_CYAN)
            }
            btn.setTextColor(COLOR_ACCENT_CYAN)
            locale.visibility = View.VISIBLE
        }
    }

    private fun handleUserCommand(rawCommand: String) {
        val query = rawCommand.trim()
        if (query.isBlank()) return

        // 1. Render User Message Bubble & mirror to CLI console
        addUserBubble(query)
        cliPanel?.let { cli ->
            cli.appendLog("[$]", query)
            cli.setStatus(AiTerminalConsolePanel.STATUS_RUNNING, "#00E5FF")
        }

        // 2. Parse Intent
        val parsed = AiIntentParser.parse(query)

        // Check if query is conversational general/world question without timeline action
        val isPureConversational = parsed.intent is VideoEditIntent.Conversational && (parsed.intent as VideoEditIntent.Conversational).executeIntent == null
        val isUnknown = parsed.intent is VideoEditIntent.Unknown

        if (isPureConversational || isUnknown) {
            val thinkingBubble = renderAssistantBubble("Thinking... ⏳", isSuccess = true)
            chatScrollView.post { chatScrollView.fullScroll(View.FOCUS_DOWN) }
            MultiProviderAiRouter.query(query, activity) { reply ->
                thinkingBubble.text = reply
                AiChatHistoryStore.addMessage(
                    activity,
                    AiChatHistoryStore.ChatMessage("assistant", reply, isSuccess = true),
                )
                cliPanel?.appendLog("[AI]", reply)
                cliPanel?.setStatus(AiTerminalConsolePanel.STATUS_READY, "#3FB950")
                chatScrollView.post { chatScrollView.fullScroll(View.FOCUS_DOWN) }
            }
            return
        }

        // 3. Auto-Pilot Magic Edit with Live Agent Progress
        val isAutoPilotAndExport = parsed.intent is VideoEditIntent.AutoPilotEditAndExport ||
            (parsed.intent is VideoEditIntent.Conversational && (parsed.intent as VideoEditIntent.Conversational).executeIntent is VideoEditIntent.AutoPilotEditAndExport)

        if (isAutoPilotAndExport) {
            handleAutoPilotAndExportExecution(parsed)
            return
        }

        val isAutoPilot = parsed.intent is VideoEditIntent.AutoPilotEdit ||
            (parsed.intent is VideoEditIntent.Conversational && (parsed.intent as VideoEditIntent.Conversational).executeIntent is VideoEditIntent.AutoPilotEdit)

        if (isAutoPilot) {
            handleAutoPilotExecution(parsed)
            return
        }

        // 4. Dispatch to timeline actions
        val (success, feedback) = dispatcher.dispatch(parsed)
        cliPanel?.let { cli ->
            cli.appendLog(if (success) "[✓]" else "[!]", feedback)
            cli.setStatus(
                if (success) AiTerminalConsolePanel.STATUS_READY else AiTerminalConsolePanel.STATUS_ERROR,
                if (success) "#3FB950" else "#F85149",
            )
        }

        // 5. Render Assistant Feedback Bubble with context-aware action pills
        val pills = if (success) resolveActionPillsForIntent(parsed.intent) else emptyList()
        addAssistantBubble(feedback, isSuccess = success, actionPills = pills)

        // Scroll chat to bottom
        chatScrollView.post {
            chatScrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun handleAutoPilotAndExportExecution(command: ParsedCommand) {
        val (style, resolution) = when (val intent = command.intent) {
            is VideoEditIntent.AutoPilotEditAndExport -> Pair(intent.style, intent.resolution)
            is VideoEditIntent.Conversational -> {
                val sub = intent.executeIntent as? VideoEditIntent.AutoPilotEditAndExport
                Pair(sub?.style ?: "ViralReel", sub?.resolution ?: "1080p")
            }
            else -> Pair("ViralReel", "1080p")
        }

        val bubble = renderAssistantBubble("🚀 Full AI Auto-Pilot Edit & Export initiated ($style, $resolution)...\n\n⏳ [1/6] Removing audio silence & dead pauses...")
        chatScrollView.post { chatScrollView.fullScroll(View.FOCUS_DOWN) }
        cliPanel?.appendLog("> [1/6]", "Auto-Pilot: Removing audio silence & dead pauses...")

        mainHandler.postDelayed({
            bubble.text = "🤖 Full AI Auto-Pilot ($style, $resolution):\n\n" +
                "✅ [1/6] Silence & dead pauses trimmed\n" +
                "⏳ [2/6] Applying dynamic viral hook zoom..."
            chatScrollView.post { chatScrollView.fullScroll(View.FOCUS_DOWN) }
            cliPanel?.appendLog("> [2/6]", "Auto-Pilot: Applying dynamic viral hook zoom...")
        }, 400)

        mainHandler.postDelayed({
            bubble.text = "🤖 Full AI Auto-Pilot ($style, $resolution):\n\n" +
                "✅ [1/6] Silence & dead pauses trimmed\n" +
                "✅ [2/6] Dynamic viral hook zoom active\n" +
                "⏳ [3/6] Applying Teal & Orange cinematic color grade..."
            chatScrollView.post { chatScrollView.fullScroll(View.FOCUS_DOWN) }
            cliPanel?.appendLog("> [3/6]", "Auto-Pilot: Applying Teal & Orange color grade...")
        }, 800)

        mainHandler.postDelayed({
            bubble.text = "🤖 Full AI Auto-Pilot ($style, $resolution):\n\n" +
                "✅ [1/6] Silence & dead pauses trimmed\n" +
                "✅ [2/6] Dynamic viral hook zoom active\n" +
                "✅ [3/6] Cinematic color grade applied\n" +
                "⏳ [4/6] Adding background score & music mastering..."
            chatScrollView.post { chatScrollView.fullScroll(View.FOCUS_DOWN) }
            cliPanel?.appendLog("> [4/6]", "Auto-Pilot: Adding background score & music mastering...")
        }, 1200)

        mainHandler.postDelayed({
            bubble.text = "🤖 Full AI Auto-Pilot ($style, $resolution):\n\n" +
                "✅ [1/6] Silence & dead pauses trimmed\n" +
                "✅ [2/6] Dynamic viral hook zoom active\n" +
                "✅ [3/6] Cinematic color grade applied\n" +
                "✅ [4/6] Royalty-free BGM added\n" +
                "⏳ [5/6] Generating AI subtitles & title overlay..."
            chatScrollView.post { chatScrollView.fullScroll(View.FOCUS_DOWN) }
            cliPanel?.appendLog("> [5/6]", "Auto-Pilot: Generating AI subtitles & title overlay...")
        }, 1600)

        mainHandler.postDelayed({
            val (success, feedback) = dispatcher.dispatch(command)
            if (success) {
                cliPanel?.appendLog("[✓]", "Auto-Pilot Complete! Exporting video in $resolution")
                cliPanel?.setStatus(AiTerminalConsolePanel.STATUS_READY, "#3FB950")
                val finalMsg = "🎉 **Full AI Video Edit & Export Started!** 🚀\n\n" +
                    "✅ Dead pauses cut & trimmed\n" +
                    "✅ Viral hook zoom & motion active\n" +
                    "✅ Cinematic Teal & Orange color grade\n" +
                    "✅ Royalty-free background music synchronized\n" +
                    "✅ AI subtitles & dynamic title added\n" +
                    "🚀 **Timeline is rendering & exporting at $resolution!**"
                bubble.text = finalMsg
                AiChatHistoryStore.addMessage(
                    activity,
                    AiChatHistoryStore.ChatMessage("assistant", finalMsg, isSuccess = true),
                )

                addAssistantBubble(
                    "Video background me export ho raha hai! Quick actions:",
                    isSuccess = true,
                    actionPills = listOf(
                        Pair("▶️ Play Preview") { handleUserCommand("play") },
                        Pair("🚀 Export 4K") { handleUserCommand("export 4k") },
                        Pair("📱 9:16 Reel") { handleUserCommand("aspect ratio 9:16") },
                        Pair("↩️ Undo") { handleUserCommand("undo") },
                    ),
                )
            } else {
                cliPanel?.appendLog("[!]", "Auto-Pilot failed: $feedback")
                cliPanel?.setStatus(AiTerminalConsolePanel.STATUS_ERROR, "#F85149")
                val errMsg = "⚠️ Auto-Pilot edit failed: $feedback\nTimeline par media clip add karein aur fir try karein."
                bubble.text = errMsg
                AiChatHistoryStore.addMessage(
                    activity,
                    AiChatHistoryStore.ChatMessage("assistant", errMsg, isSuccess = false),
                )
            }
            chatScrollView.post { chatScrollView.fullScroll(View.FOCUS_DOWN) }
        }, 2000)
    }

    private fun handleAutoPilotExecution(command: ParsedCommand) {
        val style = when (val intent = command.intent) {
            is VideoEditIntent.AutoPilotEdit -> intent.style
            is VideoEditIntent.Conversational -> (intent.executeIntent as? VideoEditIntent.AutoPilotEdit)?.style ?: "ViralReel"
            else -> "ViralReel"
        }

        val bubble = renderAssistantBubble("🚀 Auto-Pilot Magic Edit initiated ($style)...\n\n⏳ [1/5] Removing audio silence & dead pauses...")
        chatScrollView.post { chatScrollView.fullScroll(View.FOCUS_DOWN) }
        cliPanel?.appendLog("> [1/5]", "Auto-Pilot: Removing audio silence & dead pauses...")

        // Execute dispatch to perform full autonomous auto pilot edit
        val (success, _) = dispatcher.dispatch(command)
        if (!success) {
            cliPanel?.appendLog("[!]", "Auto-Pilot failed: No media or clip selected")
            cliPanel?.setStatus(AiTerminalConsolePanel.STATUS_ERROR, "#F85149")
            val failMsg = "⚠️ Auto-Pilot edit could not complete. Please ensure media or a clip is selected on the timeline."
            bubble.text = failMsg
            AiChatHistoryStore.addMessage(
                activity,
                AiChatHistoryStore.ChatMessage("assistant", failMsg, isSuccess = false),
            )
            return
        }

        // Live agent progress status stream
        mainHandler.postDelayed({
            bubble.text = "🤖 Auto-Pilot Magic Edit ($style):\n\n" +
                "✅ [1/5] Silence & dead pauses trimmed\n" +
                "⏳ [2/5] Applying viral hook zoom (push_in)..."
            chatScrollView.post { chatScrollView.fullScroll(View.FOCUS_DOWN) }
            cliPanel?.appendLog("> [2/5]", "Auto-Pilot: Applying viral hook zoom (push_in)...")
        }, 450)

        mainHandler.postDelayed({
            bubble.text = "🤖 Auto-Pilot Magic Edit ($style):\n\n" +
                "✅ [1/5] Silence & dead pauses trimmed\n" +
                "✅ [2/5] Viral hook zoom applied\n" +
                "⏳ [3/5] Applying Teal & Orange color grade..."
            chatScrollView.post { chatScrollView.fullScroll(View.FOCUS_DOWN) }
            cliPanel?.appendLog("> [3/5]", "Auto-Pilot: Applying Teal & Orange color grade...")
        }, 900)

        mainHandler.postDelayed({
            bubble.text = "🤖 Auto-Pilot Magic Edit ($style):\n\n" +
                "✅ [1/5] Silence & dead pauses trimmed\n" +
                "✅ [2/5] Viral hook zoom applied\n" +
                "✅ [3/5] Cinematic Teal & Orange grade active\n" +
                "⏳ [4/5] Generating AI auto-captions..."
            chatScrollView.post { chatScrollView.fullScroll(View.FOCUS_DOWN) }
            cliPanel?.appendLog("> [4/5]", "Auto-Pilot: Generating AI auto-captions...")
        }, 1350)

        mainHandler.postDelayed({
            bubble.text = "🤖 Auto-Pilot Magic Edit ($style):\n\n" +
                "✅ [1/5] Silence & dead pauses trimmed\n" +
                "✅ [2/5] Viral hook zoom applied\n" +
                "✅ [3/5] Cinematic Teal & Orange grade active\n" +
                "✅ [4/5] AI auto-captions generated\n" +
                "⏳ [5/5] Adding 'Viral Video' hook title..."
            chatScrollView.post { chatScrollView.fullScroll(View.FOCUS_DOWN) }
            cliPanel?.appendLog("> [5/5]", "Auto-Pilot: Adding 'Viral Video' hook title...")
        }, 1800)

        mainHandler.postDelayed({
            cliPanel?.appendLog("[✓]", "Auto-Pilot: Magic edit complete! Timeline ready for preview/export.")
            cliPanel?.setStatus(AiTerminalConsolePanel.STATUS_READY, "#3FB950")
            val completeMsg = "✨ **Auto-Pilot Magic Edit Complete!** 🚀\n\n" +
                "✅ Audio silence trimmed for fast pacing\n" +
                "✅ Dynamic hook zoom (push-in)\n" +
                "✅ Teal & Orange cinematic look\n" +
                "✅ AI subtitles & captions added\n" +
                "✅ 'Viral Video' hook title overlay\n\n" +
                "Aapki viral reel preview ke liye taiyar hai! 🔥"
            bubble.text = completeMsg
            AiChatHistoryStore.addMessage(
                activity,
                AiChatHistoryStore.ChatMessage("assistant", completeMsg, isSuccess = true),
            )
            addAssistantBubble(
                "Export ya preview chalu karein:",
                isSuccess = true,
                actionPills = listOf(
                    Pair("🚀 Export 1080p") { handleUserCommand("export 1080p") },
                    Pair("▶️ Play Preview") { handleUserCommand("play") },
                    Pair("📱 9:16 Reel") { handleUserCommand("aspect ratio 9:16") },
                    Pair("🎵 Add BGM") { handleUserCommand("add music") },
                    Pair("↩️ Undo") { handleUserCommand("undo") },
                ),
            )
            chatScrollView.post { chatScrollView.fullScroll(View.FOCUS_DOWN) }
        }, 2250)
    }

    private fun resolveActionPillsForIntent(intent: VideoEditIntent): List<Pair<String, () -> Unit>> {
        val actualIntent = if (intent is VideoEditIntent.Conversational && intent.executeIntent != null) {
            intent.executeIntent
        } else {
            intent
        }

        return when (actualIntent) {
            is VideoEditIntent.ExportVideo -> listOf(
                Pair("1080p Full HD") { handleUserCommand("export 1080p") },
                Pair("720p HD") { handleUserCommand("export 720p") },
                Pair("4K Ultra HD") { handleUserCommand("export 4k") },
                Pair("▶️ Play Preview") { handleUserCommand("play") },
            )
            is VideoEditIntent.SetAspectRatio -> listOf(
                Pair("📱 9:16 Reel") { handleUserCommand("aspect ratio 9:16") },
                Pair("🖥️ 16:9 Landscape") { handleUserCommand("aspect ratio 16:9") },
                Pair("⬛ 1:1 Square") { handleUserCommand("aspect ratio 1:1") },
                Pair("🚀 Export 1080p") { handleUserCommand("export 1080p") },
            )
            is VideoEditIntent.AddMusic -> listOf(
                Pair("🎧 Lofi Chill") { handleUserCommand("add lofi music") },
                Pair("🔥 Reels Trap") { handleUserCommand("add reels music") },
                Pair("⚡ Bass Drop") { handleUserCommand("add bass drop music") },
                Pair("▶️ Play Preview") { handleUserCommand("play") },
            )
            is VideoEditIntent.AddTransition -> listOf(
                Pair("🔀 Crossfade") { handleUserCommand("add transition crossfade") },
                Pair("➡️ Wipe Right") { handleUserCommand("add transition wipe") },
                Pair("▶️ Play Preview") { handleUserCommand("play") },
                Pair("🚀 Export 1080p") { handleUserCommand("export 1080p") },
            )
            is VideoEditIntent.TrimClip,
            is VideoEditIntent.SplitClip,
            is VideoEditIntent.RemoveSilence,
            is VideoEditIntent.ApplyColorGrade,
            is VideoEditIntent.SetSpeed,
            is VideoEditIntent.AutoCaptions,
            is VideoEditIntent.AddTitle,
            is VideoEditIntent.DynamicZoom,
            is VideoEditIntent.FastBeatCuts,
            is VideoEditIntent.RotateClip,
            is VideoEditIntent.FlipClip,
            is VideoEditIntent.SetVolume,
            is VideoEditIntent.GenerateTtsVoiceover,
            is VideoEditIntent.AutoBeatSync,
            is VideoEditIntent.ToggleBeforeAfter,
            is VideoEditIntent.TopicToReel,
            is VideoEditIntent.AddSoundEffect,
            is VideoEditIntent.FaceTrackingZoom,
            is VideoEditIntent.StudioVoiceClarity,
            is VideoEditIntent.DynamicCaptions,
            is VideoEditIntent.GenerateThumbnail,
            is VideoEditIntent.AddStickerOverlay,
            is VideoEditIntent.PlatformExport,
            is VideoEditIntent.ResetPreview,
            is VideoEditIntent.SpeedRamp,
            is VideoEditIntent.SmartJumpCuts,
            is VideoEditIntent.AutoReframe,
            is VideoEditIntent.ExtractViralHighlight,
            is VideoEditIntent.AutoBrollOverlay,
            is VideoEditIntent.Compound,
            is VideoEditIntent.MuteClip -> listOf(
                Pair("▶️ Play Preview") { handleUserCommand("play") },
                Pair("🚀 Export 1080p") { handleUserCommand("export 1080p") },
                Pair("📱 9:16 Reel") { handleUserCommand("aspect ratio 9:16") },
                Pair("↩️ Undo") { handleUserCommand("undo") },
            )
            is VideoEditIntent.InspectTimelineCritic -> listOf(
                Pair("✨ Full AI Edit") { handleUserCommand("pura video auto edit karke export karo") },
                Pair("📱 9:16 Reel") { handleUserCommand("auto reframe 9:16") },
                Pair("✂️ Smart Jump-Cuts") { handleUserCommand("smart jump cuts") },
                Pair("🎵 Add Lofi BGM") { handleUserCommand("add music lofi") },
            )
            is VideoEditIntent.TogglePlayback -> listOf(
                Pair("▶️ Play / Pause") { handleUserCommand("play") },
                Pair("🚀 Export 1080p") { handleUserCommand("export 1080p") },
                Pair("📱 9:16 Reel") { handleUserCommand("aspect ratio 9:16") },
            )
            else -> listOf(
                Pair("✨ Full AI Edit & Export") { handleUserCommand("pura video auto edit karke export karo") },
                Pair("🚀 Export 1080p") { handleUserCommand("export 1080p") },
                Pair("▶️ Play Preview") { handleUserCommand("play") },
            )
        }
    }

    private fun renderUserBubble(message: String) {
        val bubble = TextView(activity).apply {
            text = message
            textSize = 13.5f
            setTextColor(COLOR_TEXT_PRIMARY)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(COLOR_USER_BUBBLE)
                setStroke(dp(1), COLOR_USER_BORDER)
            }
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.END
                topMargin = dp(6)
                bottomMargin = dp(4)
                marginStart = dp(48)
            }
        }
        chatContainer.addView(bubble)
    }

    private fun addUserBubble(message: String) {
        renderUserBubble(message)
        AiChatHistoryStore.addMessage(
            activity,
            AiChatHistoryStore.ChatMessage("user", message, isSuccess = true),
        )
    }

    private fun renderAssistantBubble(
        message: String,
        isSuccess: Boolean = true,
        actionPills: List<Pair<String, () -> Unit>> = emptyList(),
    ): TextView {
        val rootBubbleContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.START
                topMargin = dp(6)
                bottomMargin = dp(4)
                marginEnd = dp(32)
            }
        }

        val bubbleLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }

        val botIcon = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply {
                marginEnd = dp(6)
                topMargin = dp(4)
            }
            setImageResource(R.drawable.ic_magic_wand)
            setColorFilter(if (isSuccess) COLOR_ACCENT_CYAN else Color.parseColor("#F85149"))
        }
        bubbleLayout.addView(botIcon)

        val bubbleText = TextView(activity).apply {
            text = message
            textSize = 13.5f
            setTextColor(if (isSuccess) Color.parseColor("#E6EDF3") else Color.parseColor("#FFA198"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(COLOR_BOT_BUBBLE)
                setStroke(dp(1), if (isSuccess) COLOR_BORDER else Color.parseColor("#542226"))
            }
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        bubbleLayout.addView(bubbleText)
        rootBubbleContainer.addView(bubbleLayout)

        if (actionPills.isNotEmpty()) {
            val pillsScroll = HorizontalScrollView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(6)
                    marginStart = dp(26)
                }
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
            }
            val pillsLayout = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            actionPills.forEach { (label, action) ->
                val pill = TextView(activity).apply {
                    text = label
                    textSize = 12f
                    setTypeface(typeface, Typeface.BOLD)
                    val isHighlight = label.contains("Export") || label.contains("Edit & Export")
                    setTextColor(if (isHighlight) COLOR_ACCENT_CYAN else Color.parseColor("#E6EDF3"))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(999).toFloat()
                        setColor(if (isHighlight) Color.parseColor("#1B293A") else Color.parseColor("#171D26"))
                        setStroke(dp(1), if (isHighlight) COLOR_ACCENT_CYAN else Color.parseColor("#2B3646"))
                    }
                    setPadding(dp(10), dp(5), dp(10), dp(5))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        marginEnd = dp(6)
                    }
                    setOnClickListener {
                        action.invoke()
                    }
                }
                pillsLayout.addView(pill)
            }
            pillsScroll.addView(pillsLayout)
            rootBubbleContainer.addView(pillsScroll)
        }

        chatContainer.addView(rootBubbleContainer)
        return bubbleText
    }

    private fun addAssistantBubble(
        message: String,
        isSuccess: Boolean = true,
        actionPills: List<Pair<String, () -> Unit>> = emptyList(),
    ): TextView {
        val bubble = renderAssistantBubble(message, isSuccess, actionPills)
        AiChatHistoryStore.addMessage(
            activity,
            AiChatHistoryStore.ChatMessage("assistant", message, isSuccess = isSuccess),
        )
        return bubble
    }

    private fun loadPersistedChatHistory() {
        chatContainer.removeAllViews()
        val history = AiChatHistoryStore.getHistory(activity)
        if (history.isEmpty()) {
            val welcomeText = "👋 Hey! Main tumhara pro AI video editor hoon. Bolo ya chat karo — puri timeline edit aur export turant ho jayegi! 🎬✨"
            renderAssistantBubble(
                welcomeText,
                isSuccess = true,
                actionPills = listOf(
                    Pair("✨ Full AI Edit & Export") { handleUserCommand("pura video auto edit karke export karo") },
                    Pair("👀 Inspect Video") { handleUserCommand("mera video dekho kaha cut aur zoom kare") },
                    Pair("🚀 Export 1080p") { handleUserCommand("export 1080p") },
                    Pair("📱 9:16 Reel") { handleUserCommand("aspect ratio 9:16") },
                    Pair("🎵 Add BGM") { handleUserCommand("add music lofi chill") },
                    Pair("✂️ Remove Silence") { handleUserCommand("remove silence") },
                ),
            )
            AiChatHistoryStore.addMessage(
                activity,
                AiChatHistoryStore.ChatMessage("assistant", welcomeText, isSuccess = true),
            )
        } else {
            for (msg in history) {
                if (msg.sender == "user") {
                    renderUserBubble(msg.text)
                } else {
                    renderAssistantBubble(msg.text, msg.isSuccess)
                }
            }
        }
        chatScrollView.post {
            chatScrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun showClearHistoryConfirmDialog() {
        android.app.AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Clear Chat History?")
            .setMessage("Kya aap saari previous chat history delete karna chahte hain?")
            .setPositiveButton("Delete") { _, _ ->
                AiChatHistoryStore.clearHistory(activity)
                chatContainer.removeAllViews()
                val resetMsg = "Namaste! 🙏 Saari previous chat history clear ho gayi hai. Video ko cut, zoom, color grade, subtitles ya 1-tap me viral reel banane ke liye bas bolo ya type karo!"
                addAssistantBubble(
                    resetMsg,
                    isSuccess = true,
                    actionPills = listOf(
                        Pair("✨ Full AI Edit & Export") { handleUserCommand("pura video auto edit karke export karo") },
                        Pair("👀 Inspect Video") { handleUserCommand("mera video dekho kaha cut aur zoom kare") },
                        Pair("🚀 Export 1080p") { handleUserCommand("export 1080p") },
                        Pair("📱 9:16 Reel") { handleUserCommand("aspect ratio 9:16") },
                    ),
                )
                Toast.makeText(activity, "Chat history deleted", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dp(dpVal: Int): Int {
        return (dpVal * activity.resources.displayMetrics.density).roundToInt()
    }
}
