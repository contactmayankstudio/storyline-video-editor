# Firebase Test Lab Setup

This repository includes a manual GitHub Actions workflow at `.github/workflows/firebase-test-lab.yml`.

## What You Need To Create

1. A Firebase project linked to a Google Cloud project.
2. Firebase Test Lab enabled in that project.
3. A Google Cloud service account key JSON with permission to run Test Lab.
4. GitHub repository secrets and variables.

## GitHub Secrets

Add these in `GitHub > Repository > Settings > Secrets and variables > Actions`.

- `FIREBASE_TEST_LAB_SERVICE_ACCOUNT_JSON`
  Paste the full service account key JSON.
- `ANDROID_GOOGLE_SERVICES_JSON`
  Already used by the Android build.
- `ANDROID_DEBUG_KEYSTORE_B64`
  Optional but recommended for stable debug signatures.

## GitHub Variables

- `FIREBASE_PROJECT_ID`
  Your Google Cloud / Firebase project id.
- `FIREBASE_RESULTS_BUCKET`
  Optional. A GCS bucket for storing Test Lab results. If omitted, Test Lab uses its default results storage.

## Recommended Free-Tier Style Defaults

Use the manual workflow with:

- `device_model`: `MediumPhone.arm`
- `android_version`: `34`
- `locale`: `en`
- `orientation`: `portrait`
- `timeout`: `5m`

This uses a Robo test, so no custom instrumentation test APK is required.

## What The Workflow Does

1. Builds the debug APK.
2. Authenticates to Google Cloud using the service account secret.
3. Runs:

```bash
gcloud firebase test android run \
  --type robo \
  --app android/app/build/outputs/apk/debug/app-debug.apk \
  --device model=MediumPhone.arm,version=34,locale=en,orientation=portrait \
  --timeout 5m
```

4. Uploads the Firebase Test Lab CLI log as a GitHub artifact.

## Where To Run It

Open:

- `GitHub > Actions > Firebase Test Lab > Run workflow`

## Notes

- Robo tests are the easiest entry point because they work with the app APK alone.
- For richer automated assertions later, add real `androidTest` instrumentation tests and extend the workflow to upload a test APK too.
