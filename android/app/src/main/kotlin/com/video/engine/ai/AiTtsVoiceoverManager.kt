package com.video.engine.ai

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale

/**
 * Native Text-To-Speech (TTS) Voiceover Generator for Storyline Video Editor.
 * Converts creator script into high-quality broadcast WAV voiceover files
 * and mounts them directly onto the timeline audio track.
 */
object AiTtsVoiceoverManager {
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private val mainHandler = Handler(Looper.getMainLooper())

    fun init(context: Context, onReady: (() -> Unit)? = null) {
        if (isInitialized && tts != null) {
            onReady?.invoke()
            return
        }
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isInitialized = true
                onReady?.let { mainHandler.post(it) }
            }
        }
    }

    fun synthesizeVoiceover(
        context: Context,
        text: String,
        lang: String = "hi",
        pitch: Float = 1.0f,
        rate: Float = 1.0f,
        onComplete: (File) -> Unit,
        onError: (String) -> Unit,
    ) {
        init(context) {
            val engine = tts ?: run {
                onError("TextToSpeech engine not available")
                return@init
            }
            val locale = if (lang.equals("hi", ignoreCase = true) || lang.equals("hinglish", ignoreCase = true)) {
                Locale("hi", "IN")
            } else {
                Locale.US
            }
            try {
                engine.language = locale
            } catch (_: Throwable) {
                engine.language = Locale.US
            }
            engine.setPitch(pitch)
            engine.setSpeechRate(rate)

            val dir = File(context.cacheDir, "ai_voiceovers").apply { mkdirs() }
            val outputFile = File(dir, "tts_${System.currentTimeMillis()}.wav")
            val utteranceId = "tts_${System.currentTimeMillis()}"

            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {
                    if (id == utteranceId && outputFile.exists() && outputFile.length() > 0) {
                        mainHandler.post { onComplete(outputFile) }
                    } else {
                        mainHandler.post { onError("Voice synthesis file missing") }
                    }
                }
                override fun onError(id: String?) {
                    mainHandler.post { onError("Error synthesizing speech audio") }
                }
            })

            val params = Bundle()
            val result = engine.synthesizeToFile(text, params, outputFile, utteranceId)
            if (result != TextToSpeech.SUCCESS) {
                onError("Failed to dispatch voice synthesis task")
            }
        }
    }

    fun release() {
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
            isInitialized = false
        } catch (_: Throwable) {}
    }
}
