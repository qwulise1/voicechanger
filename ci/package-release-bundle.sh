#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
CI_DIR="${ROOT_DIR}/build/ci"
STAGE_DIR="${CI_DIR}/release-bundle"
VERSION_NAME="$(awk -F'"' '/versionName =/ { print $2; exit }' "${ROOT_DIR}/module/build.gradle.kts")"
BUNDLE_NAME="voicechanger-${VERSION_NAME}-release-bundle"
BUNDLE_DIR="${STAGE_DIR}/${BUNDLE_NAME}"
ZIP_PATH="${CI_DIR}/${BUNDLE_NAME}.zip"

oplus_apk="$(find "${ROOT_DIR}/module/build/outputs/apk/oplus/release" -maxdepth 1 -name '*.apk' | head -n 1)"
clean_apk="$(find "${ROOT_DIR}/module/build/outputs/apk/clean/release" -maxdepth 1 -name '*.apk' | head -n 1)"

if [[ -z "${oplus_apk}" || -z "${clean_apk}" ]]; then
  echo "Release APKs were not found. Build oplusRelease and cleanRelease before packaging." >&2
  exit 1
fi

rm -rf "${STAGE_DIR}"
mkdir -p "${BUNDLE_DIR}"

cp "${oplus_apk}" "${BUNDLE_DIR}/Voicechanger-oplus-${VERSION_NAME}-release.apk"
cp "${clean_apk}" "${BUNDLE_DIR}/Voicechanger-clean-${VERSION_NAME}-release.apk"
cp "${ROOT_DIR}/README.md" "${BUNDLE_DIR}/README.md"
cp "${ROOT_DIR}/docs/INSTALL.md" "${BUNDLE_DIR}/INSTALL.md"

cat > "${BUNDLE_DIR}/CONTENTS.txt" <<EOF
Voicechanger ${VERSION_NAME} release bundle

Files:
- Voicechanger-oplus-${VERSION_NAME}-release.apk
- Voicechanger-clean-${VERSION_NAME}-release.apk
- README.md
- INSTALL.md
EOF

rm -f "${ZIP_PATH}"
(
  cd "${STAGE_DIR}"
  zip -qr "${ZIP_PATH}" "${BUNDLE_NAME}"
)

echo "Created release bundle: ${ZIP_PATH}"
