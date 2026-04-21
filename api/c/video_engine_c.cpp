#include "video_engine_c.h"
#include "core/clip.h"
#include "core/timeline.h"
#include "engine/engine.h"
#include <cstring>
#include <cstdlib>
#include <thread>
#include <map>

namespace VideoEngine::C {

// Thread-local error message
thread_local char g_last_error[512] = {0};

void set_error(const char* fmt, ...) {
    // Simple error formatting (production would use vsnprintf)
    strncpy(g_last_error, fmt, sizeof(g_last_error) - 1);
}

// ============ Clip Management ============

ve_clip_t ve_clip_create(const char* media_path, ve_time_ms_t start_time_ms, ve_time_ms_t duration_ms) {
    if (!media_path) {
        set_error("Invalid media path");
        return nullptr;
    }

    try {
        auto clip = std::make_shared<VideoEngine::Clip>(media_path, start_time_ms, duration_ms);
        return new std::shared_ptr<VideoEngine::Clip>(clip);
    } catch (const std::exception& e) {
        set_error("Clip creation failed: %s", e.what());
        return nullptr;
    }
}

void ve_clip_destroy(ve_clip_t clip) {
    if (!clip) return;
    auto* cpp_clip = static_cast<std::shared_ptr<VideoEngine::Clip>*>(clip);
    delete cpp_clip;
}

ve_clip_id_t ve_clip_get_id(ve_clip_t clip) {
    if (!clip) return 0;
    auto* cpp_clip = static_cast<std::shared_ptr<VideoEngine::Clip>*>(clip);
    return (*cpp_clip)->getId();
}

size_t ve_clip_get_media_path(ve_clip_t clip, char* buffer, size_t buffer_size) {
    if (!clip || !buffer || buffer_size < 1) return 0;

    auto* cpp_clip = static_cast<std::shared_ptr<VideoEngine::Clip>*>(clip);
    const auto& path = (*cpp_clip)->getMediaPath();
    size_t len = std::min(path.length(), buffer_size - 1);
    std::memcpy(buffer, path.c_str(), len);
    buffer[len] = '\0';
    return len;
}

ve_error_t ve_clip_get_timing(ve_clip_t clip, ve_time_ms_t* out_start_ms, ve_time_ms_t* out_duration_ms) {
    if (!clip || !out_start_ms || !out_duration_ms) return VE_ERROR_NULL_POINTER;

    auto* cpp_clip = static_cast<std::shared_ptr<VideoEngine::Clip>*>(clip);
    *out_start_ms = (*cpp_clip)->getStartTime();
    *out_duration_ms = (*cpp_clip)->getDuration();
    return VE_OK;
}

ve_error_t ve_clip_set_timing(ve_clip_t clip, ve_time_ms_t start_ms, ve_time_ms_t duration_ms) {
    if (!clip) return VE_ERROR_INVALID_HANDLE;
    if (duration_ms <= 0) return VE_ERROR_INVALID_PARAM;

    auto* cpp_clip = static_cast<std::shared_ptr<VideoEngine::Clip>*>(clip);
    (*cpp_clip)->setTimelinePosition(start_ms, duration_ms);
    return VE_OK;
}

ve_error_t ve_clip_get_properties(ve_clip_t clip, ve_clip_properties_t* out_props) {
    if (!clip || !out_props) return VE_ERROR_NULL_POINTER;

    auto* cpp_clip = static_cast<std::shared_ptr<VideoEngine::Clip>*>(clip);
    const auto& props = (*cpp_clip)->getProperties();
    out_props->opacity = props.opacity;
    out_props->volume_gain = props.volumeGain;
    out_props->playback_speed = props.playbackSpeed;
    out_props->enabled = props.enabled;
    return VE_OK;
}

ve_error_t ve_clip_set_opacity(ve_clip_t clip, float opacity) {
    if (!clip) return VE_ERROR_INVALID_HANDLE;
    auto* cpp_clip = static_cast<std::shared_ptr<VideoEngine::Clip>*>(clip);
    (*cpp_clip)->setOpacity(opacity);
    return VE_OK;
}

ve_error_t ve_clip_set_volume_gain(ve_clip_t clip, float gain) {
    if (!clip) return VE_ERROR_INVALID_HANDLE;
    auto* cpp_clip = static_cast<std::shared_ptr<VideoEngine::Clip>*>(clip);
    (*cpp_clip)->setVolumeGain(gain);
    return VE_OK;
}

ve_error_t ve_clip_set_speed(ve_clip_t clip, float speed) {
    if (!clip) return VE_ERROR_INVALID_HANDLE;
    auto* cpp_clip = static_cast<std::shared_ptr<VideoEngine::Clip>*>(clip);
    (*cpp_clip)->setPlaybackSpeed(speed);
    return VE_OK;
}

ve_error_t ve_clip_set_enabled(ve_clip_t clip, bool enabled) {
    if (!clip) return VE_ERROR_INVALID_HANDLE;
    auto* cpp_clip = static_cast<std::shared_ptr<VideoEngine::Clip>*>(clip);
    (*cpp_clip)->setEnabled(enabled);
    return VE_OK;
}

// ============ Timeline Management ============

ve_timeline_t ve_timeline_create(void) {
    try {
        auto timeline = std::make_unique<VideoEngine::Timeline>();
        return new std::unique_ptr<VideoEngine::Timeline>(std::move(timeline));
    } catch (const std::exception& e) {
        set_error("Timeline creation failed: %s", e.what());
        return nullptr;
    }
}

ve_timeline_t ve_timeline_create_with_props(const ve_video_properties_t* vprops,
                                            const ve_audio_properties_t* aprops) {
    (void)vprops;  // Unused for now
    (void)aprops;
    try {
        auto timeline = std::make_unique<VideoEngine::Timeline>();
        return new std::unique_ptr<VideoEngine::Timeline>(std::move(timeline));
    } catch (const std::exception& e) {
        set_error("Timeline creation failed: %s", e.what());
        return nullptr;
    }
}

void ve_timeline_destroy(ve_timeline_t timeline) {
    if (!timeline) return;
    auto* cpp_timeline = static_cast<std::unique_ptr<VideoEngine::Timeline>*>(timeline);
    delete cpp_timeline;
}

ve_time_ms_t ve_timeline_get_duration(ve_timeline_t timeline) {
    if (!timeline) return 0;
    auto* cpp_timeline = static_cast<std::unique_ptr<VideoEngine::Timeline>*>(timeline);
    return (*cpp_timeline)->getDuration();
}

ve_error_t ve_timeline_get_video_properties(ve_timeline_t timeline, ve_video_properties_t* out_props) {
    if (!timeline || !out_props) return VE_ERROR_NULL_POINTER;
    auto* cpp_timeline = static_cast<std::unique_ptr<VideoEngine::Timeline>*>(timeline);
    const auto& vp = (*cpp_timeline)->getVideoProperties();
    out_props->resolution.width  = vp.resolution.width;
    out_props->resolution.height = vp.resolution.height;
    out_props->frame_rate        = vp.frameRate;
    out_props->aspect_ratio      = vp.aspectRatio;
    return VE_OK;
}

ve_error_t ve_timeline_get_audio_properties(ve_timeline_t timeline, ve_audio_properties_t* out_props) {
    if (!timeline || !out_props) return VE_ERROR_NULL_POINTER;
    auto* cpp_timeline = static_cast<std::unique_ptr<VideoEngine::Timeline>*>(timeline);
    const auto& ap = (*cpp_timeline)->getAudioProperties();
    out_props->sample_rate = ap.sampleRate;
    out_props->channels    = ap.channels;
    out_props->bit_depth   = ap.bitDepth;
    return VE_OK;
}

ve_error_t ve_timeline_add_clip(ve_timeline_t timeline, ve_clip_t clip) {
    if (!timeline || !clip) return VE_ERROR_NULL_POINTER;

    auto* cpp_timeline = static_cast<std::unique_ptr<VideoEngine::Timeline>*>(timeline);
    auto* cpp_clip = static_cast<std::shared_ptr<VideoEngine::Clip>*>(clip);

    (*cpp_timeline)->addClip(*cpp_clip);
    return VE_OK;
}

ve_error_t ve_timeline_remove_clip(ve_timeline_t timeline, ve_clip_id_t clip_id) {
    if (!timeline) return VE_ERROR_INVALID_HANDLE;

    auto* cpp_timeline = static_cast<std::unique_ptr<VideoEngine::Timeline>*>(timeline);
    (*cpp_timeline)->removeClip(std::to_string(clip_id));
    return VE_OK;
}

uint32_t ve_timeline_get_clip_count(ve_timeline_t timeline) {
    if (!timeline) return 0;
    auto* cpp_timeline = static_cast<std::unique_ptr<VideoEngine::Timeline>*>(timeline);
    return (*cpp_timeline)->clips().size();
}

uint32_t ve_timeline_get_clip_ids(ve_timeline_t timeline, ve_clip_id_t* out_ids, uint32_t max_ids) {
    if (!timeline || !out_ids || max_ids == 0) return 0;

    auto* cpp_timeline = static_cast<std::unique_ptr<VideoEngine::Timeline>*>(timeline);
    const auto& clips = (*cpp_timeline)->clips();

    uint32_t count = 0;
    for (const auto& clip : clips) {
        if (count >= max_ids) break;
        out_ids[count++] = clip->getId();
    }

    return count;
}

ve_time_ms_t ve_timeline_frame_to_ms(ve_timeline_t timeline, ve_frame_num_t frame) {
    if (!timeline) return 0;
    auto* cpp_timeline = static_cast<std::unique_ptr<VideoEngine::Timeline>*>(timeline);
    return (*cpp_timeline)->frameToMs(frame);
}

ve_frame_num_t ve_timeline_ms_to_frame(ve_timeline_t timeline, ve_time_ms_t ms) {
    if (!timeline) return 0;
    auto* cpp_timeline = static_cast<std::unique_ptr<VideoEngine::Timeline>*>(timeline);
    return (*cpp_timeline)->msToFrame(ms);
}

// ============ Render Graph ============

ve_render_graph_t ve_render_graph_create(ve_timeline_t timeline) {
    if (!timeline) return nullptr;

    try {
        auto* cpp_timeline = static_cast<std::unique_ptr<VideoEngine::Timeline>*>(timeline);
        auto graph = std::make_unique<VideoEngine::RenderGraph>();
        graph->buildFromTimeline(**cpp_timeline);
        return new std::unique_ptr<VideoEngine::RenderGraph>(std::move(graph));
    } catch (const std::exception& e) {
        set_error("RenderGraph creation failed: %s", e.what());
        return nullptr;
    }
}

void ve_render_graph_destroy(ve_render_graph_t graph) {
    if (!graph) return;
    auto* cpp_graph = static_cast<std::unique_ptr<VideoEngine::RenderGraph>*>(graph);
    delete cpp_graph;
}

uint32_t ve_render_graph_get_items_at_time(ve_render_graph_t graph,
                                           ve_time_ms_t time_ms,
                                           ve_render_item_t* out_items,
                                           uint32_t max_items) {
    if (!graph || !out_items || max_items == 0) return 0;

    auto* cpp_graph = static_cast<std::unique_ptr<VideoEngine::RenderGraph>*>(graph);
    const auto& items = (*cpp_graph)->getItemsAtTime(time_ms);

    uint32_t count = 0;
    for (const auto& item : items) {
        if (count >= max_items) break;

        out_items[count].item_type = VE_ITEM_TYPE_CLIP;
        out_items[count].clip_id = item.clip ? item.clip->getId() : 0;
        out_items[count].layer = item.layer;
        out_items[count].start_ms = item.startMs;
        out_items[count].end_ms = item.endMs;
        out_items[count].base_opacity = item.baseOpacity;
        out_items[count].effective_opacity = item.effectiveOpacity;
        out_items[count].effective_speed = item.effectiveSpeed;
        out_items[count].enabled = item.enabled;

        ++count;
    }

    return count;
}

bool ve_render_graph_has_visible_items(ve_render_graph_t graph, ve_time_ms_t time_ms) {
    if (!graph) return false;
    auto* cpp_graph = static_cast<std::unique_ptr<VideoEngine::RenderGraph>*>(graph);
    return (*cpp_graph)->hasVisibleItems(time_ms);
}

uint32_t ve_render_graph_get_transitions_at_time(ve_render_graph_t graph,
                                                 ve_time_ms_t time_ms,
                                                 ve_transition_info_t* out_transitions,
                                                 uint32_t max_transitions) {
    if (!graph || !out_transitions || max_transitions == 0) return 0;

    auto* cpp_graph = static_cast<std::unique_ptr<VideoEngine::RenderGraph>*>(graph);
    const auto& transitions = (*cpp_graph)->getTransitionsAtTime(time_ms);

    uint32_t count = 0;
    for (const auto& trans : transitions) {
        if (count >= max_transitions) break;

        out_transitions[count].type = static_cast<ve_transition_type_t>(trans.type);
        strncpy(out_transitions[count].id, trans.id.c_str(), 255);
        out_transitions[count].id[255] = '\0';
        out_transitions[count].layer = trans.layer;
        out_transitions[count].start_ms = trans.startMs;
        out_transitions[count].duration_ms = trans.durationMs;

        ++count;
    }

    return count;
}

uint32_t ve_render_graph_get_all_transitions(ve_render_graph_t graph,
                                             ve_transition_info_t* out_transitions,
                                             uint32_t max_transitions) {
    if (!graph || !out_transitions || max_transitions == 0) return 0;

    auto* cpp_graph = static_cast<std::unique_ptr<VideoEngine::RenderGraph>*>(graph);
    const auto& transitions = (*cpp_graph)->getTransitions();

    uint32_t count = 0;
    for (const auto& trans : transitions) {
        if (count >= max_transitions) break;

        out_transitions[count].type = static_cast<ve_transition_type_t>(trans.type);
        strncpy(out_transitions[count].id, trans.id.c_str(), 255);
        out_transitions[count].id[255] = '\0';
        out_transitions[count].layer = trans.layer;
        out_transitions[count].start_ms = trans.startMs;
        out_transitions[count].duration_ms = trans.durationMs;

        ++count;
    }

    return count;
}

// ============ Effect Management ============

ve_error_t ve_clip_add_fade_in_effect(ve_clip_t clip, ve_time_ms_t fade_duration_ms) {
    if (!clip) return VE_ERROR_INVALID_HANDLE;
    if (fade_duration_ms <= 0) return VE_ERROR_INVALID_PARAM;

    auto* cpp_clip = static_cast<std::shared_ptr<VideoEngine::Clip>*>(clip);
    (*cpp_clip)->addEffect("fade-in");
    return VE_OK;
}

ve_error_t ve_clip_add_fade_out_effect(ve_clip_t clip, ve_time_ms_t fade_duration_ms) {
    if (!clip) return VE_ERROR_INVALID_HANDLE;
    if (fade_duration_ms <= 0) return VE_ERROR_INVALID_PARAM;

    auto* cpp_clip = static_cast<std::shared_ptr<VideoEngine::Clip>*>(clip);
    (*cpp_clip)->addEffect("fade-out");
    return VE_OK;
}

ve_error_t ve_clip_add_slowmo_effect(ve_clip_t clip, float speed_multiplier) {
    if (!clip) return VE_ERROR_INVALID_HANDLE;
    if (speed_multiplier <= 0.0f) return VE_ERROR_INVALID_PARAM;

    auto* cpp_clip = static_cast<std::shared_ptr<VideoEngine::Clip>*>(clip);
    (*cpp_clip)->addEffect("slowmo");
    return VE_OK;
}

// ============ Utility ============

size_t ve_get_version(char* buffer, size_t buffer_size) {
    if (!buffer || buffer_size < 1) return 0;

    const char* version = "0.1.0";
    size_t len = std::min(strlen(version), buffer_size - 1);
    std::memcpy(buffer, version, len);
    buffer[len] = '\0';
    return len;
}

size_t ve_get_last_error(char* buffer, size_t buffer_size) {
    if (!buffer || buffer_size < 1) return 0;

    size_t len = std::min(strlen(g_last_error), buffer_size - 1);
    std::memcpy(buffer, g_last_error, len);
    buffer[len] = '\0';
    return len;
}

} // namespace VideoEngine::C

