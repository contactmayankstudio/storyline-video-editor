#include <stdint.h>

// FFmpeg headers
extern "C" {
#include <libavformat/avformat.h>
#include <libavutil/dict.h>
#include <libavutil/rational.h>
}

/**
 * @brief Gets the duration (in seconds) and resolution of a video file using FFmpeg.
 *
 * This function is designed to be called from other languages via FFI (Foreign Function Interface),
 * such as Dart's FFI to be used in a Flutter application.
 *
 * @param file_path The path to the video file.
 * @param duration Pointer to a 64-bit integer where the duration in seconds will be stored.
 * @param width Pointer to an integer where the video width in pixels will be stored.
 * @param height Pointer to an integer where the video height in pixels will be stored.
 * @return 0 on success, or a negative value indicating an error code on failure.
 *         -1: Invalid arguments (null pointers).
 *         -2: Could not allocate AVFormatContext.
 *         -3: Could not open the input file.
 *         -4: Could not find stream information in the file.
 *         -5: Could not find a video stream in the file.
 */
extern "C" int get_video_info(const char* file_path, int64_t* duration, int* width, int* height) {
    if (!file_path || !duration || !width || !height) {
        return -1; // Invalid arguments
    }

    // It's good practice to initialize the network components,
    // though not strictly necessary for local files.
    avformat_network_init();

    AVFormatContext* pFormatCtx = avformat_alloc_context();
    if (!pFormatCtx) {
        return -2; // Could not allocate context
    }

    // Open the input file and read the header.
    if (avformat_open_input(&pFormatCtx, file_path, NULL, NULL) != 0) {
        avformat_free_context(pFormatCtx);
        return -3; // Could not open file
    }

    // Retrieve stream information.
    if (avformat_find_stream_info(pFormatCtx, NULL) < 0) {
        avformat_close_input(&pFormatCtx);
        return -4; // Could not find stream info
    }

    int video_stream_index = -1;
    AVCodecParameters* pCodecParams = NULL;

    // Find the first video stream.
    for (unsigned int i = 0; i < pFormatCtx->nb_streams; i++) {
        if (pFormatCtx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
            video_stream_index = i;
            pCodecParams = pFormatCtx->streams[i]->codecpar;
            break;
        }
    }

    if (video_stream_index == -1) {
        avformat_close_input(&pFormatCtx);
        return -5; // Could not find video stream
    }

    // Retrieve the resolution from the codec parameters.
    *width = pCodecParams->width;
    *height = pCodecParams->height;

    // Calculate the duration.
    // Prefer the duration from the container format context if available.
    if (pFormatCtx->duration != AV_NOPTS_VALUE) {
        // The duration is given in AV_TIME_BASE units, so we convert it to seconds.
        *duration = pFormatCtx->duration / AV_TIME_BASE;
    } else {
        // Fallback to the video stream's duration if the container does not provide one.
        AVStream* video_stream = pFormatCtx->streams[video_stream_index];
        if (video_stream->duration != AV_NOPTS_VALUE) {
            // Convert the stream's duration from its time base to seconds.
            *duration = (int64_t)(video_stream->duration * av_q2d(video_stream->time_base));
        } else {
            // Duration is not available in the container or the stream.
            *duration = 0;
        }
    }

    // Clean up resources.
    avformat_close_input(&pFormatCtx);
    avformat_network_deinit();

    return 0; // Success
}
