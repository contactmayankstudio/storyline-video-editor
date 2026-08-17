#!/bin/bash

# PLAY STORE RELEASE BUILD SCRIPT
# Usage: bash build_release.sh
# This script automates the keystore + AAB build process

set -e  # Exit on error

echo "=================================================="
echo "   CLIPSWIFT RELEASE BUILD AUTOMATION"
echo "=================================================="
echo ""

# Color codes
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Configuration
KEYSTORE_DIR="$HOME/.keystores"
KEYSTORE_FILE="$KEYSTORE_DIR/video_engine.keystore"
KEYSTORE_ALIAS="video_engine"
PROJECT_DIR="/home/am/storyline/android"

# ========== PHASE 1: CHECK/CREATE KEYSTORE ==========

echo -e "${YELLOW}[PHASE 1] Checking signing keystore...${NC}"
echo ""

if [ ! -f "$KEYSTORE_FILE" ]; then
    echo -e "${YELLOW}Keystore not found at: $KEYSTORE_FILE${NC}"
    echo ""
    echo "This script will now generate a signing keystore."
    echo "You will be prompted to enter a password and some details."
    echo ""
    echo -e "${YELLOW}⚠️  IMPORTANT:${NC}"
    echo "  - Remember your password! Save it in a password manager."
    echo "  - Example password: VideoEng#2026Release!"
    echo "  - This same password will be used for signing."
    echo ""
    read -p "Press Enter to continue and create keystore..."
    
    mkdir -p "$KEYSTORE_DIR"
    chmod 700 "$KEYSTORE_DIR"
    
    keytool -genkey -v \
        -keystore "$KEYSTORE_FILE" \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -alias "$KEYSTORE_ALIAS" \
        -storepass "videoengine2026" \
        -keypass "videoengine2026" \
        -dname "CN=Storyline Developer, OU=Mobile, O=Storyline, C=IN"
    
    chmod 600 "$KEYSTORE_FILE"
    echo ""
    echo -e "${GREEN}✓ Keystore created successfully${NC}"
    echo -e "${GREEN}  Location: $KEYSTORE_FILE${NC}"
    echo ""
else
    echo -e "${GREEN}✓ Keystore found${NC}"
    echo "  Location: $KEYSTORE_FILE"
    echo ""
fi

# ========== PHASE 2: GET CREDENTIALS ==========

echo -e "${YELLOW}[PHASE 2] Setting up credentials...${NC}"
echo ""

# Check if env vars are already set
if [ -z "$KEYSTORE_PASSWORD" ]; then
    read -sp "Enter keystore password: " KEYSTORE_PASSWORD
    echo ""
fi

if [ -z "$KEY_PASSWORD" ]; then
    read -sp "Enter key password (or press Enter for same as keystore): " KEY_PASSWORD
    echo ""
    if [ -z "$KEY_PASSWORD" ]; then
        KEY_PASSWORD="$KEYSTORE_PASSWORD"
    fi
fi

echo -e "${GREEN}✓ Credentials ready${NC}"
echo ""

# ========== PHASE 3: BUILD RELEASE AAB ==========

echo -e "${YELLOW}[PHASE 3] Building release AAB...${NC}"
echo ""

# Export env vars for gradle
export KEYSTORE_PATH="$KEYSTORE_FILE"
export KEYSTORE_PASSWORD="$KEYSTORE_PASSWORD"
export KEY_ALIAS="$KEYSTORE_ALIAS"
export KEY_PASSWORD="$KEY_PASSWORD"

cd "$PROJECT_DIR"

# Clean build (optional, comment out to skip)
echo "Cleaning build directory..."
./gradlew clean

# Build AAB
echo ""
echo "Building release AAB (this may take 5–15 minutes)..."
echo ""
./gradlew bundleRelease --no-daemon

echo ""
echo -e "${GREEN}✓ Build successful${NC}"
echo ""

# ========== PHASE 4: VERIFY AAB ==========

echo -e "${YELLOW}[PHASE 4] Verifying release AAB...${NC}"
echo ""

AAB_FILE="$PROJECT_DIR/app/build/outputs/bundle/release/app-release.aab"

if [ -f "$AAB_FILE" ]; then
    echo -e "${GREEN}✓ AAB file found${NC}"
    
    # Show file size
    FILE_SIZE=$(ls -lh "$AAB_FILE" | awk '{print $5}')
    echo "  Size: $FILE_SIZE"
    echo "  Path: $AAB_FILE"
    echo ""
    
    # Verify signature
    echo "Verifying digital signature..."
    VERIFY_OUTPUT=$(jarsigner -verify -verbose "$AAB_FILE" 2>&1 | grep -E "CN=|Signature okay" || true)
    
    if echo "$VERIFY_OUTPUT" | grep -q "Signature okay"; then
        echo -e "${GREEN}✓ Signature verified${NC}"
        echo "$VERIFY_OUTPUT"
        echo ""
    else
        echo -e "${RED}✗ Signature verification failed${NC}"
        echo "The AAB may not be properly signed. Check your password and try again."
        exit 1
    fi
else
    echo -e "${RED}✗ AAB file not found${NC}"
    echo "Expected location: $AAB_FILE"
    exit 1
fi

# ========== PHASE 5: FINAL SUMMARY ==========

echo ""
echo "=================================================="
echo -e "${GREEN}   RELEASE BUILD COMPLETE${NC}"
echo "=================================================="
echo ""
echo "✅ Signing keystore: $KEYSTORE_FILE"
echo "✅ Release AAB: $AAB_FILE"
echo "✅ File size: $FILE_SIZE"
echo "✅ Signature: Valid"
echo ""
echo "📱 Next steps:"
echo "   1. Open Google Play Console"
echo "   2. Create a new app (or go to existing app)"
echo "   3. Releases → Internal testing → Create new release"
echo "   4. Upload this AAB file"
echo "   5. Add release notes + screenshots"
echo "   6. Click 'Start rollout to Internal Testing'"
echo ""
echo "📖 For detailed instructions, see:"
echo "   PLAY_STORE_RELEASE_PLAYBOOK.md"
echo ""
echo "⚠️  IMPORTANT REMINDERS:"
echo "   • Keep the keystore file safe in: $KEYSTORE_FILE"
echo "   • Backup keystore to encrypted storage"
echo "   • Save password in secure password manager"
echo "   • Never commit keystore to GitHub"
echo ""
