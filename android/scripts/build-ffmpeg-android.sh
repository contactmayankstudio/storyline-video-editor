#!/usr/bin/env bash
set -euo pipefail

# Cross-compile FFmpeg for Android (armeabi-v7a by default).
# Requires Android NDK installed and ANDROID_NDK env var pointing to it.
# Usage: ./build-ffmpeg-android.sh armeabi-v7a

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TP_DIR="$ROOT_DIR/third_party/ffmpeg"
NDK=${ANDROID_NDK:-${ANDROID_NDK_HOME:-$HOME/Android/ndk}}

if [ -d "$NDK" ] && [ ! -d "$NDK/toolchains/llvm/prebuilt/linux-x86_64" ]; then
  RESOLVED_NDK="$(
    for candidate in "$NDK"/*; do
      [ -d "$candidate" ] && printf '%s\n' "$candidate"
    done | sort -V | tail -n 1
  )"
  if [ -n "${RESOLVED_NDK:-}" ] && [ -d "$RESOLVED_NDK/toolchains/llvm/prebuilt/linux-x86_64" ]; then
    NDK="$RESOLVED_NDK"
  fi
fi

if [ -z "$NDK" ] || [ ! -d "$NDK" ] || [ ! -d "$NDK/toolchains/llvm/prebuilt/linux-x86_64" ]; then
  echo "ERROR: ANDROID_NDK not found. Set ANDROID_NDK or ANDROID_NDK_HOME to your NDK path."
  exit 1
fi

ABI=${1:-armeabi-v7a}
API=${ANDROID_API:-21}
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"

case "$ABI" in
  armeabi-v7a)
    FFMPEG_ARCH="arm"
    CPU="armv7-a"
    CROSS_PREFIX="$TOOLCHAIN/bin/armv7a-linux-androideabi${API}-"
    CC="$TOOLCHAIN/bin/armv7a-linux-androideabi${API}-clang"
    CXX="$TOOLCHAIN/bin/armv7a-linux-androideabi${API}-clang++"
    CFLAGS="-march=armv7-a -mfloat-abi=softfp -mfpu=neon"
    ;;
  arm64-v8a)
    FFMPEG_ARCH="aarch64"
    CPU="arm64-v8a"
    CROSS_PREFIX="$TOOLCHAIN/bin/aarch64-linux-android${API}-"
    CC="$TOOLCHAIN/bin/aarch64-linux-android${API}-clang"
    CXX="$TOOLCHAIN/bin/aarch64-linux-android${API}-clang++"
    CFLAGS=""
    ;;
  *)
    echo "Unsupported ABI: $ABI"
    exit 1
    ;;
  *)
    echo "Unsupported ABI: $ABI"
    exit 1
    ;;
esac

mkdir -p "$TP_DIR/$ABI"
pushd "$TP_DIR/$ABI"

FFMPEG_VERSION="6.0"
if [ ! -d "ffmpeg-$FFMPEG_VERSION" ]; then
  echo "Downloading FFmpeg $FFMPEG_VERSION..."
  curl -LO "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.bz2"
  tar xjf "ffmpeg-$FFMPEG_VERSION.tar.bz2"
fi

pushd "ffmpeg-$FFMPEG_VERSION"

export PATH="$TOOLCHAIN/bin:$PATH"
export AR="$TOOLCHAIN/bin/llvm-ar"
export AS="$TOOLCHAIN/bin/llvm-as"
export NM="$TOOLCHAIN/bin/llvm-nm"
export CC
export CXX
export LD="$CXX"
export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
export STRIP="llvm-strip"

ANDROID_SYSROOT="$TOOLCHAIN/sysroot"

./configure \
  --prefix="$TP_DIR/$ABI" \
  --enable-shared \
  --disable-static \
  --disable-doc \
  --disable-programs \
  --enable-cross-compile \
  --cross-prefix="$CROSS_PREFIX" \
  --arch=${FFMPEG_ARCH} \
  --target-os=android \
  --sysroot="$ANDROID_SYSROOT" \
  --enable-pic \
  --extra-cflags="$CFLAGS -fPIC" \
  --extra-ldflags="-L$TP_DIR/$ABI/lib -landroid" \
  --enable-small \
  --disable-vulkan \
  --disable-vaapi \
  --disable-vdpau \
  --enable-mediacodec \
  --enable-encoder=h264_mediacodec \
  --enable-encoder=hevc_mediacodec \
  --enable-decoder=h264_mediacodec \
  --enable-hwaccel=h264_mediacodec \
  --enable-jni \
  --enable-neon

make -j$(nproc) STRIP=true
make install STRIP=true

popd
popd

echo "FFmpeg for $ABI built and installed to $TP_DIR/$ABI"
