#!/usr/bin/env bash
set -euo pipefail

ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/android-sdk}}"

echo "== Host =="
uname -a
echo

echo "== Java =="
java -version
echo

echo "== Android SDK root =="
echo "${ANDROID_SDK_ROOT}"
echo

echo "== SDK packages =="
"${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager" --list_installed || true
echo

echo "== Platform tools =="
ls -la "${ANDROID_SDK_ROOT}/platform-tools" || true
echo

echo "== NDK =="
ls -la "${ANDROID_SDK_ROOT}/ndk" || true
echo

echo "== Memory =="
free -h
echo

echo "== Swap =="
swapon --show || true
echo

echo "== Gradle =="
if [[ -f "android/gradlew" ]]; then
  (cd android && ./gradlew -version)
else
  echo "Run from the repository root to check Gradle."
fi
