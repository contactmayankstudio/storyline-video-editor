#!/usr/bin/env bash
set -euo pipefail

APP_ID="${ANDROID_APP_ID:-com.storyline.app}"
LAUNCH_ACTIVITY="${ANDROID_LAUNCH_ACTIVITY:-com.video.engine.MainActivity}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUTPUT_DIR="${ROOT_DIR}/android/build/github-device/toolbar-audit-$(date +%Y%m%d_%H%M%S)"
CURRENT_XML=""
SUMMARY_FILE=""

usage() {
    cat <<EOF
Usage: $(basename "$0") [--output-dir <dir>]

Runs a portrait adb audit on the currently installed Storyline build:
  - launch app
  - import Media > Quick Sample
  - reveal selected-clip toolbar actions
  - verify Color / Effects / Transition / Graphics / Stack
  - verify Stack > Quick Sample selects an overlay layer

Writes screenshots, XML dumps, and a summary file under android/build/github-device/.
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
    printf '[toolbar-audit] %s\n' "$*"
}

append_summary() {
    printf '%s\n' "$*" >> "$SUMMARY_FILE"
}

require_cmd() {
    for cmd in adb rg sed awk grep; do
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

find_bounds_by_pattern() {
    local xml_file="$1"
    local pattern="$2"
    python3 - "$xml_file" "$pattern" <<'PY'
import re
import sys

xml_path, pattern = sys.argv[1], sys.argv[2]
text = open(xml_path, "r", encoding="utf-8").read()
match = re.search(pattern + r'[^>]*bounds="(\[[0-9,]+\]\[[0-9,]+\])"', text)
if not match:
    sys.exit(1)
print(match.group(1))
PY
}

tap_bounds() {
    local bounds="$1"
    local coords
    coords="$(
        python3 - "$bounds" <<'PY'
import re
import sys

bounds = sys.argv[1]
nums = list(map(int, re.findall(r'\d+', bounds)))
x1, y1, x2, y2 = nums
print(f"{(x1 + x2) // 2} {(y1 + y2) // 2}")
PY
    )"
    adb shell input tap ${coords}
}

dismiss_sheet() {
    adb shell input tap 360 900
    sleep 1
}

clear_resume_dialog_if_needed() {
    if rg -q 'Resume Last Session' "$CURRENT_XML"; then
        log "Discarding resume dialog for a clean toolbar audit..."
        tap_by_text "$CURRENT_XML" "DISCARD"
        sleep 1
        dump_ui "01_launch_ready"
    fi
}

launch_editor() {
    ensure_device
    adb shell input keyevent HOME >/dev/null 2>&1 || true
    adb shell am force-stop "${APP_ID}" >/dev/null 2>&1 || true
    adb shell am force-stop com.google.android.documentsui >/dev/null 2>&1 || true
    adb shell am force-stop com.android.documentsui >/dev/null 2>&1 || true
    adb shell am start -n "${APP_ID}/${LAUNCH_ACTIVITY}" >/dev/null
    sleep 2
}

swipe_toolbar_short() {
    adb shell input swipe 640 1412 420 1412 180
    sleep 1
}

swipe_toolbar_long() {
    adb shell input swipe 680 1412 120 1412 180
    sleep 1
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

verify_contains() {
    local file="$1"
    local pattern="$2"
    local label="$3"
    if rg -q "$pattern" "$file"; then
        append_summary "${label}=pass"
    else
        append_summary "${label}=fail"
        return 1
    fi
}

ensure_storyline_front() {
    local label_prefix="$1"
    if rg -q 'package="com.storyline.app"' "$CURRENT_XML"; then
        return 0
    fi
    log "Detected external picker on top, resetting back to Storyline..."
    launch_editor
    dump_ui "${label_prefix}_launch_retry"
}

prepare_video_clip() {
    local label_prefix="$1"
    launch_editor
    dump_ui "${label_prefix}_launch"
    ensure_storyline_front "$label_prefix"
    clear_resume_dialog_if_needed
    tap_by_text "$CURRENT_XML" "Media"
    dump_ui "${label_prefix}_media_sheet"
    verify_contains "$CURRENT_XML" 'text="Quick Sample"' "${label_prefix}_media_sheet"
    tap_by_text "$CURRENT_XML" "Quick Sample"
    sleep 2
    dump_ui "${label_prefix}_after_import"
    verify_contains "$CURRENT_XML" 'VIDEO CLIP' "${label_prefix}_video_clip_selected"
}

require_cmd
append_summary "started_at=$(date -Is)"
append_summary "output_dir=${OUTPUT_DIR}"

log "Launching editor in portrait..."
set_portrait
prepare_video_clip "01_color"

log "Opening Color sheet..."
swipe_toolbar_short
dump_ui "01_color_reveal"
tap_by_resource_id "${APP_ID}:id/clipBrightnessButton"
dump_ui "01_color_open"
verify_contains "$CURRENT_XML" 'brightnessSeekBar' "color_sheet"

log "Opening Effects sheet..."
prepare_video_clip "02_effects"
swipe_toolbar_short
dump_ui "02_effects_reveal"
tap_by_resource_id "${APP_ID}:id/clipFilterButton"
dump_ui "02_effects_open"
verify_contains "$CURRENT_XML" 'Studio FX|LUT Library|Reset FX' "effects_sheet"

log "Opening Transition sheet..."
prepare_video_clip "03_transition"
swipe_toolbar_short
dump_ui "03_transition_reveal"
tap_by_resource_id "${APP_ID}:id/clipTransitionButton"
dump_ui "03_transition_open"
verify_contains "$CURRENT_XML" 'Cross 250|Fade 250|Slide 700' "transition_sheet"

log "Opening Graphics sheet..."
prepare_video_clip "04_graphics"
swipe_toolbar_long
swipe_toolbar_long
dump_ui "04_graphics_reveal"
tap_by_resource_id "${APP_ID}:id/clipGraphicsButton"
dump_ui "04_graphics_open"
verify_contains "$CURRENT_XML" 'Sticker Pack|Overlay Import|Quick Overlay' "graphics_sheet"

log "Opening Stack sheet..."
prepare_video_clip "05_stack"
swipe_toolbar_long
dump_ui "05_stack_reveal"
tap_by_resource_id "${APP_ID}:id/clipAddLayerButton"
dump_ui "05_stack_open"
verify_contains "$CURRENT_XML" 'Browse Layer|Quick Sample|Manage Layers' "stack_sheet"

log "Adding quick sample layer..."
tap_by_text "$CURRENT_XML" "Quick Sample"
sleep 2
dump_ui "05_stack_after_quicksample"
verify_contains "$CURRENT_XML" 'OVERLAY LAYER' "stack_quicksample"

append_summary "finished_at=$(date -Is)"
log "Toolbar audit complete: ${SUMMARY_FILE}"
