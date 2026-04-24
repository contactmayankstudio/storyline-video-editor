#!/usr/bin/env bash
set -euo pipefail

APP_ID="${ANDROID_APP_ID:-com.storyline.app}"
LAUNCH_ACTIVITY="${ANDROID_LAUNCH_ACTIVITY:-com.video.engine.MainActivity}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUTPUT_DIR="${ROOT_DIR}/android/build/github-device/export-verify-$(date +%Y%m%d_%H%M%S)"
DEVICE_ASSET_DIR="/sdcard/Download/storyline_export_verify"
CURRENT_XML=""
SUMMARY_FILE=""

usage() {
    cat <<EOF
Usage: $(basename "$0") [--output-dir <dir>]

Builds a heavy multitrack export proof project on the installed Storyline app:
  - imports distinct V1A/V1B/L1/O1 clips from local generated media
  - imports A1 audio
  - adds text layer
  - applies clip color effect
  - applies a quick transition
  - exports final MP4
  - extracts proof frames + ffprobe/audio stats
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --output-dir)
            OUTPUT_DIR="$2"
            shift 2
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        *)
            echo "Unknown argument: $1" >&2
            usage >&2
            exit 1
            ;;
    esac
done

mkdir -p "$OUTPUT_DIR"
SUMMARY_FILE="${OUTPUT_DIR}/summary.txt"

log() {
    printf '[export-verify] %s\n' "$*"
}

append_summary() {
    printf '%s\n' "$*" >> "$SUMMARY_FILE"
}

require_cmd() {
    for cmd in adb ffmpeg ffprobe python3 rg awk sed grep; do
        command -v "$cmd" >/dev/null 2>&1 || {
            echo "Missing required command: $cmd" >&2
            exit 1
        }
    done
}

ensure_device() {
    adb start-server >/dev/null
    adb wait-for-device >/dev/null
}

clear_debug_app_state() {
    ensure_device
    adb shell am clear-debug-app >/dev/null 2>&1 || true
    adb shell settings delete global debug_app >/dev/null 2>&1 || true
    adb shell settings delete global wait_for_debugger >/dev/null 2>&1 || true
}

set_portrait() {
    ensure_device
    adb shell settings put system accelerometer_rotation 0 >/dev/null 2>&1 || true
    adb shell settings put system user_rotation 0 >/dev/null 2>&1 || true
}

dump_ui() {
    local label="$1"
    ensure_device
    CURRENT_XML="${OUTPUT_DIR}/${label}.xml"
    adb shell uiautomator dump /sdcard/uidump.xml >/dev/null
    adb pull /sdcard/uidump.xml "$CURRENT_XML" >/dev/null
    adb exec-out screencap -p > "${OUTPUT_DIR}/${label}.png"
}

normalize_storyline_foreground() {
    local label_prefix="$1"
    local attempt
    for attempt in 1 2 3 4; do
        if rg -q 'Waiting For Debugger' "$CURRENT_XML"; then
            clear_debug_app_state
            adb shell am force-stop "${APP_ID}" >/dev/null 2>&1 || true
            adb shell am start -n "${APP_ID}/${LAUNCH_ACTIVITY}" >/dev/null
            sleep 2
            dump_ui "${label_prefix}_debug_retry_${attempt}"
            continue
        fi
        if rg -q 'package="com.google.android.permissioncontroller"' "$CURRENT_XML" && rg -q 'text="ALLOW"' "$CURRENT_XML"; then
            tap_by_text "$CURRENT_XML" "ALLOW"
            sleep 2
            dump_ui "${label_prefix}_allow_${attempt}"
            continue
        fi
        if rg -q 'package="com.storyline.app"' "$CURRENT_XML"; then
            return 0
        fi
        adb shell am start -n "${APP_ID}/${LAUNCH_ACTIVITY}" >/dev/null 2>&1 || true
        sleep 2
        dump_ui "${label_prefix}_retry_${attempt}"
    done
    return 1
}

