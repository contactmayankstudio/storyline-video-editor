#!/usr/bin/env bash
set -euo pipefail

if [ -z "${GITHUB_WORKSPACE:-}" ] || [ -z "${GITHUB_REPOSITORY:-}" ] || [ -z "${GITHUB_TOKEN:-}" ]; then
  echo "::error::GITHUB_WORKSPACE, GITHUB_REPOSITORY, and GITHUB_TOKEN are required."
  exit 1
fi

checkout_ref="${CI_CHECKOUT_REF:-${GITHUB_REF:-refs/heads/main}}"
checkout_depth="${CI_CHECKOUT_FETCH_DEPTH:-0}"
checkout_url="https://x-access-token:${GITHUB_TOKEN}@github.com/${GITHUB_REPOSITORY}.git"

find "${GITHUB_WORKSPACE}" -mindepth 1 -maxdepth 1 -exec rm -rf {} +
git init "${GITHUB_WORKSPACE}"
cd "${GITHUB_WORKSPACE}"
git config --global --add safe.directory "${GITHUB_WORKSPACE}"
git remote add origin "${checkout_url}"

if [ "${checkout_depth}" = "0" ]; then
  git -c gc.auto=0 fetch --force --tags origin "${checkout_ref}"
else
  git -c gc.auto=0 fetch --force --no-tags --depth="${checkout_depth}" origin "${checkout_ref}"
fi

git checkout --force FETCH_HEAD
git rev-parse HEAD
