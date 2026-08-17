# Google Cloud Automation

Storyline now supports Android build and Robo testing in three paths:

- GitHub Actions local/self-hosted path
- Direct Google Cloud Build APK/AAB path
- Google Cloud Build + Firebase Test Lab path

## Required repo config

Set these GitHub repository variables for the GitHub-driven path:

- `GCP_AUTOMATION_PROJECT_ID`
- `GCP_AUTOMATION_RESULTS_BUCKET` (optional)
- `GCP_AUTOMATION_MACHINE_TYPE` (optional, defaults to `E2_HIGHCPU_8`)

Set these GitHub repository secrets for the GitHub-driven path:

- `GCP_AUTOMATION_SERVICE_ACCOUNT_JSON`
- `ANDROID_GOOGLE_SERVICES_JSON`
- `ANDROID_DEBUG_KEYSTORE_B64`

## Required Google Cloud setup

The target project must have:

- Billing attached to the project
- `cloudbuild.googleapis.com`
- `secretmanager.googleapis.com` for direct `gcloud builds submit` use
- `firebase.googleapis.com`
- `testing.googleapis.com`
- `toolresults.googleapis.com`

The GitHub workflow restores `google-services.json` and the stable debug keystore into the submitted source tree before calling Cloud Build. No extra Secret Manager setup is required for this path.

Direct Cloud Build submission from this repo uses `.gcloudignore` to avoid uploading local media, build outputs, and local keystores. That means local secrets should be provided through Secret Manager instead of relying on files in your working tree.

## Secret Manager setup for direct Cloud Build

Create the secrets once:

```bash
cd /home/am/storyline

gcloud secrets create storyline-android-google-services \
  --data-file=android/app/google-services.json

gcloud secrets create storyline-android-debug-keystore \
  --data-file=android/debug.keystore.stable

gcloud secrets create storyline-android-release-keystore \
  --data-file="$HOME/.keystores/video_engine.keystore"

printf '%s' "$KEYSTORE_PASSWORD" | gcloud secrets create \
  storyline-android-release-keystore-password --data-file=-

printf '%s' "$KEY_ALIAS" | gcloud secrets create \
  storyline-android-release-key-alias --data-file=-

printf '%s' "$KEY_PASSWORD" | gcloud secrets create \
  storyline-android-release-key-password --data-file=-
```

If a secret already exists, add a new version instead:

```bash
printf '%s' "$KEY_PASSWORD" | gcloud secrets versions add \
  storyline-android-release-key-password --data-file=-
```

## Direct Cloud Build APK/AAB path

Config file:

- `cloudbuild/android-build.yaml`

Helper script:

- `scripts/cloud-build-android.sh`

Typical commands:

```bash
cd /home/am/storyline

scripts/cloud-build-android.sh \
  --task assemblePlayDebug \
  --google-services-secret storyline-android-google-services \
  --debug-keystore-secret storyline-android-debug-keystore
```

```bash
cd /home/am/storyline

scripts/cloud-build-android.sh \
  --task assemblePlayRelease \
  --google-services-secret storyline-android-google-services \
  --release-keystore-secret storyline-android-release-keystore \
  --release-keystore-password-secret storyline-android-release-keystore-password \
  --release-key-alias-secret storyline-android-release-key-alias \
  --release-key-password-secret storyline-android-release-key-password \
  --artifacts-bucket gs://YOUR_BUCKET/storyline
```

```bash
cd /home/am/storyline

scripts/cloud-build-android.sh \
  --task bundlePlayRelease \
  --google-services-secret storyline-android-google-services \
  --release-keystore-secret storyline-android-release-keystore \
  --release-keystore-password-secret storyline-android-release-keystore-password \
  --release-key-alias-secret storyline-android-release-key-alias \
  --release-key-password-secret storyline-android-release-key-password \
  --artifacts-bucket gs://YOUR_BUCKET/storyline
```

Output behavior:

- APK or AAB files are collected from `android/app/build/outputs`
- SHA-256 checksums are generated in `cloudbuild-artifacts/SHA256SUMS`
- If `--artifacts-bucket` is provided, outputs are uploaded to `gs://.../android-builds/$BUILD_ID/`

## How to run

From GitHub:

- Run workflow `Google Cloud Android CI`
- It submits Cloud Build, which builds `assembleDebug` and runs Firebase Test Lab Robo

Direct GCP path:

- Use the same `cloudbuild/android-robo.yaml` from Cloud Build triggers or manual `gcloud builds submit`
- Use `cloudbuild/android-build.yaml` or `scripts/cloud-build-android.sh` for plain APK/AAB builds without Test Lab

## Notes

- GitHub Actions remains the source-control and orchestration layer.
- Google Cloud becomes the secondary build/test execution path.
- Firebase Test Lab device model is auto-detected with fallback to `MediumPhone.arm`.
- `reflected-space-493612-b4` has already shown billing-related API activation failures. If Cloud Build or Secret Manager calls fail before the build starts, attach a billing account first.
