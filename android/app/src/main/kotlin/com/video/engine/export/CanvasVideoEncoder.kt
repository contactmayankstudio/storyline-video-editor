package com.video.engine.export

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Universal Hardware-Accelerated Video Encoder for Android.
 * Renders Canvas Bitmaps (with AR props, Status Maker visualizers, particle effects,
 * animated typography) directly into H.264 MP4 videos using MediaCodec + EGL14 + MediaMuxer.
 *
 * Runs 100% natively on any Android device without external native/FFmpeg dependencies.
 */
class CanvasVideoEncoder(
    val width: Int,
    val height: Int,
    val fps: Int = 30,
    val bitrate: Int = 6_000_000,
    private val outputFile: File
) {
    companion object {
        private const val TAG = "CanvasVideoEncoder"
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
    }

    private val tempVideoOnlyFile = File(outputFile.parentFile, "tmp_vid_${System.currentTimeMillis()}_${outputFile.name}")
    private var encoder: MediaCodec? = null
    private var inputSurface: android.view.Surface? = null
    private var muxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var isMuxerStarted = false

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var glProgram = 0
    private var aPositionLoc = 0
    private var aTexCoordLoc = 0
    private var uTextureLoc = 0
    private var textureId = 0

    private val bufferInfo = MediaCodec.BufferInfo()
    private val vertexBuffer: FloatBuffer

    init {
        // Vertex data: X, Y, U, V
        // Mapped so Bitmap top-left corresponds to top-left of video without inversion
        val quad = floatArrayOf(
            -1.0f, -1.0f, 0.0f, 1.0f, // bottom-left
             1.0f, -1.0f, 1.0f, 1.0f, // bottom-right
            -1.0f,  1.0f, 0.0f, 0.0f, // top-left
             1.0f,  1.0f, 1.0f, 0.0f  // top-right
        )
        vertexBuffer = ByteBuffer.allocateDirect(quad.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(quad)
                position(0)
            }

        setupEncoder()
        setupEgl()
        setupGl()
    }

    private fun setupEncoder() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
        encoder = codec

        muxer = MediaMuxer(tempVideoOnlyFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    private fun setupEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("eglGetDisplay failed")
        }

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("eglInitialize failed")
        }

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, configs.size, numConfigs, 0) || numConfigs[0] == 0) {
            // Fallback without recordable flag
            val fallbackAttribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
            )
            EGL14.eglChooseConfig(eglDisplay, fallbackAttribs, 0, configs, 0, configs.size, numConfigs, 0)
        }

        val config = configs[0] ?: throw RuntimeException("No EGLConfig found")
        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )

        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw RuntimeException("eglCreateContext failed")
        }

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, inputSurface, surfaceAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            throw RuntimeException("eglCreateWindowSurface failed")
        }

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw RuntimeException("eglMakeCurrent failed")
        }
    }

    private fun setupGl() {
        val vShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vShader)
        GLES20.glAttachShader(program, fShader)
        GLES20.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw RuntimeException("GL Program link failed: $log")
        }

        glProgram = program
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTextureLoc = GLES20.glGetUniformLocation(program, "uTexture")

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("GL Shader compile failed: $log")
        }
        return shader
    }

    /**
     * Enqueue a rendered Canvas Bitmap frame to the H.264 video encoder.
     * @param bitmap Bitmap containing the rendered frame
     * @param ptsNano Presentation timestamp in nanoseconds
     */
    fun encodeFrame(bitmap: Bitmap, ptsNano: Long) {
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return

        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(glProgram)

        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 4 * 4, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aPositionLoc)

        vertexBuffer.position(2)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 4 * 4, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glUniform1i(uTextureLoc, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)

        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, ptsNano)
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)

        drainEncoder(endOfStream = false)
    }

    private fun drainEncoder(endOfStream: Boolean) {
        val codec = encoder ?: return
        val m = muxer ?: return

        if (endOfStream) {
            try {
                codec.signalEndOfInputStream()
            } catch (e: Exception) {
                Log.w(TAG, "signalEndOfInputStream failed", e)
            }
        }

        while (true) {
            val status = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
            } else if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (isMuxerStarted) {
                    throw RuntimeException("Format changed after muxer started")
                }
                val newFormat = codec.outputFormat
                videoTrackIndex = m.addTrack(newFormat)
                m.start()
                isMuxerStarted = true
            } else if (status >= 0) {
                val encodedData = codec.getOutputBuffer(status)
                    ?: throw RuntimeException("encoderOutputBuffer $status was null")

                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    bufferInfo.size = 0
                }

                if (bufferInfo.size != 0) {
                    if (!isMuxerStarted) {
                        throw RuntimeException("Muxer hasn't started")
                    }
                    encodedData.position(bufferInfo.offset)
                    encodedData.limit(bufferInfo.offset + bufferInfo.size)
                    m.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                }

                codec.releaseOutputBuffer(status, false)

                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break
                }
            }
        }
    }

    /**
     * Finish encoding and composite audio track into final MP4.
     */
    fun finish(audioFile: File? = null, maxDurationUs: Long = -1L): Boolean {
        try {
            drainEncoder(endOfStream = true)
        } catch (e: Exception) {
            Log.e(TAG, "drainEncoder(endOfStream) error", e)
        }

        releaseGlAndEncoder()

        val videoOk = tempVideoOnlyFile.exists() && tempVideoOnlyFile.length() > 0
        if (!videoOk) {
            Log.e(TAG, "Video-only encoding produced empty or missing file")
            return false
        }

        if (audioFile != null && audioFile.exists() && audioFile.length() > 0) {
            val muxOk = muxVideoAndAudio(tempVideoOnlyFile, audioFile, outputFile, maxDurationUs)
            try { tempVideoOnlyFile.delete() } catch (_: Exception) {}
            return muxOk && outputFile.exists() && outputFile.length() > 0
        } else {
            // No audio, simply rename video-only to destination
            if (outputFile.exists()) outputFile.delete()
            return tempVideoOnlyFile.renameTo(outputFile)
        }
    }

    private fun muxVideoAndAudio(
        videoFile: File,
        audioFile: File,
        destFile: File,
        maxDurationUs: Long
    ): Boolean {
        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var finalMuxer: MediaMuxer? = null

        return try {
            videoExtractor = MediaExtractor().apply { setDataSource(videoFile.absolutePath) }
            audioExtractor = MediaExtractor().apply { setDataSource(audioFile.absolutePath) }

            if (destFile.exists()) destFile.delete()
            finalMuxer = MediaMuxer(destFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // Select video track
            var videoTrackIdx = -1
            var videoExtractorTrack = -1
            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoExtractor.selectTrack(i)
                    videoExtractorTrack = i
                    videoTrackIdx = finalMuxer.addTrack(format)
                    break
                }
            }

            // Select audio track
            var audioTrackIdx = -1
            var audioExtractorTrack = -1
            for (i in 0 until audioExtractor.trackCount) {
                val format = audioExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioExtractor.selectTrack(i)
                    audioExtractorTrack = i
                    audioTrackIdx = finalMuxer.addTrack(format)
                    break
                }
            }

            if (videoTrackIdx < 0) {
                Log.e(TAG, "Could not find video track in $videoFile")
                return false
            }

            finalMuxer.start()

            val bufferSize = 1024 * 1024
            val buffer = ByteBuffer.allocate(bufferSize)
            val info = MediaCodec.BufferInfo()

            // 1. Copy video track
            var lastVideoPts = 0L
            while (true) {
                info.offset = 0
                info.size = videoExtractor.readSampleData(buffer, 0)
                if (info.size < 0) break

                info.presentationTimeUs = videoExtractor.sampleTime
                info.flags = videoExtractor.sampleFlags
                lastVideoPts = Math.max(lastVideoPts, info.presentationTimeUs)

                finalMuxer.writeSampleData(videoTrackIdx, buffer, info)
                videoExtractor.advance()
            }

            // 2. Copy audio track (trim or loop to match video duration)
            if (audioTrackIdx >= 0) {
                val limitUs = if (maxDurationUs > 0) maxDurationUs else lastVideoPts
                var currentAudioTimeOffsetUs = 0L
                var wroteAudio = false

                var loopCount = 0
                while (loopCount < 20) {
                    var readInThisPass = 0
                    while (true) {
                        info.offset = 0
                        info.size = audioExtractor.readSampleData(buffer, 0)
                        if (info.size < 0) break

                        val rawSampleTime = audioExtractor.sampleTime
                        info.presentationTimeUs = currentAudioTimeOffsetUs + rawSampleTime
                        info.flags = audioExtractor.sampleFlags

                        if (limitUs > 0 && info.presentationTimeUs > limitUs) {
                            break
                        }

                        finalMuxer.writeSampleData(audioTrackIdx, buffer, info)
                        wroteAudio = true
                        readInThisPass++
                        audioExtractor.advance()
                    }

                    if (limitUs <= 0 || (wroteAudio && (currentAudioTimeOffsetUs + audioExtractor.sampleTime) >= limitUs)) {
                        break
                    }

                    // Loop audio if shorter than video
                    currentAudioTimeOffsetUs += 15_000_000L
                    audioExtractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    loopCount++
                }
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "muxVideoAndAudio failed", e)
            false
        } finally {
            try { videoExtractor?.release() } catch (_: Exception) {}
            try { audioExtractor?.release() } catch (_: Exception) {}
            try {
                finalMuxer?.stop()
                finalMuxer?.release()
            } catch (_: Exception) {}
        }
    }

    private fun releaseGlAndEncoder() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                eglSurface = EGL14.EGL_NO_SURFACE
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                eglContext = EGL14.EGL_NO_CONTEXT
            }
            EGL14.eglTerminate(eglDisplay)
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }

        try {
            inputSurface?.release()
            inputSurface = null
        } catch (_: Exception) {}

        try {
            encoder?.stop()
            encoder?.release()
            encoder = null
        } catch (_: Exception) {}

        try {
            if (isMuxerStarted) {
                muxer?.stop()
            }
            muxer?.release()
            muxer = null
        } catch (_: Exception) {}
    }
}
