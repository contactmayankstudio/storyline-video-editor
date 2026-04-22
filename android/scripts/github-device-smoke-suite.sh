#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BRIDGE_SCRIPT="${SCRIPT_DIR}/github-assemble-install-logcat.sh"
APP_ID="${ANDROID_APP_ID:-com.storyline.app}"
LAUNCH_ACTIVITY="${ANDROID_LAUNCH_ACTIVITY:-com.video.engine.MainActivity}"
PLAYBACK_TIMEOUT_SECONDS="${PLAYBACK_TIMEOUT_SECONDS:-50}"
AUTOSAVE_TIMEOUT_SECONDS="${AUTOSAVE_TIMEOUT_SECONDS:-20}"
EXPORT_TIMEOUT_SECONDS="${EXPORT_TIMEOUT_SECONDS:-420}"
POLL_SECONDS="${SUITE_POLL_SECONDS:-5}"
OUTPUT_DIR=""
SUITE_DIR=""

usage() {
    cat <<EOF
Usage: GITHUB_TOKEN=... $(basename "$0") [bridge options]

This wraps github-assemble-install-logcat.sh, then runs cold-start ADB smoke tests:
  - playback quick smoke
  - autosave/restore smoke
  - export smoke with progress capture

Bridge options are forwarded as-is:
  --dispatch
  --sha <commit>
  --branch <branch>

Environment overrides:
  PLAYBACK_TIMEOUT_SECONDS
  AUTOSAVE_TIMEOUT_SECONDS
  EXPORT_TIMEOUT_SECONDS
  SUITE_POLL_SECONDS
  ANDROID_APP_ID
  ANDROID_LAUNCH_ACTIVITY
EOF
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
    usage
    exit 0
fi

for cmd in adb awk grep sed mktemp rg; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
        echo "Missing required command: $cmd" >&2
        exit 1
    fi
done

log() {
    printf '[smoke-suite] %s\n' "$*"
}

ensure_device() {
    adb start-server >/dev/null
    adb wait-for-device >/dev/null
}

capture_state() {
    local label="$1"
    ensure_device
    adb exec-out screencap -p > "${SUITE_DIR}/${label}.png" 2>/dev/null || true
    (adb shell uiautomator dump "/sdcard/${label}.xml" >/dev/null 2>&1 && \
        adb pull "/sdcard/${label}.xml" "${SUITE_DIR}/${label}.xml" >/dev/null 2>&1) || true
    adb logcat -d -v time > "${SUITE_DIR}/${label}.logcat.txt" 2>/dev/null || true
    adb shell dumpsys notification --noredact > "${SUITE_DIR}/${label}.notification.txt" 2>/dev/null || true
    adb shell dumpsys activity activities | rg -n 'mResumedActivity|topResumedActivity' > "${SUITE_DIR}/${label}.activity.txt" 2>/dev/null || true
    adb shell 'date; ls -l /sdcard/Android/data/com.storyline.app/files/exports 2>/dev/null; ls -l /storage/emulated/0/Movies/Storyline 2>/dev/null' \
        > "${SUITE_DIR}/${label}.exports.txt" 2>/dev/null || true
}

start_cold_action() {
    local label="$1"
    local action="$2"
    shift 2
    local token="${label}_$(date +%s)"
    ensure_device
    adb shell am force-stop "${APP_ID}" >/dev/null 2>&1 || true
    adb logcat -c || true
    adb shell am start -n "${APP_ID}/${LAUNCH_ACTIVITY}" \
        --es adb_action "${action}" \
        --es adb_token "${token}" \
        "$@" > "${SUITE_DIR}/${label}.start.txt" 2>&1
}

wait_for_log_pattern() {
    local pattern="$1"
    local timeout_seconds="$2"
    local label="$3"
    local waited=0
    while (( waited < timeout_seconds )); do
        ensure_device
        adb logcat -d -v time > "${SUITE_DIR}/${label}.poll.logcat.txt" 2>/dev/null || true
        if [[ -f "${SUITE_DIR}/${label}.poll.logcat.txt" ]] && rg -q "${pattern}" "${SUITE_DIR}/${label}.poll.logcat.txt"; then
            return 0
        fi
        sleep "${POLL_SECONDS}"
        waited=$((waited + POLL_SECONDS))
    done
    return 1
}

append_summary() {
    printf '%s\n' "$*" >> "${SUITE_DIR}/summary.txt"
}

bridge_output_file="$(mktemp)"
trap 'rm -f "${bridge_output_file}"' EXIT

log "Pulling GitHub-built APK and installing on device..."
"${BRIDGE_SCRIPT}" "$@" | tee "${bridge_output_file}"

meta_file="$(awk '/Run metadata saved to / {print $NF}' "${bridge_output_file}" | tail -n 1)"
if [[ -z "${meta_file}" || ! -f "${meta_file}" ]]; then
    echo "Unable to find bridge metadata file in output." >&2
    exit 1
fi

OUTPUT_DIR="$(dirname "${meta_file}")"
SUITE_DIR="${OUTPUT_DIR}/smoke-suite-$(date +%Y%m%d_%H%M%S)"
mkdir -p "${SUITE_DIR}"

append_summary "meta_file=${meta_file}"
append_summary "output_dir=${OUTPUT_DIR}"
append_summary "suite_dir=${SUITE_DIR}"
append_summary "started_at=$(date -Is)"

log "Running playback smoke..."
start_cold_action "playback" "smoke_playback_quick"
sleep 8
capture_state "playback_initial"
if wait_for_log_pattern '\[Preview\] playback reached end|Native playback stopped' "${PLAYBACK_TIMEOUT_SECONDS}" "playback"; then
    append_summary "playback=pass"
else
    append_summary "playback=timeout"
fi
capture_state "playback_final"

log "Running autosave smoke..."
start_cold_action "autosave_prepare" "smoke_autosave_prepare" --es adb_text "AutoSmoke"
sleep 6
if wait_for_log_pattern '\[Project\] autosave complete success=true' "${AUTOSAVE_TIMEOUT_SECONDS}" "autosave_prepare"; then
    append_summary "autosave_prepare=pass"
else
    append_summary "autosave_prepare=timeout"
fi
capture_state "autosave_prepare_final"

log "Running autosave restore smoke..."
start_cold_action "autosave_restore" "restore_autosave"
sleep 6
if wait_for_log_pattern '\[Project\] load complete success=true' "${AUTOSAVE_TIMEOUT_SECONDS}" "autosave_restore"; then
    append_summary "autosave_restore=pass"
else
    append_summary "autosave_restore=timeout"
fi
capture_state "autosave_restore_final"

log "Running export smoke..."
start_cold_action "export" "smoke_export_720" --es adb_text "ExportSmoke"
sleep 10
capture_state "export_initial"

export_waited=0
export_result="timeout"
last_video_only_size=0
while (( export_waited < EXPORT_TIMEOUT_SECONDS )); do
    ensure_device
    adb logcat -d -v time > "${SUITE_DIR}/export_watch.logcat.txt" 2>/dev/null || true
    adb shell 'ls -l /sdcard/Android/data/com.storyline.app/files/exports 2>/dev/null; ls -l /storage/emulated/0/Movies/Storyline 2>/dev/null' \
        > "${SUITE_DIR}/export_watch.exports.txt" 2>/dev/null || true

    if rg -q 'Export successful:|\[Export\] Export complete:' "${SUITE_DIR}/export_watch.logcat.txt"; then
        export_result="pass"
        break
    fi
    if rg -q 'Export failed|bad_alloc|FATAL EXCEPTION' "${SUITE_DIR}/export_watch.logcat.txt"; then
        export_result="fail"
        break
    fi

    current_video_only_size="$(
        awk '/\\.video_only\\.mp4$/ {size=$5} END {print size+0}' "${SUITE_DIR}/export_watch.exports.txt"
    )"
    if (( current_video_only_size > last_video_only_size )); then
        append_summary "export_progress=${export_waited}s video_only_bytes=${current_video_only_size}"
        last_video_only_size="${current_video_only_size}"
    fi

    if (( export_waited > 0 )) && (( export_waited % 30 == 0 )); then
        capture_state "export_${export_waited}s"
    fi
    sleep "${POLL_SECONDS}"
    export_waited=$((export_waited + POLL_SECONDS))
done

append_summary "export=${export_result}"
append_summary "finished_at=$(date -Is)"
capture_state "export_final"

log "Smoke suite complete. Summary: ${SUITE_DIR}/summary.txt"
