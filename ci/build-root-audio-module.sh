#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="${ROOT_DIR}/build/root-audio"
MODULE_SRC="${ROOT_DIR}/rootaudio/module"
MODULE_DIR="${BUILD_DIR}/module"
ZIP_PATH="${ROOT_DIR}/build/ci/qwulivoice-root-audio-module.zip"

ANDROID_PLATFORM="${ANDROID_PLATFORM:-android-29}"
ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"

if [[ -z "${ANDROID_NDK_HOME}" ]]; then
  if [[ -n "${ANDROID_HOME:-}" ]]; then
    ANDROID_NDK_HOME="$(find "${ANDROID_HOME}/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
  fi
fi

if [[ -z "${ANDROID_NDK_HOME}" || ! -f "${ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake" ]]; then
  echo "Android NDK was not found. Set ANDROID_NDK_HOME or ANDROID_NDK_ROOT." >&2
  exit 1
fi

rm -rf "${BUILD_DIR}"
mkdir -p "${MODULE_DIR}" "${ROOT_DIR}/build/ci"
cp -R "${MODULE_SRC}/." "${MODULE_DIR}/"

chmod 0755 "${MODULE_DIR}/customize.sh" "${MODULE_DIR}/service.sh" "${MODULE_DIR}/post-fs-data.sh"
if [[ -f "${MODULE_DIR}/.aml.sh" ]]; then
  chmod 0755 "${MODULE_DIR}/.aml.sh"
fi

build_abi() {
  local abi="$1"
  local out_subdir="$2"
  local cmake_dir="${BUILD_DIR}/cmake-${abi}"

  cmake -S "${ROOT_DIR}/rootaudio/src" -B "${cmake_dir}" \
    -DCMAKE_TOOLCHAIN_FILE="${ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="${abi}" \
    -DANDROID_PLATFORM="${ANDROID_PLATFORM}" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_MAKE_PROGRAM=make

  cmake --build "${cmake_dir}" --config Release --parallel 2

  mkdir -p "${MODULE_DIR}/system/vendor/${out_subdir}/soundfx"
  cp "${cmake_dir}/libqwv_preprocess.so" "${MODULE_DIR}/system/vendor/${out_subdir}/soundfx/libqwv_preprocess.so"
}

build_abi "arm64-v8a" "lib64"
build_abi "armeabi-v7a" "lib"

cat > "${MODULE_DIR}/README.txt" <<EOF
qwulivoice root audio layer

Install this ZIP as a KernelSU Next / Magisk module, reboot, keep the APK installed,
and change voice/soundpad settings from the qwulivoice app. The service syncs app
settings to /data/adb/qwulivoice and /vendor/etc/qwulivoice.properties for
audioserver-side processing. The module also ships an AML-compatible .aml.sh patch.
EOF

rm -f "${ZIP_PATH}"
(
  cd "${MODULE_DIR}"
  zip -qr "${ZIP_PATH}" .
)

echo "Created root audio module: ${ZIP_PATH}"
