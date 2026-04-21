# PLAY STORE RELEASE PLAYBOOK
**Android Video Editor — First Release (v1.0.0)**

---

## **PHASE 1: PREPARE SIGNING KEYSTORE** (5 min)

### Step 1a: Create Keystore Directory
```bash
mkdir -p ~/.keystores
cd ~/.keystores
```

### Step 1b: Generate Signing Keystore
Run this command **once** and save the password:

```bash
keytool -genkey -v -keystore video_engine.keystore -keyalg RSA -keysize 2048 -validity 10000 -alias video_engine
```

**When prompted, enter**:
```
Keystore password: VideoEng#2026Release!
Key password: VideoEng#2026Release! (same)
Your names: Video Engine Releases
Organization: YourCompanyName
City: Any
State: Any
Country: IN
```

### Step 1c: Verify Keystore Created
```bash
ls -lh ~/.keystores/video_engine.keystore
```

**Expected output**: File size ~2–3 KB

### Step 1d: BACKUP KEYSTORE IMMEDIATELY
```bash
# Option 1: Copy to external drive or cloud
cp ~/.keystores/video_engine.keystore /path/to/backup/

# Option 2: Encrypted backup (MacOS/Linux)
gpg --symmetric ~/.keystores/video_engine.keystore  # Creates .gpg file
```

⚠️ **CRITICAL**: Store password in secure password manager (1Password, LastPass, Bitwarden)

---

## **PHASE 2: BUILD RELEASE AAB** (10–15 min)

### Step 2a: Set Environment Variables
```bash
export KEYSTORE_PATH="$HOME/.keystores/video_engine.keystore"
export KEYSTORE_PASSWORD="VideoEng#2026Release!"
export KEY_ALIAS="video_engine"
export KEY_PASSWORD="VideoEng#2026Release!"
```

### Step 2b: Navigate to Android Project
```bash
cd /home/am/video_engine_core/android
```

### Step 2c: Build Release AAB
```bash
./gradlew bundleRelease --no-daemon
```

**Watch for**:
- `> Task :app:bundleRelease` starting
- Build should take 5–15 min (depends on CPU)
- **Success**: `BUILD SUCCESSFUL in XXs`
- **Error**: See troubleshooting below

### Step 2d: Verify AAB File Exists
```bash
ls -lh app/build/outputs/bundle/release/app-release.aab
```

**Expected**: File size ~50–60 MB

### Step 2e: Verify AAB is Signed
```bash
jarsigner -verify -verbose app/build/outputs/bundle/release/app-release.aab | grep -E "CN=|Signature okay"
```

**Expected output**:
```
CN=Video Engine Releases
Signature okay
```

---

## **PHASE 3: GOOGLE PLAY CONSOLE SETUP** (30 min)

