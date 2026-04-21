#ifndef VIDEO_ENGINE_C_H
#define VIDEO_ENGINE_C_H

#ifdef __cplusplus
extern "C" {
#endif

#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>

/**
 * C-Compatible API for VideoEngine Core
 * 
 * Features:
 * - No STL types in public API
 * - Opaque pointers for C++ objects
 * - JNI-friendly for Android
 * - NDK-safe with zero native dependencies
 * 
 * All handles must be freed with appropriate ve_*_destroy() functions.
 */

// ============ Opaque Handle Types ============
// These are void pointers to C++ objects, hidden from C code
typedef void* ve_clip_t;
typedef void* ve_timeline_t;
typedef void* ve_render_graph_t;
typedef void* ve_effect_t;

// ============ Error Codes ============
typedef enum {
    VE_OK = 0,
    VE_ERROR_INVALID_HANDLE = 1,
    VE_ERROR_NULL_POINTER = 2,
    VE_ERROR_INVALID_PARAM = 3,
    VE_ERROR_OUT_OF_MEMORY = 4,
    VE_ERROR_FILE_NOT_FOUND = 5,
    VE_ERROR_UNKNOWN = 99
} ve_error_t;

// ============ Primitive Types (C-safe) ============
typedef int64_t ve_time_ms_t;
typedef uint32_t ve_clip_id_t;
typedef uint32_t ve_frame_num_t;

typedef struct {
    uint32_t width;
    uint32_t height;
} ve_resolution_t;

typedef struct {
    ve_resolution_t resolution;
    uint32_t frame_rate;
    float aspect_ratio;
} ve_video_properties_t;

typedef struct {
    uint32_t sample_rate;
    uint8_t channels;
    uint32_t bit_depth;
} ve_audio_properties_t;

// ============ Clip Properties (C-safe) ============
typedef struct {
    float opacity;
    float volume_gain;
    float playback_speed;
    bool enabled;
} ve_clip_properties_t;

// ============ Render Item (C-safe) ============
typedef enum {
    VE_ITEM_TYPE_CLIP = 0,
    VE_ITEM_TYPE_TRANSITION = 1
} ve_item_type_t;

typedef struct {
    ve_item_type_t item_type;
    ve_clip_id_t clip_id;
    uint32_t layer;
    ve_time_ms_t start_ms;
    ve_time_ms_t end_ms;
    float base_opacity;
    float effective_opacity;
    float effective_speed;
    bool enabled;
} ve_render_item_t;

// ============ Transition Info (C-safe) ============
typedef enum {
    VE_TRANSITION_NONE = 0,
    VE_TRANSITION_CROSSFADE = 1,
    VE_TRANSITION_FADE = 2,
    VE_TRANSITION_WIPE = 3,
    VE_TRANSITION_CUSTOM = 4
} ve_transition_type_t;

typedef struct {
    ve_transition_type_t type;
    char id[256];
    uint32_t layer;
    ve_time_ms_t start_ms;
    ve_time_ms_t duration_ms;
} ve_transition_info_t;

// ============ Clip Management ============

/**
 * Create a new clip from a media file.
 * @param media_path Path to media file (null-terminated string)
 * @param start_time_ms Timeline position (milliseconds)
 * @param duration_ms Clip duration (0 = use source duration)
 * @return Clip handle, or NULL on error
 */
ve_clip_t ve_clip_create(const char* media_path, ve_time_ms_t start_time_ms, ve_time_ms_t duration_ms);

/**
 * Destroy clip handle and free resources.
 */
void ve_clip_destroy(ve_clip_t clip);

/**
 * Get clip ID.
 */
ve_clip_id_t ve_clip_get_id(ve_clip_t clip);

/**
 * Get clip media path.
 * @param clip Clip handle
 * @param buffer Output buffer (must be at least 1024 bytes)
 * @param buffer_size Size of output buffer
 * @return Number of bytes written (not including null terminator)
 */
size_t ve_clip_get_media_path(ve_clip_t clip, char* buffer, size_t buffer_size);

/**
 * Get clip timeline position and duration.
 */
ve_error_t ve_clip_get_timing(ve_clip_t clip, ve_time_ms_t* out_start_ms, ve_time_ms_t* out_duration_ms);

/**
 * Set clip timeline position.
 */
ve_error_t ve_clip_set_timing(ve_clip_t clip, ve_time_ms_t start_ms, ve_time_ms_t duration_ms);

/**
 * Get clip properties (opacity, volume, speed, enabled).
 */
ve_error_t ve_clip_get_properties(ve_clip_t clip, ve_clip_properties_t* out_props);

/**
 * Set clip properties.
 */
ve_error_t ve_clip_set_opacity(ve_clip_t clip, float opacity);
ve_error_t ve_clip_set_volume_gain(ve_clip_t clip, float gain);
ve_error_t ve_clip_set_speed(ve_clip_t clip, float speed);
ve_error_t ve_clip_set_enabled(ve_clip_t clip, bool enabled);

// ============ Timeline Management ============

/**
 * Create a new timeline with default properties.
 */
ve_timeline_t ve_timeline_create(void);

/**
 * Create a timeline with custom video/audio properties.
 */
ve_timeline_t ve_timeline_create_with_props(const ve_video_properties_t* vprops,
                                            const ve_audio_properties_t* aprops);

/**
 * Destroy timeline and all clips within it.
 */
void ve_timeline_destroy(ve_timeline_t timeline);

/**
 * Get timeline duration (end of last clip).
 */
ve_time_ms_t ve_timeline_get_duration(ve_timeline_t timeline);

/**
 * Get video properties.
 */
ve_error_t ve_timeline_get_video_properties(ve_timeline_t timeline, ve_video_properties_t* out_props);

/**
 * Get audio properties.
 */
ve_error_t ve_timeline_get_audio_properties(ve_timeline_t timeline, ve_audio_properties_t* out_props);

/**
 * Add clip to timeline (video track 0).
 */
ve_error_t ve_timeline_add_clip(ve_timeline_t timeline, ve_clip_t clip);

/**
 * Remove clip by ID from timeline.
 */
ve_error_t ve_timeline_remove_clip(ve_timeline_t timeline, ve_clip_id_t clip_id);

/**
 * Get clip count on timeline.
 */
uint32_t ve_timeline_get_clip_count(ve_timeline_t timeline);

/**
 * Get clip IDs (caller must allocate output array).
 * @param timeline Timeline handle
 * @param out_ids Output array for clip IDs
 * @param max_ids Maximum number of IDs to write
 * @return Number of clip IDs written
 */
uint32_t ve_timeline_get_clip_ids(ve_timeline_t timeline, ve_clip_id_t* out_ids, uint32_t max_ids);

/**
 * Convert frame number to milliseconds.
 */
ve_time_ms_t ve_timeline_frame_to_ms(ve_timeline_t timeline, ve_frame_num_t frame);

/**
 * Convert milliseconds to frame number.
 */
ve_frame_num_t ve_timeline_ms_to_frame(ve_timeline_t timeline, ve_time_ms_t ms);

// ============ Render Graph ============

/**
 * Create render graph from timeline.
 * @param timeline Source timeline
 * @return Render graph handle, or NULL on error
 */
ve_render_graph_t ve_render_graph_create(ve_timeline_t timeline);

/**
 * Destroy render graph.
 */
void ve_render_graph_destroy(ve_render_graph_t graph);

/**
 * Get visible render items at given time.
 * @param graph Render graph handle
 * @param time_ms Time in milliseconds
 * @param out_items Output array (caller allocates)
 * @param max_items Maximum items to write
 * @return Number of items written
 */
uint32_t ve_render_graph_get_items_at_time(ve_render_graph_t graph,
                                           ve_time_ms_t time_ms,
                                           ve_render_item_t* out_items,
                                           uint32_t max_items);

/**
 * Check if anything is visible at given time.
 */
bool ve_render_graph_has_visible_items(ve_render_graph_t graph, ve_time_ms_t time_ms);

/**
 * Get transitions at given time.
 * @param graph Render graph handle
 * @param time_ms Time in milliseconds
 * @param out_transitions Output array (caller allocates)
 * @param max_transitions Maximum transitions to write
 * @return Number of transitions written
 */
uint32_t ve_render_graph_get_transitions_at_time(ve_render_graph_t graph,
                                                 ve_time_ms_t time_ms,
                                                 ve_transition_info_t* out_transitions,
                                                 uint32_t max_transitions);

/**
 * Get all transitions in graph.
 */
uint32_t ve_render_graph_get_all_transitions(ve_render_graph_t graph,
                                             ve_transition_info_t* out_transitions,
                                             uint32_t max_transitions);

// ============ Effect Management (Simple Interface) ============

/**
 * Add fade-in effect to clip (0 to 1 opacity over duration).
 * @param clip Clip handle
 * @param fade_duration_ms Duration of fade in milliseconds
 */
ve_error_t ve_clip_add_fade_in_effect(ve_clip_t clip, ve_time_ms_t fade_duration_ms);

/**
 * Add fade-out effect to clip (1 to 0 opacity over duration).
 */
ve_error_t ve_clip_add_fade_out_effect(ve_clip_t clip, ve_time_ms_t fade_duration_ms);

/**
 * Add slow-motion effect (reduce speed to 50%).
 */
ve_error_t ve_clip_add_slowmo_effect(ve_clip_t clip, float speed_multiplier);

// ============ Utility & Version ============

/**
 * Get engine version string.
 * @param buffer Output buffer (must be at least 256 bytes)
 * @param buffer_size Size of buffer
 * @return Number of bytes written
 */
size_t ve_get_version(char* buffer, size_t buffer_size);

/**
 * Get last error message (thread-local).
 * @param buffer Output buffer (must be at least 512 bytes)
 * @param buffer_size Size of buffer
 * @return Number of bytes written
 */
size_t ve_get_last_error(char* buffer, size_t buffer_size);

#ifdef __cplusplus
}
#endif

#endif // VIDEO_ENGINE_C_H
