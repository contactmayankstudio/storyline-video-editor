#!/usr/bin/env bash
set -euo pipefail

OUT_ROOT="/home/am/storyline/android/build/github-device/track-verify-$(date +%Y%m%d_%H%M%S)"
mkdir -p "$OUT_ROOT"
APP="com.storyline.app/com.video.engine.MainActivity"

require_device() {
  adb wait-for-device >/dev/null 2>&1 || true
  adb devices | tail -n +2 | grep -q '\bdevice$'
}

start_action() {
  local action="$1"
  shift || true
  adb shell am start -n "$APP" --es adb_action "$action" "$@" >/dev/null 2>&1 || true
}

dump_state() {
  local prefix="$1"
  adb exec-out uiautomator dump /dev/tty > "$OUT_ROOT/${prefix}.xml" || true
  adb exec-out screencap -p > "$OUT_ROOT/${prefix}.png" || true
  adb logcat -d -v time > "$OUT_ROOT/${prefix}.logcat.txt" || true
}

assert_xml_contains() {
  local file="$1"
  local needle="$2"
  rg -q "$needle" "$file"
}

verify_case() {
  local name="$1"
  local action="$2"
  local expect_label="$3"
  local extra_key="$4"

  adb logcat -c || true
  start_action reset_to_blank
  sleep 3

  if [[ "$action" == "add_text" ]]; then
    adb shell am start -n "$APP" --es adb_action add_text --es adb_text "TrackProof" >/dev/null 2>&1 || true
  else
    start_action "$action"
  fi

  sleep 7

  if [[ "$action" != "add_text" ]]; then
    start_action play
    sleep 2
    start_action pause
    sleep 1
  fi

  dump_state "$name"

  local xml="$OUT_ROOT/${name}.xml"
  local ok=1

  if ! assert_xml_contains "$xml" "$expect_label"; then
    echo "$name: missing_label:$expect_label" >> "$OUT_ROOT/summary.txt"
    ok=0
  fi

  if [[ -n "$extra_key" ]] && ! rg -q "$extra_key" "$OUT_ROOT/${name}.logcat.txt"; then
    echo "$name: missing_log:$extra_key" >> "$OUT_ROOT/summary.txt"
    ok=0
  fi

  if [[ "$action" == "add_text" ]] && ! assert_xml_contains "$xml" "TrackProof"; then
    echo "$name: missing_preview_text" >> "$OUT_ROOT/summary.txt"
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

  verify_case video quick_import_video "VIDEO CLIP" "NativeBridge.addClip result clipId:"
  verify_case layer quick_import_layer "MEDIA LAYER" "NativeBridge.addClip result clipId:"
  verify_case overlay quick_import_overlay "OVERLAY LAYER" "NativeBridge.addClip result clipId:"
  verify_case text add_text "TEXT LAYER" "\\[Text\\] added id="

  echo "$OUT_ROOT"
}

main "$@"
