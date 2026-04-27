#!/usr/bin/env bash
set -euo pipefail

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run this script with sudo."
  exit 1
fi

: "${GH_RUNNER_URL:?Set GH_RUNNER_URL, for example https://github.com/sarojshahu12-max/storyline}"
: "${GH_RUNNER_TOKEN:?Set GH_RUNNER_TOKEN from GitHub > Settings > Actions > Runners > New self-hosted runner}"

RUN_AS_USER="${RUN_AS_USER:-${SUDO_USER:-$(id -un)}}"
RUN_AS_HOME="$(getent passwd "${RUN_AS_USER}" | cut -d: -f6)"
RUNNER_NAME="${RUNNER_NAME:-oracle-free-$(hostname -s)}"
RUNNER_LABELS="${RUNNER_LABELS:-self-hosted,Linux,X64,storyline,oracle-free}"
RUNNER_ROOT="${RUNNER_ROOT:-/opt/actions-runner}"
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-/opt/android-sdk}"
ANDROID_CMDLINE_TOOLS_URL="${ANDROID_CMDLINE_TOOLS_URL:-https://dl.google.com/android/repository/commandlinetools-linux-14742923_latest.zip}"
ANDROID_PLATFORM="${ANDROID_PLATFORM:-android-34}"
ANDROID_BUILD_TOOLS="${ANDROID_BUILD_TOOLS:-34.0.0}"
ANDROID_NDK_VERSION="${ANDROID_NDK_VERSION:-26.3.11579264}"
ANDROID_CMAKE_VERSION="${ANDROID_CMAKE_VERSION:-3.22.1}"
SWAP_GB="${SWAP_GB:-8}"

if [[ "$(uname -s)" != "Linux" ]]; then
  echo "This bootstrap is for Linux VMs only."
  exit 1
fi

if [[ "$(uname -m)" != "x86_64" ]]; then
  echo "This bootstrap is written for x86_64 Linux runners."
  echo "Storyline Android CI currently expects the Linux x64 Android toolchain path."
  exit 1
fi

detect_os() {
  . /etc/os-release
  echo "${ID}"
}

install_packages() {
  local os_id
  os_id="$(detect_os)"

  case "${os_id}" in
    ubuntu|debian)
      export DEBIAN_FRONTEND=noninteractive
      apt-get update
      apt-get install -y \
        ca-certificates \
        curl \
        git \
        jq \
        openjdk-17-jdk \
        unzip \
        zip \
        tar \
        build-essential \
        cmake \
        ninja-build \
        lib32stdc++6 \
        libc6-i386 \
        lib32z1
      ;;
    ol|rhel|centos|fedora)
      dnf install -y \
        ca-certificates \
        curl \
        git \
        jq \
        java-17-openjdk-devel \
        unzip \
        zip \
        tar \
        gcc \
        gcc-c++ \
        make \
        cmake \
        ninja-build \
        glibc.i686 \
        libstdc++.i686 \
        zlib.i686
      ;;
    *)
      echo "Unsupported OS: ${os_id}"
      exit 1
      ;;
  esac
}

ensure_swap() {
  local swapfile="/swapfile-storyline"

  if swapon --show --noheadings | grep -q "${swapfile}"; then
    echo "Swap already active at ${swapfile}"
    return
  fi

  if [[ -f "${swapfile}" ]]; then
    chmod 600 "${swapfile}"
    mkswap "${swapfile}" >/dev/null
    swapon "${swapfile}"
  else
    fallocate -l "${SWAP_GB}G" "${swapfile}" || dd if=/dev/zero of="${swapfile}" bs=1M count="$((SWAP_GB * 1024))"
    chmod 600 "${swapfile}"
    mkswap "${swapfile}" >/dev/null
    swapon "${swapfile}"
  fi

  if ! grep -q "${swapfile}" /etc/fstab; then
    echo "${swapfile} none swap sw 0 0" >> /etc/fstab
  fi
}