### Step 3a: Create Developer Account (if new)
- Go to [Google Play Console](https://play.google.com/console)
- Sign in with Google account
- Pay $25 USD registration fee
- Accept all terms

### Step 3b: Create New App
1. **Dashboard** → **Create app**
2. **App name**: `ClipSwift – Fast Video Editor`
3. **Default language**: English
4. **App type**: Select **App** (not Game)
5. **Free or paid**: **Free**
6. **Agree to policies**: ✓ Checked
7. Click **Create app**

### Step 3c: Fill App Details (in order)

#### 3c-1: App category & audience
- **Category**: Video Players & Editors
- **Audience**: Everyone 3+ (or Teens 13+)
- **Save**

#### 3c-2: Privacy Policy
1. Create/prepare privacy policy (use [Termly](https://termly.io) or [PrivacyPolicies.com](https://www.privacypolicies.com) — both free)
2. Upload to your website: `https://yoursite.com/privacy.html`
3. **Play Console** → **Privacy Policy** → Paste URL
4. **Save**

#### 3c-3: Data Safety
1. **Data Safety** section
2. **Data you collect**:
   - ✓ Video files
   - ✓ Photos/videos from device
3. **Data you share**: 
   - If using Firebase: ✓ Crash data (purpose: app quality)
   - Otherwise: ✗ All unchecked
4. **Encryption**: Yes
5. **Save**

#### 3c-4: Content Rating
1. **Content Rating** → **Questionnaire**
2. Answer truthfully (usually all "No" for video editor):
   - Violence: No
   - Profanity: No
   - Sexual: No
   - Ads/IAP: No (unless applicable)
   - Gambling: No
3. **Submit questionnaire**
4. **Publish rating** ✓

---

## **PHASE 4: APP LISTING** (20 min)

### Step 4a: Store Listing
1. **Store listing** section
2. Fill in fields:

| Field | Content | Max Length |
|-------|---------|-----------|
| **App name** | ClipSwift – Fast Video Editor | 64 chars |
| **Short desc** | GPU-powered video editor — no watermark, offline, quick edits for reels | 80 chars |
| **Full desc** | [See below] | 4000 chars |

### Step 4b: Full Description (Copy-Paste)
```
ClipSwift is a fast, offline video editor for beginners and creators. 
Edit, add effects, and export HD videos without a watermark.

⚡ Easy & Fast
GPU acceleration for smooth edits and fast export.

🎯 No Watermark
Final videos are clean — no app logo or watermark.

📵 Works Offline
Edit and export fully offline; no sign-in or internet required.

🎨 Beginner-Friendly UI
Simple timeline, one-tap trims, drag-and-drop clips.

📱 Perfect for Reels & Shorts
Presets for 9:16, 1:1, 16:9; ready for Instagram Reels and YouTube Shorts.

🎬 Pro Tools Made Simple
Multi-layer timeline, transitions, speed controls, effects, animated text.

🔐 Privacy First
All editing stays on your device; no cloud upload.

Download ClipSwift and make reels in minutes — no watermark, no internet.
```

### Step 4c: Screenshots (5 required)
Upload PNG images (1440×2560) with captions:

```
Screenshot 1: Home / Timeline
Caption: Fast timeline — drag, split, and edit in seconds.

Screenshot 2: Video Preview + Controls
Caption: Real-time GPU preview — smooth playback while you edit.

Screenshot 3: Effects Panel
Caption: Add filters & animated text — look great on Reels.

Screenshot 4: Export Screen
Caption: Export up to 4K — choose quality and go offline.

Screenshot 5: Final Video Result
Caption: Share-ready reels — no watermark, instant upload.
```

### Step 4d: Promotional Graphics
- **App icon**: 512×512 PNG
- **Feature graphic**: 1024×500 PNG (optional but recommended)

### Step 4e: Contact Info
- **Support email**: dev@example.com
- **Support website**: (optional)
- **Save**

---

## **PHASE 5: UPLOAD AAB & SUBMIT** (15 min)

### Step 5a: Navigate to Releases
1. **Releases** section (left sidebar)
2. Select **Internal testing** track
3. Click **Create new release**

### Step 5b: Upload AAB File
1. **Upload APK/AAB files** section → Click **Browse**
2. Select: `/home/am/video_engine_core/android/app/build/outputs/bundle/release/app-release.aab`
3. Wait for validation (1–2 min) ✓

**Play Console auto-detects**:
- Version code: 1
- Version name: 1.0.0
- ABIs: armeabi-v7a, arm64-v8a
- Min SDK: 21 (Android 5.0)
- Target SDK: 34 (Android 14)

### Step 5c: Handle Warnings
- ⚠️ "NDK native code detected" → Click **Confirm** (expected)
- ⚠️ "Some devices not compatible" → No action (GPU requirements)

### Step 5d: Add Release Notes
```
ClipSwift 1.0.0 – First Release

🎉 What's New:
✅ GPU-accelerated video editing
✅ Multi-layer timeline with drag-and-drop
✅ Real-time preview
✅ Effects, filters, transitions
✅ No watermarks
✅ Works fully offline
✅ Export up to 4K
✅ Instagram Reels & YouTube Shorts presets

Tested on: Pixel 6/7, Samsung Galaxy A13, Redmi Note 11
Min requirement: Android 5.0+ with GPU support
```

### Step 5e: Configure Rollout
- **Rollout percentage**: 100% (for internal testing)
- **Release track**: Internal testing

### Step 5f: Add Testers (Optional)
1. **Settings** → **Testers**
2. Enter email addresses:
   ```
   your.email@gmail.com
   test.person@outlook.com
   ```
3. They'll receive Play Store install link

### Step 5g: SUBMIT
1. **Review release** (verify all fields)
2. Click **Start rollout to Internal Testing**
3. **Status**: "Pending review" → "Active" (usually instant)

---

## **PHASE 6: POST-SUBMISSION** (Ongoing)

### Monitor & Respond
- **Play Console** → **User reviews** (respond within 24h)
- **Crashes & ANRs** section → Fix critical crashes
- **Android vitals** → Monitor performance

### Move to Closed Testing (1–2 weeks later)
1. Same AAB, new release
2. Submit to **Closed Testing** track
3. Add 5–50 external testers
4. Collect feedback

### Move to Production (after 2–4 weeks testing)
1. Same AAB (or increment version if bugs fixed)
2. Submit to **Production** track
3. **Google Play review** (4–24 hours)
4. **Status**: "Live"

---

## **TROUBLESHOOTING**

### Build Error: "Could not locate the build tools"
```bash
cd /home/am/video_engine_core/android
./gradlew --update-locks
./gradlew bundleRelease
```

### Build Error: "libvideo_engine.so not found"
```bash
# Force CMake rebuild
./gradlew :app:clean
./gradlew bundleRelease --no-daemon
```

### Keystore not found
```bash
# Verify path
ls -la "$HOME/.keystores/video_engine.keystore"

# Verify env vars
echo $KEYSTORE_PATH
echo $KEYSTORE_PASSWORD
```

### AAB Validation Fails
- Check min SDK (must be 21+)
- Check target SDK (must be 34+)
- Verify no unsigned code
- Re-run: `jarsigner -verify -verbose app-release.aab`

### Google Play Review Rejected
Common reasons:
- ❌ Misleading screenshots → Use real UI
- ❌ Privacy policy missing → Add URL
- ❌ Crashes on Android 5.0–12 → Test on those versions
- ❌ Permissions not justified → Document why camera/microphone/storage needed
- ❌ Incomplete app listing → Fill all fields, no "Coming Soon"

**Fix**: Increment `versionCode` in `build.gradle`, rebuild, resubmit.

---

## **FINAL CHECKLIST BEFORE "START ROLLOUT"**

- [ ] Keystore created and backed up
- [ ] AAB built successfully (`bundleRelease`)
- [ ] AAB file is signed (verified with `jarsigner`)
- [ ] App name ≤64 chars
- [ ] Short description ≤80 chars
- [ ] Full description is clear, no placeholder text
- [ ] 5 screenshots uploaded (1440×2560 PNG)
- [ ] App icon uploaded (512×512 PNG)
- [ ] Privacy policy URL added and reachable
- [ ] Data Safety form completed
- [ ] Content rating submitted
- [ ] AAB uploaded to Play Console (no validation errors)
- [ ] Release notes added
- [ ] Internal testing testers added (or keep empty)
- [ ] All warnings reviewed & confirmed

---

## **SUCCESS CRITERIA**

After "Start rollout to Internal Testing":
- ✅ Release status shows **"Active"** or **"Pending review"**
- ✅ Testers receive notification email (if emails added)
- ✅ App appears in Play Store internal testing link for testers
- ✅ Play Console shows versionCode 1, versionName 1.0.0

---

## **NEXT STEPS**

1. **Day 1–7**: Monitor internal testing for crashes
2. **Day 7–14**: Collect feedback from testers
3. **Day 14**: Move to Closed Testing (if stable)
4. **Day 28**: Submit to Production (live release)

---

**📧 Support**: dev@example.com**
**🔑 Keystore location**: ~/.keystores/video_engine.keystore  
**📦 AAB location**: android/app/build/outputs/bundle/release/app-release.aab  
**📱 Play Console**: https://play.google.com/console