find_bounds_by_pattern() {
    local xml_file="$1"
    local pattern="$2"
    python3 - "$xml_file" "$pattern" <<'PY'
import re
import sys
text = open(sys.argv[1], "r", encoding="utf-8").read()
pattern = sys.argv[2]
match = re.search(pattern + r'[^>]*bounds="(\[[0-9,]+\]\[[0-9,]+\])"', text)
if not match:
    raise SystemExit(1)
print(match.group(1))
PY
}

tap_bounds() {
    local bounds="$1"
    local coords
    coords="$(
        python3 - "$bounds" <<'PY'
import re, sys
x1, y1, x2, y2 = map(int, re.findall(r'\d+', sys.argv[1]))
print(f"{(x1 + x2)//2} {(y1 + y2)//2}")
PY
    )"
    adb shell input tap ${coords}
}

tap_by_resource_id() {
    local resource_id="$1"
    local bounds
    bounds="$(find_bounds_by_pattern "$CURRENT_XML" "resource-id=\"${resource_id}\"")"
    tap_bounds "$bounds"
    sleep 1
}

tap_by_text() {
    local xml_file="$1"
    local text="$2"
    local bounds
    bounds="$(find_bounds_by_pattern "$xml_file" "text=\"${text}\"")"
    tap_bounds "$bounds"
    sleep 1
}

swipe_toolbar_short() {
    adb shell input swipe 640 1412 420 1412 180
    sleep 1
}

swipe_toolbar_right() {
    adb shell input swipe 120 1412 680 1412 180
    sleep 1
}

reset_toolbar_to_left() {
    local attempt
    for attempt in 1 2 3 4 5; do
        swipe_toolbar_right
    done
}

reveal_main_toolbar_button() {
    local resource_id="$1"
    local label="$2"
    local attempt
    reset_toolbar_to_left
    for attempt in 1 2 3 4 5 6 7 8; do
        dump_ui "${label}_reveal_${attempt}"
        if rg -q "resource-id=\"${resource_id}\"" "$CURRENT_XML"; then
            return 0
        fi
        swipe_toolbar_short
    done
    return 1
}

open_main_toolbar_button() {
    local resource_id="$1"
    local label="$2"
    reveal_main_toolbar_button "$resource_id" "$label"
    tap_by_resource_id "$resource_id"
}

swipe_within_bounds() {
    local bounds="$1"
    local start_ratio="$2"
    local end_ratio="$3"
    local coords
    coords="$(
        python3 - "$bounds" "$start_ratio" "$end_ratio" <<'PY'
import re, sys
x1, y1, x2, y2 = map(int, re.findall(r'\d+', sys.argv[1]))
start_ratio = float(sys.argv[2]); end_ratio = float(sys.argv[3])
y = (y1 + y2) // 2
sx = int(x1 + (x2 - x1) * start_ratio)
ex = int(x1 + (x2 - x1) * end_ratio)
print(f"{sx} {y} {ex} {y}")
PY
    )"
    adb shell input swipe ${coords} 220
    sleep 1
}

send_action() {
    local action="$1"
    local token="${action}_$(date +%s%N)"
    shift
    adb shell am start -n "${APP_ID}/${LAUNCH_ACTIVITY}" \
        --es adb_action "${action}" \
        --es adb_token "${token}" \
        "$@" >/dev/null 2>&1 || true
    sleep 2
}

wait_for_contains() {
    local file="$1"
    local pattern="$2"
    local timeout="${3:-12}"
    local waited=0
    while (( waited < timeout )); do
        if rg -q "$pattern" "$file" 2>/dev/null; then
            return 0
        fi
        sleep 1
        waited=$((waited + 1))
    done
    return 1
}

