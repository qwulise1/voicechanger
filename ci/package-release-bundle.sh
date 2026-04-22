#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
CI_DIR="${ROOT_DIR}/build/ci"
STAGE_DIR="${CI_DIR}/release-bundle"
VERSION_NAME="$(awk -F'"' '/versionName =/ { print $2; exit }' "${ROOT_DIR}/app/build.gradle.kts")"
BUNDLE_NAME="voicechanger-${VERSION_NAME}-release-bundle"
BUNDLE_DIR="${STAGE_DIR}/${BUNDLE_NAME}"
ZIP_PATH="${CI_DIR}/${BUNDLE_NAME}.zip"

companion_apk="$(find "${ROOT_DIR}/app/build/outputs/apk/release" -maxdepth 1 -name '*.apk' | head -n 1)"
module_apk="$(find "${ROOT_DIR}/module/build/outputs/apk/release" -maxdepth 1 -name '*.apk' | head -n 1)"

if [[ -z "${companion_apk}" || -z "${module_apk}" ]]; then
  echo "Release APKs were not found. Build release variants before packaging." >&2
  exit 1
fi

rm -rf "${STAGE_DIR}"
mkdir -p "${BUNDLE_DIR}"

cp "${companion_apk}" "${BUNDLE_DIR}/Voicechanger-companion-${VERSION_NAME}-release.apk"
cp "${module_apk}" "${BUNDLE_DIR}/Voicechanger-module-${VERSION_NAME}-release.apk"
cp "${ROOT_DIR}/README.md" "${BUNDLE_DIR}/README.md"
cp "${ROOT_DIR}/docs/INSTALL.md" "${BUNDLE_DIR}/INSTALL.md"

cat > "${BUNDLE_DIR}/CONTENTS.txt" <<EOF
Voicechanger ${VERSION_NAME} release bundle

Files:
- Voicechanger-companion-${VERSION_NAME}-release.apk
- Voicechanger-module-${VERSION_NAME}-release.apk
- README.md
- INSTALL.md
EOF

rm -f "${ZIP_PATH}"
(
  cd "${STAGE_DIR}"
  zip -qr "${ZIP_PATH}" "${BUNDLE_NAME}"
)

echo "Created release bundle: ${ZIP_PATH}"
