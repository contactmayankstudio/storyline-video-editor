package com.video.engine

import android.media.MediaCodecList
import android.util.Log

object ExportCodecSupport {
    private const val TAG = "[ExportCodec]"
    private const val AVC_MIME = "video/avc"
    private const val HEVC_MIME = "video/hevc"

    data class Capabilities(
        val avcEncoder: Boolean,
        val hevcEncoder: Boolean,
    )

    @Volatile
    private var cachedCapabilities: Capabilities? = null

    fun getCapabilities(): Capabilities {
        cachedCapabilities?.let { return it }
        val computed = runCatching {
            val codecInfos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            val avc = codecInfos.any { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(AVC_MIME, ignoreCase = true) }
            }
            val hevc = codecInfos.any { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(HEVC_MIME, ignoreCase = true) }
            }
            Capabilities(
                avcEncoder = avc,
                hevcEncoder = hevc,
            )
        }.getOrElse { error ->
            Log.w(TAG, "Failed to inspect codec capabilities", error)
            Capabilities(
                avcEncoder = true,
                hevcEncoder = false,
            )
        }
        cachedCapabilities = computed
        Log.i(TAG, "Encoder capabilities avc=${computed.avcEncoder} hevc=${computed.hevcEncoder}")
        return computed
    }
}
