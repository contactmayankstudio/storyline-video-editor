#!/usr/bin/env bash
set -euo pipefail

OWNER="${GITHUB_OWNER:-sarojshahu12-max}"
REPO="${GITHUB_REPO:-storyline}"
BRANCH="${GITHUB_BRANCH:-main}"
WORKFLOW_FILE="${GITHUB_WORKFLOW_FILE:-main.yml}"
WORKFLOW_LABEL="${GITHUB_WORKFLOW_LABEL:-Android CI Build}"
ARTIFACT_NAME="${GITHUB_ARTIFACT_NAME:-storyline-debug-apk}"
APP_ID="${ANDROID_APP_ID:-com.storyline.app}"
LAUNCH_ACTIVITY="${ANDROID_LAUNCH_ACTIVITY:-com.video.engine.MainActivity}"
POLL_SECONDS="${POLL_SECONDS:-15}"
FOLLOW_LOGCAT=0
DISPATCH=0
TARGET_SHA="${TARGET_SHA:-}"

usage() {
    cat <<EOF
Usage: GITHUB_TOKEN=... $(basename "$0") [options]

Options:
  --dispatch          Trigger GitHub Actions workflow_dispatch before polling
  --follow-logcat     Keep streaming adb logcat after install + launch
  --sha <commit>      Wait for a specific head SHA instead of latest workflow run
  --branch <branch>   Workflow branch to watch or dispatch (default: ${BRANCH})
  --help              Show this help

Environment overrides:
  GITHUB_OWNER, GITHUB_REPO, GITHUB_BRANCH, GITHUB_WORKFLOW_FILE,
  GITHUB_WORKFLOW_LABEL, GITHUB_ARTIFACT_NAME, ANDROID_APP_ID,
  ANDROID_LAUNCH_ACTIVITY, POLL_SECONDS, TARGET_SHA

Examples:
  GITHUB_TOKEN=... $(basename "$0") --sha "$(git rev-parse origin/main)"
  GITHUB_TOKEN=... $(basename "$0") --dispatch --follow-logcat
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --dispatch)
            DISPATCH=1
            shift
            ;;
        --follow-logcat)
            FOLLOW_LOGCAT=1
            shift
            ;;
        --sha)
            TARGET_SHA="${2:-}"
            shift 2
            ;;
        --branch)
            BRANCH="${2:-}"
            shift 2
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        *)
            echo "Unknown option: $1" >&2
            usage >&2
            exit 1
            ;;
    esac
done

if [[ -z "${GITHUB_TOKEN:-}" ]]; then
    echo "GITHUB_TOKEN is required." >&2
    exit 1
fi

for cmd in adb curl unzip python3 mktemp; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
        echo "Missing required command: $cmd" >&2
        exit 1
    fi
done

API_ROOT="https://api.github.com"
AUTH_HEADER="Authorization: Bearer ${GITHUB_TOKEN}"
ACCEPT_HEADER="Accept: application/vnd.github+json"
START_AFTER_ISO=""

api_get() {
    local path="$1"
    curl -fsSL \
        -H "$AUTH_HEADER" \
        -H "$ACCEPT_HEADER" \
        -H "X-GitHub-Api-Version: 2022-11-28" \
        "${API_ROOT}${path}"
}

api_post() {
    local path="$1"
    local body="$2"
    curl -fsSL -X POST \
        -H "$AUTH_HEADER" \
        -H "$ACCEPT_HEADER" \
        -H "X-GitHub-Api-Version: 2022-11-28" \
        -H "Content-Type: application/json" \
        -d "$body" \
        "${API_ROOT}${path}"
}

pick_run() {
    local json_file="$1"
    JSON_FILE="$json_file" \
    TARGET_SHA_VALUE="$TARGET_SHA" \
    START_AFTER_VALUE="$START_AFTER_ISO" \
    python3 - <<'PY'
import json
import os
from datetime import datetime

json_file = os.environ["JSON_FILE"]
target_sha = os.environ.get("TARGET_SHA_VALUE", "").strip()
start_after = os.environ.get("START_AFTER_VALUE", "").strip()

def parse_github_time(value: str):
    if not value:
        return None
    return datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ")

start_after_dt = parse_github_time(start_after) if start_after else None

with open(json_file, "r", encoding="utf-8") as fh:
    payload = json.load(fh)

runs = payload.get("workflow_runs") or []
selected = None

for run in runs:
    created_at = parse_github_time(run.get("created_at", ""))
    if target_sha and run.get("head_sha") != target_sha:
        continue
    if start_after_dt and created_at and created_at < start_after_dt:
        continue
    selected = run
    break

if not selected:
    raise SystemExit(1)

print(selected["id"])
print(selected.get("status", ""))
print(selected.get("conclusion") or "")
print(selected.get("head_sha", ""))
print(selected.get("html_url", ""))
PY
}

