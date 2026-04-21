# GOOGLE PLAY CONSOLE SUBMISSION CHECKLIST
**ClipSwift – Fast Video Editor v1.0.0**

Date Started: _______________  
Submitted: _______________  
Live Date: _______________

---

## **PART A: DEVELOPER ACCOUNT SETUP**

### Account Creation
- [ ] Google Play Console account created
- [ ] $25 USD registration fee paid
- [ ] All terms & policies accepted
- [ ] Payment method verified
- [ ] Developer account active

### App Creation
- [ ] New app created in Play Console
- [ ] App name: `ClipSwift – Fast Video Editor`
- [ ] Default language: English
- [ ] App type: **App** (not Game)
- [ ] Pricing: **Free**

---

## **PART B: APP CONTENT & COMPLIANCE**

### Basic Info
- [ ] **Namespace/Package ID**: com.video.engine
- [ ] **Application ID**: com.video.engine
- [ ] **Category**: Video Players & Editors
- [ ] **Target audience**: Everyone 3+ OR Teens 13+ (choose one)
- [ ] **Content rating**: PEGI 3 / ESRB E (auto-generated)

### Privacy Policy
- [ ] Privacy policy written/obtained
- [ ] Hosted on accessible URL: ___________________________
- [ ] URL added to Play Console
- [ ] Policy covers: local file handling, analytics, no cloud upload
- [ ] Policy is professional & complete (no placeholders)

### Data Safety Form
- [ ] **Data collected section**:
  - [x] Video files
  - [x] Photos/videos from device storage
  - [ ] Audio files
  - [ ] Contacts
  - [ ] Calendar events
  - [x] Installed apps (if applicable for library detection)

- [ ] **Data sharing**:
  - [ ] Google Analytics (if used)
  - [x] Firebase Crash Reporting (if enabled)
  - [ ] Other third-party services: _________________
  
- [ ] **Encryption**: In transit ✓ (HTTPS/TLS)
- [ ] **Deletion policy**: "Users can delete by uninstalling app"
- [ ] **Data security**: 
  - [ ] Encryption at rest reviewed
  - [ ] Access controls documented
  - [ ] Regular security updates planned

### Content Rating Questionnaire
- [ ] Questionnaire submitted
- [ ] Violence: **No**
- [ ] Profanity/offensive language: **No**
- [ ] Sexual content: **No**
- [ ] Ads/in-app purchase: **No** (if no ads)
- [ ] Gambling: **No**
- [ ] Mature themes: **No**
- [ ] Rating generated & published

### App Permissions Justified
- [ ] **CAMERA**: ✓ Needed for video recording
- [ ] **MICROPHONE**: ✓ Needed for audio recording
- [ ] **READ_EXTERNAL_STORAGE**: ✓ Needed to import videos/images
- [ ] **WRITE_EXTERNAL_STORAGE**: ✓ Needed to export videos
- [ ] **INTERNET**: ✓ Needed for Firebase (optional, can disable)
- [ ] All permissions justified in app listing

---

## **PART C: APP LISTING & GRAPHICS**

### Text Fields
- [ ] **App name** (≤64 chars): `ClipSwift – Fast Video Editor`
  - Current length: ___ / 64 chars

- [ ] **Short description** (≤80 chars): 
  ```
  GPU-powered video editor — no watermark, offline, quick edits for reels
  ```
  - Current length: ___ / 80 chars

- [ ] **Full description** (≤4000 chars):
  - [x] Introduction paragraph
  - [x] Easy & Fast section
  - [x] No Watermark benefit
  - [x] Works Offline benefit
  - [x] Beginner-Friendly UI section
  - [x] Perfect for Reels & Shorts section
  - [x] Pro-Level Tools section
  - [x] Rich Effects & Text section
  - [x] Audio & Music section
  - [x] High Quality Export section
  - [x] Privacy First section
  - [x] Call-to-action
  - Current length: ___ / 4000 chars
  - No placeholder text ✓
  - Professional tone ✓

### Graphics & Media

