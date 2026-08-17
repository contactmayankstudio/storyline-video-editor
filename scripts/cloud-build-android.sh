#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONFIG_PATH="cloudbuild/android-build.yaml"
REGION="global"
BUILD_TASK="${BUILD_TASK:-assemblePlayDebug}"
MACHINE_TYPE="${MACHINE_TYPE:-E2_HIGHCPU_8}"
ARTIFACTS_BUCKET="${ARTIFACTS_BUCKET:-}"
ARTIFACTS_PREFIX="${ARTIFACTS_PREFIX:-android-builds}"
GOOGLE_SERVICES_SECRET="${GOOGLE_SERVICES_SECRET:-}"
DEBUG_KEYSTORE_SECRET="${DEBUG_KEYSTORE_SECRET:-}"
RELEASE_KEYSTORE_SECRET="${RELEASE_KEYSTORE_SECRET:-}"
RELEASE_KEYSTORE_PASSWORD_SECRET="${RELEASE_KEYSTORE_PASSWORD_SECRET:-}"
RELEASE_KEY_ALIAS_SECRET="${RELEASE_KEY_ALIAS_SECRET:-}"
RELEASE_KEY_PASSWORD_SECRET="${RELEASE_KEY_PASSWORD_SECRET:-}"

usage() {
  cat <<'EOF'
Usage:
  scripts/cloud-build-android.sh [options]

Options:
  --task TASK                                 Gradle task, e.g. assemblePlayDebug, assemblePlayRelease, bundlePlayRelease
  --region REGION                             Cloud Build region (default: global)
  --machine-type TYPE                         Cloud Build machine type (default: E2_HIGHCPU_8)
  --artifacts-bucket gs://bucket/path         Optional Cloud Storage destination for APK/AAB uploads
  --artifacts-prefix PREFIX                   Path segment under the bucket (default: android-builds)
  --google-services-secret SECRET             Secret Manager secret name for android/app/google-services.json
  --debug-keystore-secret SECRET              Secret Manager secret name for android debug keystore
  --release-keystore-secret SECRET            Secret Manager secret name for release keystore binary
  --release-keystore-password-secret SECRET   Secret Manager secret name for release keystore password
  --release-key-alias-secret SECRET           Secret Manager secret name for release key alias
  --release-key-password-secret SECRET        Secret Manager secret name for release key password
  --help                                      Show this message

Examples:
  scripts/cloud-build-android.sh \
    --task assemblePlayDebug \
    --google-services-secret storyline-android-google-services \
    --debug-keystore-secret storyline-android-debug-keystore

  scripts/cloud-build-android.sh \
    --task bundlePlayRelease \
    --google-services-secret storyline-android-google-services \
    --release-keystore-secret storyline-android-release-keystore \
    --release-keystore-password-secret storyline-android-release-keystore-password \
    --release-key-alias-secret storyline-android-release-key-alias \
    --release-key-password-secret storyline-android-release-key-password \
    --artifacts-bucket gs://my-build-artifacts/storyline
EOF
}

require_value() {
  local flag="$1"
  local value="$2"
  if [ -z "$value" ]; then
    echo "Missing value for ${flag}"
    exit 1
  fi
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --task)
      BUILD_TASK="${2:-}"
      require_value "$1" "$BUILD_TASK"
      shift 2
      ;;
    --region)
      REGION="${2:-}"
      require_value "$1" "$REGION"
      shift 2
      ;;
    --machine-type)
      MACHINE_TYPE="${2:-}"
      require_value "$1" "$MACHINE_TYPE"
      shift 2
      ;;
    --artifacts-bucket)
      ARTIFACTS_BUCKET="${2:-}"
      require_value "$1" "$ARTIFACTS_BUCKET"
      shift 2
      ;;
    --artifacts-prefix)
      ARTIFACTS_PREFIX="${2:-}"
      require_value "$1" "$ARTIFACTS_PREFIX"
      shift 2
      ;;
    --google-services-secret)
      GOOGLE_SERVICES_SECRET="${2:-}"
      require_value "$1" "$GOOGLE_SERVICES_SECRET"
      shift 2
      ;;
    --debug-keystore-secret)
      DEBUG_KEYSTORE_SECRET="${2:-}"
      require_value "$1" "$DEBUG_KEYSTORE_SECRET"
      shift 2
      ;;
    --release-keystore-secret)
      RELEASE_KEYSTORE_SECRET="${2:-}"
      require_value "$1" "$RELEASE_KEYSTORE_SECRET"
      shift 2
      ;;
    --release-keystore-password-secret)
      RELEASE_KEYSTORE_PASSWORD_SECRET="${2:-}"
      require_value "$1" "$RELEASE_KEYSTORE_PASSWORD_SECRET"
      shift 2
      ;;
    --release-key-alias-secret)
      RELEASE_KEY_ALIAS_SECRET="${2:-}"
      require_value "$1" "$RELEASE_KEY_ALIAS_SECRET"
      shift 2
      ;;
    --release-key-password-secret)
      RELEASE_KEY_PASSWORD_SECRET="${2:-}"
      require_value "$1" "$RELEASE_KEY_PASSWORD_SECRET"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1"
      usage
      exit 1
      ;;
  esac
