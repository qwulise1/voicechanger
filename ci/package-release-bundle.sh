#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
CI_DIR="${ROOT_DIR}/build/ci"
STAGE_DIR="${CI_DIR}/release-bundle"
VERSION_NAME="$(awk -F'"' '/versionName =/ { print $2; exit }' "${ROOT_DIR}/module/build.gradle.kts")"
BUNDLE_NAME="qwulivoice-${VERSION_NAME}-release-bundle"
BUNDLE_DIR="${STAGE_DIR}/${BUNDLE_NAME}"
ZIP_PATH="${CI_DIR}/${BUNDLE_NAME}.zip"

clean_apk="$(find "${ROOT_DIR}/module/build/outputs/apk/clean/release" -maxdepth 1 -name '*.apk' | head -n 1)"
root_audio_zip="${CI_DIR}/qwulivoice-root-audio-module.zip"

if [[ -z "${clean_apk}" ]]; then
  echo "Clean release APK was not found. Build cleanRelease before packaging." >&2
  exit 1
fi

rm -rf "${STAGE_DIR}"
mkdir -p "${BUNDLE_DIR}"

cp "${clean_apk}" "${BUNDLE_DIR}/qwulivoice-beta-${VERSION_NAME}-release.apk"
if [[ -f "${root_audio_zip}" ]]; then
  cp "${root_audio_zip}" "${BUNDLE_DIR}/qwulivoice-root-audio-module.zip"
fi
cp "${ROOT_DIR}/README.md" "${BUNDLE_DIR}/README.md"
cp "${ROOT_DIR}/docs/INSTALL.md" "${BUNDLE_DIR}/INSTALL.md"
cp "${ROOT_DIR}/docs/ROOT_AUDIO_MODULE.md" "${BUNDLE_DIR}/ROOT_AUDIO_MODULE.md"

cat > "${BUNDLE_DIR}/CONTENTS.txt" <<EOF
qwulivoice ${VERSION_NAME} release bundle

Files:
- qwulivoice-beta-${VERSION_NAME}-release.apk
- qwulivoice-root-audio-module.zip
- README.md
- INSTALL.md
- ROOT_AUDIO_MODULE.md
EOF

rm -f "${ZIP_PATH}"
(
  cd "${STAGE_DIR}"
  zip -qr "${ZIP_PATH}" "${BUNDLE_NAME}"
)

echo "Created release bundle: ${ZIP_PATH}"