prepare_assets() {
    local asset_dir="${OUTPUT_DIR}/assets"
    mkdir -p "$asset_dir"

    local font="/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"

    ffmpeg -y -f lavfi -i "color=c=#505050:s=1280x720:d=6" \
        -vf "drawtext=fontfile=${font}:text='V1A':fontcolor=white:fontsize=110:x=(w-text_w)/2:y=(h-text_h)/2" \
        -c:v libx264 -pix_fmt yuv420p "${asset_dir}/v1a.mp4" >/dev/null 2>&1

    ffmpeg -y -f lavfi -i "color=c=#8E2DE2:s=1280x720:d=6" \
        -vf "drawtext=fontfile=${font}:text='V1B':fontcolor=white:fontsize=110:x=(w-text_w)/2:y=(h-text_h)/2" \
        -c:v libx264 -pix_fmt yuv420p "${asset_dir}/v1b.mp4" >/dev/null 2>&1

    ffmpeg -y -f lavfi -i "color=c=#0047AB:s=1280x720:d=6" \
        -vf "drawtext=fontfile=${font}:text='L1':fontcolor=white:fontsize=110:x=(w-text_w)/2:y=(h-text_h)/2" \
        -c:v libx264 -pix_fmt yuv420p "${asset_dir}/l1.mp4" >/dev/null 2>&1

    ffmpeg -y -f lavfi -i "color=c=#E8B000:s=1280x720:d=6" \
        -vf "drawtext=fontfile=${font}:text='O1':fontcolor=black:fontsize=110:x=(w-text_w)/2:y=(h-text_h)/2" \
        -c:v libx264 -pix_fmt yuv420p "${asset_dir}/o1.mp4" >/dev/null 2>&1

    ffmpeg -y -f lavfi -i "sine=frequency=440:duration=12:sample_rate=44100" \
        -c:a pcm_s16le "${asset_dir}/a1.wav" >/dev/null 2>&1

    adb shell "mkdir -p ${DEVICE_ASSET_DIR}" >/dev/null 2>&1 || true
    adb push "${asset_dir}/v1a.mp4" "${DEVICE_ASSET_DIR}/v1a.mp4" >/dev/null
    adb push "${asset_dir}/v1b.mp4" "${DEVICE_ASSET_DIR}/v1b.mp4" >/dev/null
    adb push "${asset_dir}/l1.mp4" "${DEVICE_ASSET_DIR}/l1.mp4" >/dev/null
    adb push "${asset_dir}/o1.mp4" "${DEVICE_ASSET_DIR}/o1.mp4" >/dev/null
    adb push "${asset_dir}/a1.wav" "${DEVICE_ASSET_DIR}/a1.wav" >/dev/null
}

dismiss_resume_if_needed() {
    if rg -q 'Resume Last Session' "$CURRENT_XML"; then
        tap_by_text "$CURRENT_XML" "DISCARD"
        sleep 1
        dump_ui "01_after_discard"
    fi
}

launch_clean() {
    clear_debug_app_state
    adb shell am force-stop "${APP_ID}" >/dev/null 2>&1 || true
    adb shell am start -n "${APP_ID}/${LAUNCH_ACTIVITY}" >/dev/null
    sleep 2
    dump_ui "00_launch"
    normalize_storyline_foreground "00_launch"
    dismiss_resume_if_needed
    send_action "reset_to_blank"
    dump_ui "01_blank"
    normalize_storyline_foreground "01_blank"
}

import_media_track() {
    local time_ms="$1"
    local track="$2"
    local device_path="$3"
    send_action "set_playhead_ms" --es adb_time_ms "${time_ms}"
    send_action "import_media_path" --es adb_track_type "${track}" --es adb_path "${device_path}"
    sleep 2
    dump_ui "import_${track}_${time_ms}"
    normalize_storyline_foreground "import_${track}_${time_ms}"
}

import_audio_track() {
    local time_ms="$1"
    local device_path="$2"
    send_action "set_playhead_ms" --es adb_time_ms "${time_ms}"
    send_action "import_audio_path" --es adb_path "${device_path}"
    sleep 2
    dump_ui "import_audio_${time_ms}"
    normalize_storyline_foreground "import_audio_${time_ms}"
}

