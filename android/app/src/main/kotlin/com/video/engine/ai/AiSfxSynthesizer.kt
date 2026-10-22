package com.video.engine.ai

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Algorithmic Sound Effects (SFX) Synthesizer for Storyline Video Editor.
 * Synthesizes studio-grade sound effects (Whoosh, Pop, Ding, Glitch, Thud)
 * directly in memory and writes valid 16-bit PCM WAV files with zero asset downloads.
 */
object AiSfxSynthesizer {

    fun generateSfx(context: Context, sfxType: String): File {
        val dir = File(context.cacheDir, "ai_sfx").apply { mkdirs() }
        val cleanType = sfxType.trim().lowercase()
        val file = File(dir, "sfx_${cleanType}_${System.currentTimeMillis()}.wav")

        val sampleRate = 44100
        val pcmData = when (cleanType) {
            "pop" -> generatePop(sampleRate)
            "ding", "bell" -> generateDing(sampleRate)
            "glitch" -> generateGlitch(sampleRate)
            "thud", "bass", "impact" -> generateThud(sampleRate)
            else -> generateWhoosh(sampleRate)
        }

        writeWavFile(file, pcmData, sampleRate)
        return file
    }

    private fun generateWhoosh(sampleRate: Int): ShortArray {
        val durationSec = 0.42f
        val numSamples = (sampleRate * durationSec).toInt()
        val samples = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate
            val progress = t / durationSec
            val freq = 180.0 + 420.0 * sin(PI * progress)
            val amp = sin(PI * progress) * 0.85
            val s = sin(2.0 * PI * freq * t) * amp
            samples[i] = (s * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return samples
    }

    private fun generatePop(sampleRate: Int): ShortArray {
        val durationSec = 0.12f
        val numSamples = (sampleRate * durationSec).toInt()
        val samples = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate
            val freq = 380.0 + (1.0 - (t / durationSec)) * 520.0
            val env = exp(-t * 36.0)
            val s = sin(2.0 * PI * freq * t) * env
            samples[i] = (s * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return samples
    }

    private fun generateDing(sampleRate: Int): ShortArray {
        val durationSec = 0.65f
        val numSamples = (sampleRate * durationSec).toInt()
        val samples = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate
            val env = exp(-t * 5.8)
            val s = (sin(2.0 * PI * 880.0 * t) * 0.7 + sin(2.0 * PI * 1760.0 * t) * 0.3) * env
            samples[i] = (s * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return samples
    }

    private fun generateGlitch(sampleRate: Int): ShortArray {
        val durationSec = 0.25f
        val numSamples = (sampleRate * durationSec).toInt()
        val samples = ShortArray(numSamples)
        var randomPhase = 0.0
        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate
            if (i % 75 == 0) randomPhase = (Math.random() - 0.5) * 2.0
            val env = (1.0 - t / durationSec)
            val s = (sin(2.0 * PI * (620.0 + randomPhase * 320.0) * t) * 0.6 + (Math.random() - 0.5) * 0.4) * env
            samples[i] = (s * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return samples
    }

    private fun generateThud(sampleRate: Int): ShortArray {
        val durationSec = 0.35f
        val numSamples = (sampleRate * durationSec).toInt()
        val samples = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate
            val freq = 130.0 * exp(-t * 11.0) + 40.0
            val env = exp(-t * 9.5)
            val s = sin(2.0 * PI * freq * t) * env
            samples[i] = (s * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return samples
    }

    private fun writeWavFile(file: File, pcmData: ShortArray, sampleRate: Int) {
        val channels = 1
        val byteRate = sampleRate * channels * 2
        val dataSize = pcmData.size * 2
        val totalSize = 36 + dataSize

        FileOutputStream(file).use { out ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray())
                putInt(totalSize)
                put("WAVE".toByteArray())
                put("fmt ".toByteArray())
                putInt(16)
                putShort(1)
                putShort(channels.toShort())
                putInt(sampleRate)
                putInt(byteRate)
                putShort((channels * 2).toShort())
                putShort(16)
                put("data".toByteArray())
                putInt(dataSize)
            }
            out.write(header.array())

            val buffer = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in pcmData) {
                buffer.putShort(sample)
            }
            out.write(buffer.array())
        }
    }
}
