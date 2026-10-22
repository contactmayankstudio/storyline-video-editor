package com.video.engine.ai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Supported speech recognition locales.
 */
enum class SpeechLocale(val tag: String, val displayName: String) {
    ENGLISH_IN("en-IN", "English"),
    HINDI_IN("hi-IN", "हिंदी"),
    HINGLISH("en-IN", "Hinglish"),
}

/**
 * Callback for speech recognition lifecycle and waveform events.
 */
interface SpeechRecognizerCallback {
    fun onSpeechReady()
    fun onSpeechStart()
    fun onRmsChanged(rmsdB: Float)
    fun onPartialResult(partialText: String)
    fun onSpeechResult(resultText: String)
    fun onSpeechError(errorMessage: String, errorCode: Int)
    fun onSpeechEnd()
    fun onAvailabilityChanged(available: Boolean)
}

/**
 * Manages native Android SpeechRecognizer with multi-lingual support (en-IN, hi-IN),
 * RMS wave decibel callbacks, and graceful fallback when unavailable.
 */
class SpeechRecognizerManager(
    private val context: Context,
    private val callback: SpeechRecognizerCallback,
) {
    companion object {
        private const val TAG = "SpeechRecognizerManager"
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening: Boolean = false
    var currentLocale: SpeechLocale = SpeechLocale.ENGLISH_IN
        private set

    /**
     * Check if speech recognition service is installed and available on device.
     */
    fun isRecognitionAvailable(): Boolean {
        return try {
            SpeechRecognizer.isRecognitionAvailable(context)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check speech recognition availability: ${e.message}")
            false
        }
    }

    /**
     * Check if audio recording permission is granted.
     */
    fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Change active speech recognition locale.
     */
    fun setLocale(locale: SpeechLocale) {
        currentLocale = locale
        if (isListening) {
            stopListening()
            startListening()
        }
    }

    /**
     * Start listening for voice commands.
     */
    fun startListening(): Boolean {
        if (!isRecognitionAvailable()) {
            Log.w(TAG, "SpeechRecognizer is not available on this device")
            callback.onAvailabilityChanged(false)
            callback.onSpeechError("Voice input is not available on this device. Please use text chat.", -1)
            return false
        }

        if (!hasAudioPermission()) {
            Log.w(TAG, "RECORD_AUDIO permission not granted")
            callback.onSpeechError("Microphone permission required for voice commands.", SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            return false
        }

        try {
            cleanupRecognizer()

            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(createRecognitionListener())
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLocale.tag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, currentLocale.tag)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("hi-IN", "en-IN", "en-US"))
                }
            }

            speechRecognizer?.startListening(intent)
            isListening = true
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error starting speech recognition: ${e.message}", e)
            isListening = false
            callback.onSpeechError("Could not start speech recognition: ${e.message}", -1)
            callback.onSpeechEnd()
            return false
        }
    }

    /**
     * Stop listening cleanly.
     */
    fun stopListening() {
        if (!isListening) return
        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping speech recognition: ${e.message}")
        } finally {
            isListening = false
            callback.onSpeechEnd()
        }
    }

    /**
     * Cancel any active recognition.
     */
    fun cancel() {
        try {
            speechRecognizer?.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "Error canceling speech recognition: ${e.message}")
        } finally {
            isListening = false
            callback.onSpeechEnd()
        }
    }

    /**
     * Release all resources.
     */
    fun destroy() {
        cleanupRecognizer()
    }

    private fun cleanupRecognizer() {
        isListening = false
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Error destroying speech recognizer: ${e.message}")
        } finally {
            speechRecognizer = null
        }
    }

    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "SpeechRecognizer onReadyForSpeech")
                callback.onSpeechReady()
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "SpeechRecognizer onBeginningOfSpeech")
                callback.onSpeechStart()
            }

            override fun onRmsChanged(rmsdB: Float) {
                callback.onRmsChanged(rmsdB)
            }

            override fun onBufferReceived(buffer: ByteArray?) {
                // Audio buffer stream
            }

            override fun onEndOfSpeech() {
                Log.d(TAG, "SpeechRecognizer onEndOfSpeech")
                isListening = false
                callback.onSpeechEnd()
            }

            override fun onError(error: Int) {
                isListening = false
                val message = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                    SpeechRecognizer.ERROR_CLIENT -> "Client operation error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required"
                    SpeechRecognizer.ERROR_NETWORK -> "Network connection error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized. Try speaking again."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy"
                    SpeechRecognizer.ERROR_SERVER -> "Server error occurred"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
                    else -> "Speech recognition error ($error)"
                }
                Log.w(TAG, "SpeechRecognizer onError: $error ($message)")
                callback.onSpeechError(message, error)
                callback.onSpeechEnd()
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val recognized = matches?.firstOrNull()?.trim()
                Log.d(TAG, "SpeechRecognizer onResults: $recognized")
                if (!recognized.isNullOrBlank()) {
                    callback.onSpeechResult(recognized)
                }
                callback.onSpeechEnd()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val partial = matches?.firstOrNull()?.trim()
                if (!partial.isNullOrBlank()) {
                    callback.onPartialResult(partial)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {
                // Speech event
            }
        }
    }
}