apply_color_effect_to_selected_clip() {
    dump_ui "03_before_color"
    open_main_toolbar_button "${APP_ID}:id/clipBrightnessButton" "03_color"
    dump_ui "03_color_open"
    local brightness_bounds contrast_bounds
    brightness_bounds="$(find_bounds_by_pattern "$CURRENT_XML" "resource-id=\"${APP_ID}:id/brightnessSeekBar\"")"
    contrast_bounds="$(find_bounds_by_pattern "$CURRENT_XML" "resource-id=\"${APP_ID}:id/contrastSeekBar\"")"
    swipe_within_bounds "$brightness_bounds" 0.50 0.90
    swipe_within_bounds "$contrast_bounds" 0.50 0.75
    dump_ui "03_color_applied"
    open_main_toolbar_button "${APP_ID}:id/clipBrightnessButton" "03_color_close"
    dump_ui "03_color_closed"
}

apply_transition_between_v1_clips() {
    dump_ui "04_transition_selected"
    adb logcat -c >/dev/null 2>&1 || true
    open_main_toolbar_button "${APP_ID}:id/clipTransitionButton" "04_transition"
    dump_ui "04_transition_sheet"
    tap_by_text "$CURRENT_XML" "Cross 250"
    sleep 2
    adb logcat -d -v time > "${OUTPUT_DIR}/04_transition.logcat.txt"
    dump_ui "04_transition_applied"
}

build_project() {
    import_media_track 0 VIDEO "${DEVICE_ASSET_DIR}/v1a.mp4"
    dump_ui "02_v1a_imported"
    apply_color_effect_to_selected_clip

    import_media_track 6000 VIDEO "${DEVICE_ASSET_DIR}/v1b.mp4"
    dump_ui "04_v1b_imported"
    apply_transition_between_v1_clips

    import_media_track 3000 LAYER "${DEVICE_ASSET_DIR}/l1.mp4"
    dump_ui "05_l1_imported"

    import_media_track 6000 OVERLAY "${DEVICE_ASSET_DIR}/o1.mp4"
    dump_ui "06_o1_imported"

    send_action "set_playhead_ms" --es adb_time_ms "9000"
    send_action "add_text" --es adb_text "TXT"
    dump_ui "07_text_added"

    import_audio_track 0 "${DEVICE_ASSET_DIR}/a1.wav"
    dump_ui "08_audio_added"

    send_action "set_playhead_ms" --es adb_time_ms "0"
    dump_ui "09_final_timeline"
}

wait_for_export() {
    local before_movie before_export after_movie after_export
    before_movie="$(adb shell 'ls -1t /storage/emulated/0/Movies/Storyline/*.mp4 2>/dev/null | head -n 1' 2>/dev/null | tr -d '\r')"
    before_export="$(adb shell 'ls -1t /sdcard/Android/data/com.storyline.app/files/exports/*.mp4 2>/dev/null | grep -v "\\.video_only\\.mp4$" | head -n 1' 2>/dev/null | tr -d '\r')"
    append_summary "before_movie=${before_movie}"
    append_summary "before_export=${before_export}"
    adb logcat -c >/dev/null 2>&1 || true
    send_action "export_720"
    local waited=0
    while (( waited < 720 )); do
        adb logcat -d -v time > "${OUTPUT_DIR}/export_watch.logcat.txt" 2>/dev/null || true
        after_movie="$(adb shell 'ls -1t /storage/emulated/0/Movies/Storyline/*.mp4 2>/dev/null | head -n 1' 2>/dev/null | tr -d '\r')"
        after_export="$(adb shell 'ls -1t /sdcard/Android/data/com.storyline.app/files/exports/*.mp4 2>/dev/null | grep -v "\\.video_only\\.mp4$" | head -n 1' 2>/dev/null | tr -d '\r')"
        if [[ -n "${after_movie}" && "${after_movie}" == "${before_movie}" ]]; then
            after_movie=""
        fi
        if [[ -n "${after_export}" && "${after_export}" == "${before_export}" ]]; then
            after_export=""
        fi
        if rg -q 'Export successful:|\[Export\] Export complete:' "${OUTPUT_DIR}/export_watch.logcat.txt"; then
            echo "${after_movie}|${after_export}"
            return 0
        fi
        if [[ -n "${after_movie}" && "${after_movie}" != "${before_movie}" ]]; then
            echo "${after_movie}|${after_export}"
            return 0
        fi
        if [[ -n "${after_export}" && "${after_export}" != "${before_export}" ]]; then
            echo "${after_movie}|${after_export}"
            return 0
        fi
        if rg -q 'E/AndroidPreview: \[Export\] Exception:|E/\[UI\].*Export failed|std::bad_alloc|java\.lang\.OutOfMemoryError' "${OUTPUT_DIR}/export_watch.logcat.txt"; then
            return 1
        fi
        sleep 5
        waited=$((waited + 5))
    done
    return 1
}

