# Google Cloud Automation

Storyline now supports Android build and Robo testing in two paths:

- GitHub Actions local/self-hosted path
- Google Cloud Build + Firebase Test Lab path

## Required repo config

Set these GitHub repository variables:

- `GCP_AUTOMATION_PROJECT_ID`
- `GCP_AUTOMATION_RESULTS_BUCKET` (optional)
- `GCP_AUTOMATION_MACHINE_TYPE` (optional, defaults to `E2_HIGHCPU_8`)

Set these GitHub repository secrets:

- `GCP_AUTOMATION_SERVICE_ACCOUNT_JSON`
- `ANDROID_GOOGLE_SERVICES_JSON`
- `ANDROID_DEBUG_KEYSTORE_B64`

## Required Google Cloud setup

The target project must have:

- `cloudbuild.googleapis.com`
- `firebase.googleapis.com`
- `testing.googleapis.com`
- `toolresults.googleapis.com`

The GitHub workflow restores `google-services.json` and the stable debug keystore into the submitted source tree before calling Cloud Build. No extra Secret Manager setup is required for this path.

## How to run

From GitHub:

- Run workflow `Google Cloud Android CI`
- It submits Cloud Build, which builds `assembleDebug` and runs Firebase Test Lab Robo

Direct GCP path:

- Use the same `cloudbuild/android-robo.yaml` from Cloud Build triggers or manual `gcloud builds submit`

## Notes

- GitHub Actions remains the source-control and orchestration layer.
- Google Cloud becomes the secondary build/test execution path.
- Firebase Test Lab device model is auto-detected with fallback to `MediumPhone.arm`.
