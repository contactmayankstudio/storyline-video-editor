#!/usr/bin/env bash
set -euo pipefail

# Simple file-watch auto-build script using inotifywait
# Watches repository changes and runs cmake build on change

WATCH_DIRS=(".")
BUILD_DIR="build"
NPROC=$(nproc)
CMD=(cmake --build "$BUILD_DIR" --config Release -j "$NPROC")

mkdir -p "$BUILD_DIR"

if ! command -v inotifywait >/dev/null 2>&1; then
  echo "inotifywait not found. Install with: sudo apt install inotify-tools"
  exit 1
fi

assemble() {
  echo "Running assemble: build and run the project"
  if "${CMD[@]}"; then
    echo "Build succeeded at $(date '+%Y-%m-%d %H:%M:%S')"
    # Assuming the executable is in build directory and named 'storyline'
    EXECUTABLE="$BUILD_DIR/storyline"
    if [[ -x "$EXECUTABLE" ]]; then
      echo "Running executable: $EXECUTABLE"
      "$EXECUTABLE"
    else
      echo "Executable $EXECUTABLE not found or not executable."
      exit 1
    fi
  else
    echo "Build failed at $(date '+%Y-%m-%d %H:%M:%S')"
    exit 1
  fi
}

if [[ "${1:-}" == "assemble" ]]; then
  assemble
  exit 0
fi

echo "Starting auto-build watcher (watching . and subdirs). Press Ctrl+C to stop."
while inotifywait -r -e modify,create,delete --exclude '(^|/)\.git(/|$)|~$|\.swp$' "${WATCH_DIRS[@]}" >/dev/null 2>&1; do
  echo "Change detected — running build: ${CMD[*]}"
  if "${CMD[@]}"; then
    echo "Build succeeded at $(date '+%Y-%m-%d %H:%M:%S')"
  else
    echo "Build failed at $(date '+%Y-%m-%d %H:%M:%S') — waiting for next change"
  fi
done