#### App Icon
- [ ] File: PNG (transparent background recommended)
- [ ] Size: 512 × 512 pixels
- [ ] Design:
  - [x] Recognizable at small sizes (≤48px)
  - [x] Clear play symbol / editing concept
  - [x] Color palette: Purple-to-orange gradient
  - [x] Follows Material Design guidelines
- [ ] Covers entire canvas
- [ ] No text overlay
- [ ] No margin/dead space

#### Feature Graphic (Optional but recommended)
- [ ] File: PNG or JPG
- [ ] Size: 1024 × 500 pixels
- [ ] Content:
  - [x] App icon visible
  - [x] Key messages: "Fast", "No Watermark", "Offline"
  - [x] Sample UI screenshot or mockup
  - [x] Professional branding
- [ ] Safe zone: Center 924×500 (text inside only)

#### Screenshots (5 required, 1440×2560 each)

**Screenshot 1: Home / Timeline**
- [ ] Image uploaded (1440×2560 PNG)
- [ ] Shows: Main timeline with multiple clips
- [ ] Highlights: Drag handles, trim UI, clip arrangement
- [ ] Caption: "Fast timeline — drag, split, and edit in seconds."
- [ ] Professional & uncluttered

**Screenshot 2: Video Preview + Controls**
- [ ] Image uploaded (1440×2560 PNG)
- [ ] Shows: Video preview window with playback controls
- [ ] Highlights: Play/pause button, scrubber, timeline preview
- [ ] Caption: "Real-time GPU preview — smooth playback while you edit."
- [ ] Playback clearly visible

**Screenshot 3: Effects Panel**
- [ ] Image uploaded (1440×2560 PNG)
- [ ] Shows: Effects, filters, transitions, or text overlay panel
- [ ] Highlights: Multiple effect options, color controls
- [ ] Caption: "Add filters & animated text — look great on Reels."
- [ ] Visual richness demonstrated

**Screenshot 4: Export Screen**
- [ ] Image uploaded (1440×2560 PNG)
- [ ] Shows: Export dialog with options
- [ ] Highlights: Resolution selector, aspect ratio, bitrate controls, progress bar
- [ ] Caption: "Export up to 4K — choose quality and go offline."
- [ ] Clear export workflow

**Screenshot 5: Final Result / Social Post**
- [ ] Image uploaded (1440×2560 PNG)
- [ ] Shows: Exported video on social mockup (Instagram Reels template)
- [ ] Highlights: Clean video, no watermark, ready-to-upload appearance
- [ ] Caption: "Share-ready reels — no watermark, instant upload."
- [ ] Shows real output quality

### Keywords / Search Tags (10–15)
- [ ] video editor
- [ ] video maker
- [ ] reels editor
- [ ] shorts editor
- [ ] edit videos
- [ ] no watermark
- [ ] offline editor
- [ ] trim video
- [ ] merge clips
- [ ] video effects
- [ ] quick edit
- [ ] social videos
- [ ] movie maker
- [ ] clip editor
- [ ] status video

### Contact Information
- [ ] **Support email**: dev@example.com
- [ ] **Support website**: https://yoursite.com (optional)
- [ ] **Privacy policy**: https://yoursite.com/privacy (required)
- [ ] Email responds within 24h ✓

---

## **PART D: RELEASE & AAB UPLOAD**

### AAB File Preparation
- [ ] Release keystore created: `~/.keystores/video_engine.keystore`
- [ ] Keystore password saved securely (password manager)
- [ ] Keystore backed up to external storage
- [ ] `build.gradle` configured with `signingConfigs.release`
- [ ] Build environment variables set:
  - [ ] KEYSTORE_PATH
  - [ ] KEYSTORE_PASSWORD
  - [ ] KEY_ALIAS
  - [ ] KEY_PASSWORD

### Build & Verification
- [ ] Release AAB built successfully: `./gradlew bundleRelease`
- [ ] AAB file exists: `app/build/outputs/bundle/release/app-release.aab`
- [ ] AAB file size: ~50–60 MB ✓
- [ ] AAB signature verified:
  ```bash
  jarsigner -verify -verbose app-release.aab | grep "Signature okay"
  ```