pull_and_analyze_export() {
    local movie_path="$1"
    local export_path="$2"
    local final_device_path="${export_path}"
    if [[ -z "${final_device_path}" ]]; then
        final_device_path="${movie_path}"
    fi
    [[ -n "${final_device_path}" ]] || return 1
    local local_mp4="${OUTPUT_DIR}/final_export.mp4"
    adb pull "${final_device_path}" "${local_mp4}" >/dev/null
    ffprobe -hide_banner -show_streams -show_format "${local_mp4}" > "${OUTPUT_DIR}/final_export.ffprobe.txt" 2>&1
    ffmpeg -y -ss 1 -i "${local_mp4}" -frames:v 1 "${OUTPUT_DIR}/frame_01s.png" >/dev/null 2>&1
    ffmpeg -y -ss 4 -i "${local_mp4}" -frames:v 1 "${OUTPUT_DIR}/frame_04s.png" >/dev/null 2>&1
    ffmpeg -y -ss 7 -i "${local_mp4}" -frames:v 1 "${OUTPUT_DIR}/frame_07s.png" >/dev/null 2>&1
    ffmpeg -y -ss 10 -i "${local_mp4}" -frames:v 1 "${OUTPUT_DIR}/frame_10s.png" >/dev/null 2>&1
    ffmpeg -y -ss 5.95 -i "${local_mp4}" -frames:v 1 "${OUTPUT_DIR}/frame_595s.png" >/dev/null 2>&1
    ffmpeg -y -ss 6.05 -i "${local_mp4}" -frames:v 1 "${OUTPUT_DIR}/frame_605s.png" >/dev/null 2>&1
    ffmpeg -y -ss 1 -i "${OUTPUT_DIR}/assets/v1a.mp4" -frames:v 1 "${OUTPUT_DIR}/source_v1a_01s.png" >/dev/null 2>&1
    ffmpeg -hide_banner -ss 1 -i "${local_mp4}" -frames:v 1 -vf signalstats -f null - > "${OUTPUT_DIR}/export_frame_stats.txt" 2>&1 || true
    ffmpeg -hide_banner -ss 1 -i "${OUTPUT_DIR}/assets/v1a.mp4" -frames:v 1 -vf signalstats -f null - > "${OUTPUT_DIR}/source_frame_stats.txt" 2>&1 || true
    ffmpeg -hide_banner -i "${local_mp4}" -af astats=metadata=1:reset=1 -f null - > "${OUTPUT_DIR}/audio_stats.txt" 2>&1 || true
}

main() {
    require_cmd
    ensure_device
    set_portrait
    append_summary "started_at=$(date -Is)"
    append_summary "output_dir=${OUTPUT_DIR}"
    log "Generating distinct test assets..."
    prepare_assets
    log "Launching clean editor..."
    launch_clean
    log "Building multitrack project..."
    build_project
    log "Exporting final MP4..."
    local export_refs
    if ! export_refs="$(wait_for_export)"; then
        append_summary "export=fail"
        dump_ui "export_failed"
        exit 1
    fi
    local movie_path="${export_refs%%|*}"
    local export_path="${export_refs#*|}"
    append_summary "export=pass"
    append_summary "movie_path=${movie_path}"
    append_summary "export_path=${export_path}"
    log "Pulling and analyzing export..."
    pull_and_analyze_export "${movie_path}" "${export_path}"
    append_summary "finished_at=$(date -Is)"
    log "Export verify complete: ${OUTPUT_DIR}"
}

main "$@"