install_android_sdk() {
  mkdir -p "${ANDROID_SDK_ROOT}/cmdline-tools"
  local tmp_zip
  tmp_zip="$(mktemp /tmp/android-cmdline-tools.XXXXXX.zip)"
  curl -fsSL "${ANDROID_CMDLINE_TOOLS_URL}" -o "${tmp_zip}"

  rm -rf "${ANDROID_SDK_ROOT}/cmdline-tools/latest"
  mkdir -p "${ANDROID_SDK_ROOT}/cmdline-tools/latest"
  unzip -q "${tmp_zip}" -d "${ANDROID_SDK_ROOT}/cmdline-tools/latest"
  rm -f "${tmp_zip}"

  if [[ -d "${ANDROID_SDK_ROOT}/cmdline-tools/latest/cmdline-tools" ]]; then
    mv "${ANDROID_SDK_ROOT}/cmdline-tools/latest/cmdline-tools/"* "${ANDROID_SDK_ROOT}/cmdline-tools/latest/"
    rmdir "${ANDROID_SDK_ROOT}/cmdline-tools/latest/cmdline-tools"
  fi

  chown -R "${RUN_AS_USER}:${RUN_AS_USER}" "${ANDROID_SDK_ROOT}"

  sudo -u "${RUN_AS_USER}" env \
    ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT}" \
    PATH="${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/platform-tools:${PATH}" \
    bash -lc "
      yes | sdkmanager --licenses >/dev/null
      sdkmanager \
        'platform-tools' \
        'platforms;${ANDROID_PLATFORM}' \
        'build-tools;${ANDROID_BUILD_TOOLS}' \
        'cmdline-tools;latest' \
        'cmake;${ANDROID_CMAKE_VERSION}' \
        'ndk;${ANDROID_NDK_VERSION}'
    "
}

install_runner() {
  local latest_version
  latest_version="$(curl -fsSL https://api.github.com/repos/actions/runner/releases/latest | jq -r '.tag_name' | sed 's/^v//')"
  local archive="actions-runner-linux-x64-${latest_version}.tar.gz"
  local url="https://github.com/actions/runner/releases/download/v${latest_version}/${archive}"

  mkdir -p "${RUNNER_ROOT}"
  chown -R "${RUN_AS_USER}:${RUN_AS_USER}" "${RUNNER_ROOT}"

  sudo -u "${RUN_AS_USER}" bash -lc "
    cd '${RUNNER_ROOT}'
    rm -f '${archive}'
    curl -fsSL '${url}' -o '${archive}'
    tar xzf '${archive}'
    rm -f '${archive}'
  "

  cat > "${RUNNER_ROOT}/.env" <<EOF
ANDROID_HOME=${ANDROID_SDK_ROOT}
ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT}
ANDROID_NDK_ROOT=${ANDROID_SDK_ROOT}/ndk/${ANDROID_NDK_VERSION}
PATH=${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/platform-tools:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
EOF
  chown "${RUN_AS_USER}:${RUN_AS_USER}" "${RUNNER_ROOT}/.env"

  sudo -u "${RUN_AS_USER}" bash -lc "
    cd '${RUNNER_ROOT}'
    ./config.sh \
      --url '${GH_RUNNER_URL}' \
      --token '${GH_RUNNER_TOKEN}' \
      --name '${RUNNER_NAME}' \
      --labels '${RUNNER_LABELS}' \
      --unattended \
      --replace
  "

  (cd "${RUNNER_ROOT}" && ./svc.sh install "${RUN_AS_USER}")
  (cd "${RUNNER_ROOT}" && ./svc.sh start)
}

write_gradle_defaults() {
  local gradle_dir="${RUN_AS_HOME}/.gradle"
  mkdir -p "${gradle_dir}"
  cat > "${gradle_dir}/gradle.properties" <<EOF
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
org.gradle.workers.max=1
org.gradle.parallel=false
android.builder.sdkDownload=true
EOF
  chown -R "${RUN_AS_USER}:${RUN_AS_USER}" "${gradle_dir}"
}

main() {
  install_packages
  ensure_swap
  install_android_sdk
  write_gradle_defaults
  install_runner

  cat <<EOF

Oracle self-hosted runner is ready.

Runner name:    ${RUNNER_NAME}
Runner labels:  ${RUNNER_LABELS}
Runner root:    ${RUNNER_ROOT}
Android SDK:    ${ANDROID_SDK_ROOT}

After the runner appears online in GitHub, set:
  gh variable set CI_RUNNER_LABELS --repo sarojshahu12-max/storyline --body '["self-hosted","Linux","X64","storyline","oracle-free"]'
EOF
}

main "$@"
