#!/usr/bin/env bash
set -euo pipefail

# Downloads a specific glm release into android/third_party/glm
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TP_DIR="$ROOT_DIR/third_party"
GLM_DIR="$TP_DIR/glm"
GLM_VERSION="0.9.9.8"

echo "Fetching GLM ${GLM_VERSION} into ${GLM_DIR}..."
mkdir -p "$TP_DIR"
rm -rf "$GLM_DIR"

tmpdir=$(mktemp -d)
trap "rm -rf $tmpdir" EXIT

pushd "$tmpdir"
curl -L -o glm.zip "https://github.com/g-truc/glm/archive/refs/tags/${GLM_VERSION}.zip"
unzip glm.zip
mv "glm-${GLM_VERSION}" "$GLM_DIR"
popd

echo "GLM downloaded to ${GLM_DIR}. Include path: ${GLM_DIR}"