- [ ] Version code: 1
- [ ] Version name: 1.0.0

### Upload in Play Console
1. Navigate to **Releases** → **Internal testing**
2. Click **Create new release**
3. Upload AAB file ✓
4. Wait for validation (~1–2 min)
- [ ] AAB uploaded successfully
- [ ] No validation errors
- [ ] Version code & name auto-detected correctly
- [ ] Supported ABIs shown: armeabi-v7a, arm64-v8a
- [ ] Min SDK shown: 21 (Android 5.0)
- [ ] Target SDK shown: 34 (Android 14)

### Handle Upload Warnings
- [ ] ⚠️ "NDK native code detected" → Click **Confirm** (expected)
- [ ] ⚠️ "Some devices may not be compatible" → No action (GPU requirement)
- [ ] ❌ NO validation errors (if any, see troubleshooting)

---

## **PART E: RELEASE CONFIGURATION**

### Release Notes
- [ ] **Title**: `ClipSwift 1.0.0 – Initial Release`
- [ ] **Notes include**:
  - [x] What's new (list of features)
  - [x] Tested devices (Pixel, Samsung, Redmi)
  - [x] Known limitations (if any)
  - [x] Support contact (email)
- [ ] Professional language ✓
- [ ] No grammatical errors ✓
- [ ] 500–1000 chars recommended
- [ ] Release notes saved

### Track Selection
- [ ] **Track**: Internal testing (for first release)
- [ ] **Rollout percentage**: 100% (standard for internal)
- [ ] **Staged rollout**: No (for internal testing)

### Testers (Optional)
- [ ] Add tester emails (optional):
  - [ ] your.email@example.com
  - [ ] test.user@example.com
- [ ] Or leave empty (Play Console still processes release)
- [ ] Each tester will get an email invite with Play Store link

---

## **PART F: PRE-SUBMISSION REVIEW**

### Final Content Check
- [ ] ✓ App name, description, keywords → No placeholder text
- [ ] ✓ Screenshots → Real UI (not mockups or doctored images)
- [ ] ✓ Privacy policy → Accessible & complete
- [ ] ✓ Data safety form → Honest answers
- [ ] ✓ Content rating → Submitted & published
- [ ] ✓ Permissions → All justified in listing
- [ ] ✓ Graphics → Professional, on-brand

### Technical Check
- [ ] ✓ AAB file uploaded (size 50–60 MB)
- [ ] ✓ No validation errors
- [ ] ✓ Min SDK 21 (Android 5.0)
- [ ] ✓ Target SDK 34 (Android 14)
- [ ] ✓ Both ABIs (armeabi-v7a, arm64-v8a)
- [ ] ✓ Signature verified

### Compliance Check (Avoid Rejections)
- [ ] ✓ No misleading statements
- [ ] ✓ No false claims (e.g., "fastest on market")
- [ ] ✓ Permissions only for stated features
- [ ] ✓ No external links (except privacy policy & support)
- [ ] ✓ No watermarks/third-party logos in screenshots
- [ ] ✓ No profanity, hate speech, or inappropriate content
- [ ] ✓ No copyright infringement (background music, images)
- [ ] ✓ App tested on Android 5.0, 8.0, 12.0, 14.0

---

## **PART G: SUBMISSION**

### **FINAL REVIEW** (checklist before clicking submit)
- [ ] All sections complete (no "Coming Soon")
- [ ] All graphics uploaded & visible
- [ ] Release notes written
- [ ] AAB uploaded & verified
- [ ] No red validation errors
- [ ] Privacy policy URL working
- [ ] Support email valid
- [ ] Ready to submit ✅

### **SUBMIT FOR REVIEW**

1. Click **Review release** button
2. Verify all details one final time
3. Click **Start rollout to Internal Testing**
4. 📍 **Status**: Release now shows as **"Pending review"** or **"Active"**

**Submission timestamp**: __________  
**Expected review time**: 4–24 hours (usually instant for internal testing)

---

## **PART H: POST-SUBMISSION MONITORING**