done

if ! command -v gcloud >/dev/null 2>&1; then
  echo "gcloud is required but was not found in PATH."
  exit 1
fi

if [ -z "$GOOGLE_SERVICES_SECRET" ]; then
  echo "--google-services-secret is required for Cloud Build submits."
  exit 1
fi

if [[ "$BUILD_TASK" == *Debug* ]] && [ -z "$DEBUG_KEYSTORE_SECRET" ]; then
  echo "--debug-keystore-secret is required for debug builds submitted with this helper."
  exit 1
fi

if [[ "$BUILD_TASK" == *Release* ]]; then
  for pair in \
    "--release-keystore-secret:$RELEASE_KEYSTORE_SECRET" \
    "--release-keystore-password-secret:$RELEASE_KEYSTORE_PASSWORD_SECRET" \
    "--release-key-alias-secret:$RELEASE_KEY_ALIAS_SECRET" \
    "--release-key-password-secret:$RELEASE_KEY_PASSWORD_SECRET"; do
    flag="${pair%%:*}"
    value="${pair#*:}"
    if [ -z "$value" ]; then
      echo "${flag} is required for release builds."
      exit 1
    fi
  done
fi

substitutions=(
  "_BUILD_TASK=${BUILD_TASK}"
  "_MACHINE_TYPE=${MACHINE_TYPE}"
  "_ARTIFACTS_PREFIX=${ARTIFACTS_PREFIX}"
  "_GOOGLE_SERVICES_SECRET=${GOOGLE_SERVICES_SECRET}"
)

if [ -n "$ARTIFACTS_BUCKET" ]; then
  substitutions+=("_ARTIFACTS_BUCKET=${ARTIFACTS_BUCKET}")
fi
if [ -n "$DEBUG_KEYSTORE_SECRET" ]; then
  substitutions+=("_DEBUG_KEYSTORE_SECRET=${DEBUG_KEYSTORE_SECRET}")
fi
if [ -n "$RELEASE_KEYSTORE_SECRET" ]; then
  substitutions+=("_RELEASE_KEYSTORE_SECRET=${RELEASE_KEYSTORE_SECRET}")
fi
if [ -n "$RELEASE_KEYSTORE_PASSWORD_SECRET" ]; then
  substitutions+=("_RELEASE_KEYSTORE_PASSWORD_SECRET=${RELEASE_KEYSTORE_PASSWORD_SECRET}")
fi
if [ -n "$RELEASE_KEY_ALIAS_SECRET" ]; then
  substitutions+=("_RELEASE_KEY_ALIAS_SECRET=${RELEASE_KEY_ALIAS_SECRET}")
fi
if [ -n "$RELEASE_KEY_PASSWORD_SECRET" ]; then
  substitutions+=("_RELEASE_KEY_PASSWORD_SECRET=${RELEASE_KEY_PASSWORD_SECRET}")
fi

joined_substitutions="$(
  IFS=,
  echo "${substitutions[*]}"
)"

(
  cd "$ROOT_DIR"
  gcloud builds submit . \
    --config "$CONFIG_PATH" \
    --region "$REGION" \
    --substitutions "$joined_substitutions"
)
