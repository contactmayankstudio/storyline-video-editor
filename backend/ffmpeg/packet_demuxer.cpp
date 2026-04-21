#include "backend/ffmpeg/packet_demuxer.h"

#include <cstring>
#include <iostream>

extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/avutil.h>
}
#include <media/NdkMediaCodec.h>

#ifndef AMEDIACODEC_BUFFER_FLAG_KEY_FRAME
#ifdef AMEDIACODEC_BUFFER_FLAG_SYNC_FRAME
#define AMEDIACODEC_BUFFER_FLAG_KEY_FRAME AMEDIACODEC_BUFFER_FLAG_SYNC_FRAME
#else
#define AMEDIACODEC_BUFFER_FLAG_KEY_FRAME 1
#endif
#endif

namespace video_engine::backend {

namespace {
constexpr uint8_t kStartCode[4] = {0x00, 0x00, 0x00, 0x01};

std::string mimeFromCodecId(AVCodecID id) {
    switch (id) {
        case AV_CODEC_ID_H264:
            return "video/avc";
        case AV_CODEC_ID_HEVC:
            return "video/hevc";
        default:
            return "video/avc";
    }
}

} // namespace

PacketDemuxer::PacketDemuxer()
    : m_formatContext(nullptr),
      m_videoStreamIndex(-1),
      m_width(0),
      m_height(0),
      m_naluLengthSize(0),
      m_timeBase{0, 1},
      m_fps(0.0),
      m_isOpen(false) {}

PacketDemuxer::~PacketDemuxer() {
    close();
}

bool PacketDemuxer::open(const std::string& path) {
    close();
    if (avformat_open_input(&m_formatContext, path.c_str(), nullptr, nullptr) != 0) {
        std::cerr << "[PacketDemuxer] Failed to open input: " << path << "\n";
        return false;
    }
    if (avformat_find_stream_info(m_formatContext, nullptr) < 0) {
        std::cerr << "[PacketDemuxer] Failed to find stream info\n";
        close();
        return false;
    }
    for (unsigned i = 0; i < m_formatContext->nb_streams; ++i) {
        auto* stream = m_formatContext->streams[i];
        if (stream->codecpar && stream->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
            m_videoStreamIndex = static_cast<int>(i);
            m_width = stream->codecpar->width;
            m_height = stream->codecpar->height;
            m_mimeType = mimeFromCodecId(stream->codecpar->codec_id);
            m_timeBase = stream->time_base;
            if (stream->avg_frame_rate.num > 0 && stream->avg_frame_rate.den > 0) {
                m_fps = av_q2d(stream->avg_frame_rate);
            } else if (stream->r_frame_rate.num > 0 && stream->r_frame_rate.den > 0) {
                m_fps = av_q2d(stream->r_frame_rate);
            } else {
                m_fps = 0.0;
            }
            break;
        }
    }
    if (m_videoStreamIndex < 0) {
        std::cerr << "[PacketDemuxer] No video stream found\n";
        close();
        return false;
    }
    m_isOpen = buildCodecConfig();
    if (m_isOpen && m_csd0.empty()) {
        primeCodecConfigFromFirstKeyframe();
    }
    return m_isOpen;
}

bool PacketDemuxer::buildCodecConfig() {
    if (!m_formatContext || m_videoStreamIndex < 0) return false;
    auto* stream = m_formatContext->streams[m_videoStreamIndex];
    if (!stream || !stream->codecpar) return false;
    const uint8_t* extra = stream->codecpar->extradata;
    const int extraSize = stream->codecpar->extradata_size;
    m_codecConfig.clear();
    m_csd0.clear();
    m_csd1.clear();
    m_naluLengthSize = 0;
    m_fps = 0.0;
    if (!extra || extraSize <= 0) {
        return true;
    }

    // Handle AVC/HVCC extradata (length-prefixed NALU)
    if (stream->codecpar->codec_id == AV_CODEC_ID_H264 && extraSize >= 7) {
        m_naluLengthSize = (extra[4] & 0x03) + 1;
        const uint8_t numSps = extra[5] & 0x1F;
        size_t offset = 6;
        for (uint8_t i = 0; i < numSps && offset + 2 <= static_cast<size_t>(extraSize); ++i) {
            uint16_t spsLen = (extra[offset] << 8) | extra[offset + 1];
            offset += 2;
            if (offset + spsLen > static_cast<size_t>(extraSize)) break;
            m_csd0.insert(m_csd0.end(), std::begin(kStartCode), std::end(kStartCode));
            m_csd0.insert(m_csd0.end(), extra + offset, extra + offset + spsLen);
            offset += spsLen;
        }
        if (offset + 1 <= static_cast<size_t>(extraSize)) {
            uint8_t numPps = extra[offset++];
            for (uint8_t i = 0; i < numPps && offset + 2 <= static_cast<size_t>(extraSize); ++i) {
                uint16_t ppsLen = (extra[offset] << 8) | extra[offset + 1];
                offset += 2;
                if (offset + ppsLen > static_cast<size_t>(extraSize)) break;
                m_csd1.insert(m_csd1.end(), std::begin(kStartCode), std::end(kStartCode));
                m_csd1.insert(m_csd1.end(), extra + offset, extra + offset + ppsLen);
                offset += ppsLen;
            }
        }
    } else if (stream->codecpar->codec_id == AV_CODEC_ID_HEVC && extraSize >= 23) {
        m_naluLengthSize = (extra[21] & 0x03) + 1;
        const uint8_t numArrays = extra[22];
        size_t offset = 23;
        for (uint8_t i = 0; i < numArrays && offset + 3 <= static_cast<size_t>(extraSize); ++i) {
            uint8_t nalType = extra[offset++] & 0x3F;
            uint16_t numNalus = (extra[offset] << 8) | extra[offset + 1];
            offset += 2;
            for (uint16_t j = 0; j < numNalus && offset + 2 <= static_cast<size_t>(extraSize); ++j) {
                uint16_t nalLen = (extra[offset] << 8) | extra[offset + 1];
                offset += 2;
                if (offset + nalLen > static_cast<size_t>(extraSize)) break;
                if (nalType == 32 || nalType == 33 || nalType == 34) {
                    m_csd0.insert(m_csd0.end(), std::begin(kStartCode), std::end(kStartCode));
                    m_csd0.insert(m_csd0.end(), extra + offset, extra + offset + nalLen);
                }
                offset += nalLen;
            }
        }
    }

    m_codecConfig.assign(extra, extra + extraSize);
    return true;
}

bool PacketDemuxer::readNextPacket(EncodedPacket& out) {
    if (!m_isOpen || !m_formatContext) return false;
    AVPacket pkt;
    av_init_packet(&pkt);
    bool got = false;
    while (av_read_frame(m_formatContext, &pkt) >= 0) {
        if (pkt.stream_index == m_videoStreamIndex) {
            out.ptsUs = pkt.pts == AV_NOPTS_VALUE
                ? 0
                : av_rescale_q(pkt.pts, m_timeBase, {1, 1000000});
            out.flags = (pkt.flags & AV_PKT_FLAG_KEY) ? AMEDIACODEC_BUFFER_FLAG_KEY_FRAME : 0;

            if (m_naluLengthSize > 0) {
                convertPacketToAnnexB(pkt.data, pkt.size, out);
            } else {
                out.data.assign(pkt.data, pkt.data + pkt.size);
            }
            got = true;
            av_packet_unref(&pkt);
            break;
        }
        av_packet_unref(&pkt);
    }
    return got;
}

void PacketDemuxer::convertPacketToAnnexB(const uint8_t* data, size_t size, EncodedPacket& out) {
    out.data.clear();
    size_t offset = 0;
    while (offset + m_naluLengthSize <= size) {
        uint32_t naluSize = 0;
        for (int i = 0; i < m_naluLengthSize; ++i) {
            naluSize = (naluSize << 8) | data[offset + i];
        }
        offset += m_naluLengthSize;
        if (offset + naluSize > size) break;
        out.data.insert(out.data.end(), std::begin(kStartCode), std::end(kStartCode));
        out.data.insert(out.data.end(), data + offset, data + offset + naluSize);
        offset += naluSize;
    }
}

void PacketDemuxer::extractCsdFromAnnexB(const uint8_t* data, size_t size) {
    if (size < 4) return;
    size_t i = 0;
    auto isStartCode = [&](size_t pos) {
        return pos + 3 < size &&
            data[pos] == 0x00 &&
            data[pos + 1] == 0x00 &&
            data[pos + 2] == 0x00 &&
            data[pos + 3] == 0x01;
    };

    while (i + 4 < size) {
        if (!isStartCode(i)) {
            ++i;
            continue;
        }
        const size_t naluStart = i + 4;
        size_t next = naluStart;
        while (next + 4 < size && !isStartCode(next)) {
            ++next;
        }
        const size_t naluSize = next - naluStart;
        if (naluSize == 0) {
            i = next;
            continue;
        }
        const uint8_t nalHeader = data[naluStart];
        const uint8_t nalTypeH264 = nalHeader & 0x1F;
        const uint8_t nalTypeHevc = (nalHeader >> 1) & 0x3F;

        if (m_mimeType == "video/avc") {
            if (nalTypeH264 == 7 && m_csd0.empty()) {
                m_csd0.insert(m_csd0.end(), std::begin(kStartCode), std::end(kStartCode));
                m_csd0.insert(m_csd0.end(), data + naluStart, data + naluStart + naluSize);
            } else if (nalTypeH264 == 8 && m_csd1.empty()) {
                m_csd1.insert(m_csd1.end(), std::begin(kStartCode), std::end(kStartCode));
                m_csd1.insert(m_csd1.end(), data + naluStart, data + naluStart + naluSize);
            }
        } else if (m_mimeType == "video/hevc") {
            if ((nalTypeHevc == 32 || nalTypeHevc == 33 || nalTypeHevc == 34) && m_csd0.empty()) {
                m_csd0.insert(m_csd0.end(), std::begin(kStartCode), std::end(kStartCode));
                m_csd0.insert(m_csd0.end(), data + naluStart, data + naluStart + naluSize);
            }
        }
        if (!m_csd0.empty() && (m_mimeType != "video/avc" || !m_csd1.empty())) {
            break;
        }
        i = next;
    }
}

bool PacketDemuxer::seekToMsKeyframe(int64_t timeMs) {
    if (!m_formatContext || m_videoStreamIndex < 0) return false;
    const int64_t ts = av_rescale_q(timeMs, {1, 1000}, m_timeBase);
    if (av_seek_frame(m_formatContext, m_videoStreamIndex, ts, AVSEEK_FLAG_BACKWARD) < 0) {
        return false;
    }
    return true;
}

bool PacketDemuxer::seekToMsApprox(int64_t timeMs) {
    if (!m_formatContext || m_videoStreamIndex < 0) return false;
    const int64_t ts = av_rescale_q(timeMs, {1, 1000}, m_timeBase);
    if (av_seek_frame(m_formatContext, m_videoStreamIndex, ts, AVSEEK_FLAG_BACKWARD | AVSEEK_FLAG_ANY) < 0) {
        return false;
    }
    return true;
}

bool PacketDemuxer::rewind() {
    if (!m_formatContext || m_videoStreamIndex < 0) return false;
    if (av_seek_frame(m_formatContext, m_videoStreamIndex, 0, AVSEEK_FLAG_BACKWARD) < 0) {
        return false;
    }
    return true;
}

bool PacketDemuxer::primeCodecConfigFromFirstKeyframe(int maxPackets) {
    if (!m_formatContext || m_videoStreamIndex < 0) return false;
    if (!m_csd0.empty()) return true;
    AVPacket pkt;
    av_init_packet(&pkt);
    int count = 0;
    while (count++ < maxPackets && av_read_frame(m_formatContext, &pkt) >= 0) {
        if (pkt.stream_index == m_videoStreamIndex && (pkt.flags & AV_PKT_FLAG_KEY)) {
            EncodedPacket temp;
            if (m_naluLengthSize > 0) {
                convertPacketToAnnexB(pkt.data, pkt.size, temp);
                extractCsdFromAnnexB(temp.data.data(), temp.data.size());
            } else {
                extractCsdFromAnnexB(pkt.data, pkt.size);
            }
            av_packet_unref(&pkt);
            break;
        }
        av_packet_unref(&pkt);
    }
    rewind();
    return !m_csd0.empty();
}

void PacketDemuxer::close() {
    if (m_formatContext) {
        avformat_close_input(&m_formatContext);
        m_formatContext = nullptr;
    }
    m_videoStreamIndex = -1;
    m_width = 0;
    m_height = 0;
    m_mimeType.clear();
    m_codecConfig.clear();
    m_csd0.clear();
    m_csd1.clear();
    m_naluLengthSize = 0;
    m_fps = 0.0;
    m_isOpen = false;
}

} // namespace video_engine::backend