pick_artifact_url() {
    local json_file="$1"
    JSON_FILE="$json_file" \
    ARTIFACT_NAME_VALUE="$ARTIFACT_NAME" \
    python3 - <<'PY'
import json
import os

json_file = os.environ["JSON_FILE"]
artifact_name = os.environ["ARTIFACT_NAME_VALUE"]

with open(json_file, "r", encoding="utf-8") as fh:
    payload = json.load(fh)

for artifact in payload.get("artifacts") or []:
    if artifact.get("name") == artifact_name and not artifact.get("expired", False):
        print(artifact["archive_download_url"])
        raise SystemExit(0)

raise SystemExit(1)
PY
}

if [[ "$DISPATCH" -eq 1 ]]; then
    START_AFTER_ISO="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "Triggering ${WORKFLOW_LABEL} on ${OWNER}/${REPO}@${BRANCH}..."
    api_post "/repos/${OWNER}/${REPO}/actions/workflows/${WORKFLOW_FILE}/dispatches" "{\"ref\":\"${BRANCH}\"}" >/dev/null
    echo "Workflow dispatch accepted."
fi

tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

run_id=""
run_status=""
run_conclusion=""
run_sha=""
run_url=""

echo "Polling GitHub Actions for ${WORKFLOW_LABEL}..."
while true; do
    runs_json="${tmpdir}/runs.json"
    api_get "/repos/${OWNER}/${REPO}/actions/workflows/${WORKFLOW_FILE}/runs?branch=${BRANCH}&per_page=15" >"$runs_json"

    if mapfile -t run_fields < <(pick_run "$runs_json"); then
        run_id="${run_fields[0]}"
        run_status="${run_fields[1]}"
        run_conclusion="${run_fields[2]}"
        run_sha="${run_fields[3]}"
        run_url="${run_fields[4]}"
        echo "Run ${run_id} status=${run_status} conclusion=${run_conclusion:-pending} sha=${run_sha:0:8}"
        if [[ "$run_status" == "completed" ]]; then
            if [[ "$run_conclusion" != "success" ]]; then
                echo "Remote build failed: ${run_url}" >&2
                exit 1
            fi
            break
        fi
    else
        echo "No matching run found yet."
    fi

    sleep "$POLL_SECONDS"
done

artifacts_json="${tmpdir}/artifacts.json"
api_get "/repos/${OWNER}/${REPO}/actions/runs/${run_id}/artifacts" >"$artifacts_json"
artifact_url="$(pick_artifact_url "$artifacts_json")"
artifact_zip="${tmpdir}/artifact.zip"
artifact_dir="${tmpdir}/artifact"

echo "Downloading artifact ${ARTIFACT_NAME} from run ${run_id}..."
curl -fsSL -L \
    -H "$AUTH_HEADER" \
    -H "$ACCEPT_HEADER" \
    -H "X-GitHub-Api-Version: 2022-11-28" \
    "$artifact_url" \
    -o "$artifact_zip"

mkdir -p "$artifact_dir"
unzip -oq "$artifact_zip" -d "$artifact_dir"

apk_path="$(find "$artifact_dir" -type f -name '*.apk' | head -n 1)"
if [[ -z "$apk_path" ]]; then
    echo "No APK found inside downloaded artifact." >&2
    exit 1
fi

timestamp="$(date +%Y%m%d_%H%M%S)"
output_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/build/github-device/${timestamp}"
mkdir -p "$output_dir"
local_apk="${output_dir}/app-debug.apk"
log_file="${output_dir}/logcat.txt"
meta_file="${output_dir}/run.txt"

cp "$apk_path" "$local_apk"

cat >"$meta_file" <<EOF
repo=${OWNER}/${REPO}
workflow=${WORKFLOW_LABEL}
run_id=${run_id}
run_sha=${run_sha}
run_url=${run_url}
apk=${local_apk}
logcat=${log_file}
EOF

echo "Using APK: ${local_apk}"
adb start-server >/dev/null
adb wait-for-device
adb logcat -c || true
adb install -r "$local_apk"
adb shell am force-stop "$APP_ID" || true
adb shell am start -n "${APP_ID}/${LAUNCH_ACTIVITY}"
sleep 4
adb logcat -d -v time >"$log_file"

echo "Installed and launched ${APP_ID}."
echo "Saved initial logcat to ${log_file}"
echo "Run metadata saved to ${meta_file}"

if [[ "$FOLLOW_LOGCAT" -eq 1 ]]; then
    echo "Following adb logcat. Press Ctrl+C to stop."
    adb logcat -v time | tee -a "$log_file"
fi
