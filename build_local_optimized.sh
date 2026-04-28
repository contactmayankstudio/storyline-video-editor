#!/usr/bin/env bash
# Optimized Build Script for low-RAM laptops (3GB - 4GB)
set -euo pipefail

echo "🚀 Starting memory-optimized build..."
echo "Freeing up some RAM first..."
sync && sleep 2

cd android

# Running with 4GB JVM limit but only 2 workers to prevent CPU overload
# Using --no-daemon to ensure memory is released after build
./gradlew assembleDebug \
    -Dorg.gradle.jvmargs="-Xmx4096m -XX:MaxMetaspaceSize=512m" \
    --no-daemon \
    --max-workers=2

echo "✅ Build Successful!"
cp app/build/outputs/apk/debug/app-debug.apk ../storyline-debug.apk
echo "📦 APK copied to: storyline/storyline-debug.apk"