// ============ C Wrapper Functions (extern C) ============

extern "C" {

ve_clip_t ve_clip_create(const char* media_path, ve_time_ms_t start_time_ms, ve_time_ms_t duration_ms) {
    return VideoEngine::C::ve_clip_create(media_path, start_time_ms, duration_ms);
}

void ve_clip_destroy(ve_clip_t clip) {
    VideoEngine::C::ve_clip_destroy(clip);
}

ve_clip_id_t ve_clip_get_id(ve_clip_t clip) {
    return VideoEngine::C::ve_clip_get_id(clip);
}

size_t ve_clip_get_media_path(ve_clip_t clip, char* buffer, size_t buffer_size) {
    return VideoEngine::C::ve_clip_get_media_path(clip, buffer, buffer_size);
}

ve_error_t ve_clip_get_timing(ve_clip_t clip, ve_time_ms_t* out_start_ms, ve_time_ms_t* out_duration_ms) {
    return VideoEngine::C::ve_clip_get_timing(clip, out_start_ms, out_duration_ms);
}

ve_error_t ve_clip_set_timing(ve_clip_t clip, ve_time_ms_t start_ms, ve_time_ms_t duration_ms) {
    return VideoEngine::C::ve_clip_set_timing(clip, start_ms, duration_ms);
}

ve_error_t ve_clip_get_properties(ve_clip_t clip, ve_clip_properties_t* out_props) {
    return VideoEngine::C::ve_clip_get_properties(clip, out_props);
}

ve_error_t ve_clip_set_opacity(ve_clip_t clip, float opacity) {
    return VideoEngine::C::ve_clip_set_opacity(clip, opacity);
}

ve_error_t ve_clip_set_volume_gain(ve_clip_t clip, float gain) {
    return VideoEngine::C::ve_clip_set_volume_gain(clip, gain);
}

ve_error_t ve_clip_set_speed(ve_clip_t clip, float speed) {
    return VideoEngine::C::ve_clip_set_speed(clip, speed);
}

ve_error_t ve_clip_set_enabled(ve_clip_t clip, bool enabled) {
    return VideoEngine::C::ve_clip_set_enabled(clip, enabled);
}

ve_timeline_t ve_timeline_create(void) {
    return VideoEngine::C::ve_timeline_create();
}

ve_timeline_t ve_timeline_create_with_props(const ve_video_properties_t* vprops,
                                            const ve_audio_properties_t* aprops) {
    return VideoEngine::C::ve_timeline_create_with_props(vprops, aprops);
}

void ve_timeline_destroy(ve_timeline_t timeline) {
    VideoEngine::C::ve_timeline_destroy(timeline);
}

ve_time_ms_t ve_timeline_get_duration(ve_timeline_t timeline) {
    return VideoEngine::C::ve_timeline_get_duration(timeline);
}

ve_error_t ve_timeline_get_video_properties(ve_timeline_t timeline, ve_video_properties_t* out_props) {
    return VideoEngine::C::ve_timeline_get_video_properties(timeline, out_props);
}

ve_error_t ve_timeline_get_audio_properties(ve_timeline_t timeline, ve_audio_properties_t* out_props) {
    return VideoEngine::C::ve_timeline_get_audio_properties(timeline, out_props);
}

ve_error_t ve_timeline_add_clip(ve_timeline_t timeline, ve_clip_t clip) {
    return VideoEngine::C::ve_timeline_add_clip(timeline, clip);
}

ve_error_t ve_timeline_remove_clip(ve_timeline_t timeline, ve_clip_id_t clip_id) {
    return VideoEngine::C::ve_timeline_remove_clip(timeline, clip_id);
}

uint32_t ve_timeline_get_clip_count(ve_timeline_t timeline) {
    return VideoEngine::C::ve_timeline_get_clip_count(timeline);
}

uint32_t ve_timeline_get_clip_ids(ve_timeline_t timeline, ve_clip_id_t* out_ids, uint32_t max_ids) {
    return VideoEngine::C::ve_timeline_get_clip_ids(timeline, out_ids, max_ids);
}

ve_time_ms_t ve_timeline_frame_to_ms(ve_timeline_t timeline, ve_frame_num_t frame) {
    return VideoEngine::C::ve_timeline_frame_to_ms(timeline, frame);
}

ve_frame_num_t ve_timeline_ms_to_frame(ve_timeline_t timeline, ve_time_ms_t ms) {
    return VideoEngine::C::ve_timeline_ms_to_frame(timeline, ms);
}

ve_render_graph_t ve_render_graph_create(ve_timeline_t timeline) {
    return VideoEngine::C::ve_render_graph_create(timeline);
}

void ve_render_graph_destroy(ve_render_graph_t graph) {
    VideoEngine::C::ve_render_graph_destroy(graph);
}

uint32_t ve_render_graph_get_items_at_time(ve_render_graph_t graph,
                                           ve_time_ms_t time_ms,
                                           ve_render_item_t* out_items,
                                           uint32_t max_items) {
    return VideoEngine::C::ve_render_graph_get_items_at_time(graph, time_ms, out_items, max_items);
}

bool ve_render_graph_has_visible_items(ve_render_graph_t graph, ve_time_ms_t time_ms) {
    return VideoEngine::C::ve_render_graph_has_visible_items(graph, time_ms);
}

uint32_t ve_render_graph_get_transitions_at_time(ve_render_graph_t graph,
                                                 ve_time_ms_t time_ms,
                                                 ve_transition_info_t* out_transitions,
                                                 uint32_t max_transitions) {
    return VideoEngine::C::ve_render_graph_get_transitions_at_time(graph, time_ms, out_transitions, max_transitions);
}

uint32_t ve_render_graph_get_all_transitions(ve_render_graph_t graph,
                                             ve_transition_info_t* out_transitions,
                                             uint32_t max_transitions) {
    return VideoEngine::C::ve_render_graph_get_all_transitions(graph, out_transitions, max_transitions);
}

ve_error_t ve_clip_add_fade_in_effect(ve_clip_t clip, ve_time_ms_t fade_duration_ms) {
    return VideoEngine::C::ve_clip_add_fade_in_effect(clip, fade_duration_ms);
}

ve_error_t ve_clip_add_fade_out_effect(ve_clip_t clip, ve_time_ms_t fade_duration_ms) {
    return VideoEngine::C::ve_clip_add_fade_out_effect(clip, fade_duration_ms);
}

ve_error_t ve_clip_add_slowmo_effect(ve_clip_t clip, float speed_multiplier) {
    return VideoEngine::C::ve_clip_add_slowmo_effect(clip, speed_multiplier);
}

size_t ve_get_version(char* buffer, size_t buffer_size) {
    return VideoEngine::C::ve_get_version(buffer, buffer_size);
}

size_t ve_get_last_error(char* buffer, size_t buffer_size) {
    return VideoEngine::C::ve_get_last_error(buffer, buffer_size);
}

}
