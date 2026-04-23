#!/usr/bin/env bash
set -euo pipefail

OUT_ROOT="/home/am/storyline/android/build/github-device/track-select-verify-$(date +%Y%m%d_%H%M%S)"
mkdir -p "$OUT_ROOT"
APP="com.storyline.app/com.video.engine.MainActivity"

require_device() {
  adb wait-for-device >/dev/null 2>&1 || true
  adb devices | tail -n +2 | grep -q '\bdevice$'
}

start_action() {
  local action="$1"
  shift || true
  local token="${action}_$(date +%s%N)"
  adb shell am start -n "$APP" --es adb_action "$action" --es adb_token "$token" "$@" >/dev/null 2>&1 || true
}

dump_state() {
  local prefix="$1"
  adb exec-out uiautomator dump /dev/tty > "$OUT_ROOT/${prefix}.xml" || true
  adb exec-out screencap -p > "$OUT_ROOT/${prefix}.png" || true
  adb logcat -d -v time > "$OUT_ROOT/${prefix}.logcat.txt" || true
}

format_time_label() {
  local ms="$1"
  local total_seconds=$(( ms / 1000 ))
  printf "%02d:%02d" $(( total_seconds / 60 )) $(( total_seconds % 60 ))
}

wait_for_time_visible() {
  local expected="$1"
  local tmp_xml="$OUT_ROOT/.time_check.xml"
  local attempt
  for attempt in $(seq 1 8); do
    adb exec-out uiautomator dump /dev/tty > "$tmp_xml" 2>/dev/null || true
    if rg -q "$expected" "$tmp_xml"; then
      return 0
    fi
    sleep 1
  done
  return 1
}

assert_contains() {
  local file="$1"
  local pattern="$2"
  rg -q "$pattern" "$file"
}

import_track_at() {
  local playhead_ms="$1"
  local action="$2"
  local expected_time
  expected_time="$(format_time_label "$playhead_ms")"
  local attempt
  for attempt in 1 2 3; do
    start_action set_playhead_ms --es adb_time_ms "$playhead_ms"
    sleep 1
    if wait_for_time_visible "$expected_time"; then
      break
    fi
  done
  if [[ "$action" == "add_text" ]]; then
    start_action add_text --es adb_text "TrackProof"
    sleep 4
  else
    start_action "$action"
    sleep 7
  fi
}

verify_track() {
  local name="$1"
  local label="$2"
  local expected_time="$3"
  local scale_factor="$4"

  adb logcat -c || true
  start_action select_track_clip --es adb_track_type "${name^^}"
  sleep 2
  start_action scale_selected_preview --es adb_factor "$scale_factor"
  sleep 2
  dump_state "$name"

  local xml="$OUT_ROOT/${name}.xml"
  local log="$OUT_ROOT/${name}.logcat.txt"
  local ok=1

  if ! assert_contains "$xml" "$label"; then
    echo "$name: missing_label:$label" >> "$OUT_ROOT/summary.txt"
    ok=0
  fi

  if ! assert_contains "$xml" "$expected_time"; then
    echo "$name: missing_time:$expected_time" >> "$OUT_ROOT/summary.txt"
    ok=0
  fi

  if ! assert_contains "$log" "\\[Automation\\] select_track_clip track=${name^^}"; then
    echo "$name: missing_select_log" >> "$OUT_ROOT/summary.txt"
    ok=0
  fi

  if ! assert_contains "$log" "\\[Automation\\] scale_selected_preview applied=true"; then
    echo "$name: missing_scale_log" >> "$OUT_ROOT/summary.txt"
    ok=0
  fi

  if [[ "$name" == "text" ]] && ! assert_contains "$xml" "TrackProof"; then
    echo "$name: missing_text_preview" >> "$OUT_ROOT/summary.txt"
    ok=0
  fi

  if [[ "$ok" -eq 1 ]]; then
    echo "$name=pass" >> "$OUT_ROOT/summary.txt"
  else
    echo "$name=fail" >> "$OUT_ROOT/summary.txt"
  fi
}

main() {
  : > "$OUT_ROOT/summary.txt"

  if ! require_device; then
    echo "device=missing" > "$OUT_ROOT/summary.txt"
    echo "$OUT_ROOT"
    exit 2
  fi

  adb logcat -c || true
  start_action discard_autosave
  sleep 1
  start_action reset_to_blank
  sleep 3

  import_track_at 0 quick_import_video
  import_track_at 3000 quick_import_layer
  import_track_at 6000 quick_import_overlay
  import_track_at 9000 add_text

  verify_track video "VIDEO CLIP" "00:00" "1.20"
  verify_track layer "MEDIA LAYER" "00:03" "1.30"
  verify_track overlay "OVERLAY LAYER" "00:06" "1.40"
  verify_track text "TEXT LAYER" "00:09" "1.25"

  echo "$OUT_ROOT"
}

main "$@"
