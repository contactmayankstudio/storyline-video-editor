#pragma once

#include <cstdint>
#include <string>
#include <vector>

extern "C" {
#include <libavutil/rational.h>
}

struct AVFormatContext;

namespace video_engine::backend {

struct EncodedPacket {
    std::vector<uint8_t> data;
    int64_t ptsUs = 0;
    int flags = 0;
};

class PacketDemuxer {
public:
    PacketDemuxer();
    ~PacketDemuxer();

    bool open(const std::string& path);
    bool readNextPacket(EncodedPacket& out);
    bool seekToMsKeyframe(int64_t timeMs);
    bool seekToMsApprox(int64_t timeMs);
    bool primeCodecConfigFromFirstKeyframe(int maxPackets = 60);
    bool rewind();
    void close();

    bool isOpen() const { return m_isOpen; }
    int width() const { return m_width; }
    int height() const { return m_height; }
    const std::string& mimeType() const { return m_mimeType; }
    const std::vector<uint8_t>& codecConfig() const { return m_codecConfig; }
    const std::vector<uint8_t>& csd0() const { return m_csd0; }
    const std::vector<uint8_t>& csd1() const { return m_csd1; }
    double fps() const { return m_fps; }

private:
    bool buildCodecConfig();
    void convertPacketToAnnexB(const uint8_t* data, size_t size, EncodedPacket& out);
    void extractCsdFromAnnexB(const uint8_t* data, size_t size);

    AVFormatContext* m_formatContext;
    int m_videoStreamIndex;
    int m_width;
    int m_height;
    std::string m_mimeType;
    std::vector<uint8_t> m_codecConfig;
    std::vector<uint8_t> m_csd0;
    std::vector<uint8_t> m_csd1;
    int m_naluLengthSize;
    AVRational m_timeBase;
    double m_fps;
    bool m_isOpen;
};

} // namespace video_engine::backend
