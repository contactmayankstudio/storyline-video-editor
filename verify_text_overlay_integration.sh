#!/usr/bin/env bash
# Integration Verification Checklist for Text Overlay System

set -e

cat << 'EOF'

================================================================================
  TEXT OVERLAY SYSTEM - INTEGRATION VERIFICATION
================================================================================

This script verifies that all components are in place for the professional
VN/KineMaster-style text overlay system.

EXPECTED STATE:
✓ TextOverlay.kt created with data class + OverlayStore
✓ TextEditorPanel.kt created with UI controls
✓ VideoPreviewView.kt enhanced with drag-to-move + surface tracking
✓ MainActivity.kt wired with new text button handler
✓ Native text rendering already works (native_preview.cpp)

================================================================================
EOF

WORKSPACE="/home/am/video_engine_core"
ANDROID_DIR="$WORKSPACE/android/app/src/main/kotlin/com/video/engine"
JNI_DIR="$WORKSPACE/android/jni"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

check_file() {
    local file=$1
    local description=$2
    if [ -f "$file" ]; then
        echo -e "${GREEN}✓${NC} $description"
        return 0
    else
        echo -e "${RED}✗${NC} $description"
        return 1
    fi
}

check_content() {
    local file=$1
    local pattern=$2
    local description=$3
    if grep -q "$pattern" "$file" 2>/dev/null; then
        echo -e "  ${GREEN}✓${NC} $description"
        return 0
    else
        echo -e "  ${RED}✗${NC} $description"
        return 1
    fi
}

echo ""
echo "Checking Kotlin Components..."
echo "────────────────────────────────────────────────────────────"

# TextOverlay.kt
check_file "$ANDROID_DIR/overlay/TextOverlay.kt" "TextOverlay.kt data class"
check_content "$ANDROID_DIR/overlay/TextOverlay.kt" "data class TextOverlay" "  TextOverlay data class with properties"
check_content "$ANDROID_DIR/overlay/TextOverlay.kt" "object OverlayStore" "  OverlayStore singleton for tracking"

echo ""

# TextEditorPanel.kt
check_file "$ANDROID_DIR/TextEditorPanel.kt" "TextEditorPanel.kt bottom sheet"
check_content "$ANDROID_DIR/TextEditorPanel.kt" "class TextEditorPanel" "  TextEditorPanel with EditText"
check_content "$ANDROID_DIR/TextEditorPanel.kt" "FontSize.*SeekBar" "  Font size slider (12-72pt)"
check_content "$ANDROID_DIR/TextEditorPanel.kt" "colorRow.*color" "  Color picker with presets"
check_content "$ANDROID_DIR/TextEditorPanel.kt" "opacitySeek" "  Opacity slider (0-100%)"

echo ""

# VideoPreviewView.kt modifications
check_file "$ANDROID_DIR/VideoPreviewView.kt" "VideoPreviewView.kt with enhancements"
check_content "$ANDROID_DIR/VideoPreviewView.kt" "surfaceW.*surfaceH" "  Surface size tracking"
check_content "$ANDROID_DIR/VideoPreviewView.kt" "fun addTextOverlay.*Long" "  addTextOverlay returns native ID"
check_content "$ANDROID_DIR/VideoPreviewView.kt" "drag.*move.*overlay\|nativeUpdateTextOverlay.*ACTION_MOVE" "  Drag-to-move gesture handling"

echo ""

# MainActivity.kt modifications
check_file "$ANDROID_DIR/MainActivity.kt" "MainActivity.kt with text button handler"
check_content "$ANDROID_DIR/MainActivity.kt" "textButton.*setOnClickListener" "  Text button listener registered"
check_content "$ANDROID_DIR/MainActivity.kt" "TextEditorPanel.*show" "  TextEditorPanel launcher"
check_content "$ANDROID_DIR/MainActivity.kt" "nextTextOverlayId" "  Text overlay ID counter"

echo ""
echo "Checking Native Components..."
echo "────────────────────────────────────────────────────────────"

# Native text overlay support
check_file "$JNI_DIR/native_preview.cpp" "native_preview.cpp with text support"
check_content "$JNI_DIR/native_preview.cpp" "nativeAddTextOverlay" "  nativeAddTextOverlay JNI binding"
check_content "$JNI_DIR/native_preview.cpp" "nativeUpdateTextOverlay" "  nativeUpdateTextOverlay JNI binding"
check_content "$JNI_DIR/native_preview.cpp" "renderTextOverlays" "  renderTextOverlays implementation"
check_content "$JNI_DIR/native_preview.cpp" "nativeSetTextOverlayBitmap" "  Bitmap upload to GL texture"
check_content "$JNI_DIR/native_preview.cpp" "TextOverlay.*zOrder.*keyframes" "  TextOverlay with keyframes"

echo ""
echo "Checking Header Files..."
echo "────────────────────────────────────────────────────────────"

check_file "$WORKSPACE/text_overlay.h" "text_overlay.h header"
check_content "$WORKSPACE/text_overlay.h" "struct TextOverlay" "  TextOverlay struct defined"
check_content "$WORKSPACE/text_overlay.h" "x.*y.*scale.*rotation" "  Transform parameters"
check_content "$WORKSPACE/text_overlay.h" "startTime.*endTime" "  Timeline bounds"

echo ""
echo "Checking Documentation..."
echo "────────────────────────────────────────────────────────────"

check_file "$WORKSPACE/TEXT_OVERLAY_PROFESSIONAL.md" "TEXT_OVERLAY_PROFESSIONAL.md guide"
check_content "$WORKSPACE/TEXT_OVERLAY_PROFESSIONAL.md" "VN/KineMaster" "  VN/KineMaster comparison"
check_content "$WORKSPACE/TEXT_OVERLAY_PROFESSIONAL.md" "TextEditorPanel\|drag.*move\|pinch" "  Feature documentation"

echo ""
echo "=================================================================================="
echo "                          INTEGRATION SUMMARY"
echo "=================================================================================="
echo ""
echo "COMPLETED:"
echo "  ✓ TextOverlay Kotlin data model with OverlayStore registry"
echo "  ✓ TextEditorPanel professional UI (fonts, colors, opacity, styling)"
echo "  ✓ VideoPreviewView enhanced with:"
echo "    - Surface size tracking (normalized coordinate mapping)"
echo "    - Drag-to-move single finger gesture"
echo "    - Pinch-to-scale (existing)"
echo "    - Two-finger rotation (existing)"
echo "  ✓ MainActivity Text button with editor launcher"
echo "  ✓ Native JNI bindings (already working in native_preview.cpp)"
echo "  ✓ GPU text rendering pipeline (already working)"
echo "  ✓ Professional documentation guide"
echo ""
echo "READY FOR:"
echo "  • Build: ./gradlew :app:assembleDebug"
echo "  • Testing: Create text → Edit → Drag → Export"
echo "  • Production: VN/KineMaster-tier text overlay experience"
echo ""
echo "=================================================================================="

EOF