### Day 1–3: Release Status
- [ ] Check **Releases** section status
- [ ] Expected: Status = "Active" (for internal testing)
- [ ] Testers receive email invite (if emails added)
- [ ] Test link accessible in Play Store

### Week 1: Beta Testing
- [ ] Install on test devices (Android 5.0, 8.0, 12.0, 14.0)
- [ ] [ ] Test video import (camera roll, storage)
- [ ] [ ] Test video editing (timeline, trim, effects)
- [ ] [ ] Test preview playback (smooth, no crashes)
- [ ] [ ] Test export (multiple resolutions)
- [ ] [ ] Check device storage usage
- [ ] [ ] Monitor for crashes in Play Console
- [ ] Monitor **Crashes & ANRs** section (should be 0)
- [ ] Monitor **Android Vitals** (performance metrics)

### User Feedback
- [ ] Monitor **Reviews** section (if enabled for testers)
- [ ] Respond to feedback within 24 hours
- [ ] Note any bugs for next version

### Move to Closed Testing (Week 2+, Optional)
- [ ] Fix any critical bugs
- [ ] Increment `versionCode = 2` if bugs fixed, OR reuse AAB
- [ ] Create new release
- [ ] Submit to **Closed Testing** track (not Internal)
- [ ] Add 5–50 external testers (beta users, friends)
- [ ] Run for 2–4 weeks

### Move to Production / Live (Week 4+)
- [ ] Decide if ready for public release
- [ ] AAB same OR `versionCode = 3` if bugs fixed
- [ ] Create new release
- [ ] Submit to **Production** track
- [ ] **Google Play Review** (4–24 hours)
- [ ] Once approved: **Status = "Live"** ✅
- [ ] App visible to all users in Play Store

---

## **PART I: IMPORTANT REMINDERS**

### ⚠️ DO NOT
- [ ] ❌ Commit keystore to GitHub
- [ ] ❌ Share keystore file via email/Slack
- [ ] ❌ Delete or overwrite keystore
- [ ] ❌ Use same keystore for multiple apps
- [ ] ❌ Post password in version control
- [ ] ❌ Upload debug APK/AAB

### 🔒 DO
- [ ] ✓ Store keystore at: `~/.keystores/video_engine.keystore`
- [ ] ✓ Backup keystore to encrypted external drive
- [ ] ✓ Save password in secure password manager
- [ ] ✓ Use environment variables for credentials (NOT in gradle file)
- [ ] ✓ Add keystore to `.gitignore`
- [ ] ✓ Test release APK locally before AAB upload
- [ ] ✓ Use release signing config in `build.gradle`

### 🚨 If Keystore is Lost
- Permanent consequence: **Cannot update the app on Play Store**
- Must create new app with different package ID (com.video.engine2)
- Existing users cannot get auto-updates
- This is irreversible

---

## **SUCCESS INDICATORS**

✅ **Release Successful When**:
1. Status shows "Pending review" or "Active"
2. Testers receive email invites (if added)
3. App appears in Play Store (searchable or via link)
4. No crashes reported in Play Console (within 24h)
5. Version code 1, version name 1.0.0 displayed correctly

❌ **Review Rejected If**:
- Misleading screenshots or description
- Privacy policy missing or dead link
- Permissions not justified
- Crashes on basic test devices
- Incomplete app listing
- Duplicate app warning

**If rejected**: Fix issue → increment `versionCode` → rebuild AAB → resubmit

---

## **USEFUL LINKS**

- **Google Play Console**: https://play.google.com/console
- **Privacy Policy Generator**: https://termly.io
- **Play Store Policies**: https://play.google.com/about/developer-content-policy/
- **Android Vitals**: https://play.google.com/console → Your app → Android vitals
- **Crashes & ANRs**: https://play.google.com/console → Your app → Crashes & ANRs

---

## **NOTES**

```
Add any custom notes, app-specific details, or reminders here:

_________________________________________________________________

_________________________________________________________________

_________________________________________________________________
```

---

**Last Updated**: February 7, 2026  
**Status**: Ready for submission  
**Contact**: dev@example.com
