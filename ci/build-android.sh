#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="${ROOT_DIR}/build/ci"
LOG_FILE="${LOG_DIR}/android-build.log"
GRADLE_CMD="${GRADLE_CMD:-gradle}"

mkdir -p "${LOG_DIR}"
cd "${ROOT_DIR}"

echo "Root dir: ${ROOT_DIR}"
echo "Gradle command: ${GRADLE_CMD}"
echo "Log file: ${LOG_FILE}"

set +e
"${GRADLE_CMD}" --no-daemon --console=plain --stacktrace \
  :module:assembleCleanDebug \
  :module:assembleCleanRelease \
  2>&1 | tee "${LOG_FILE}"
status=${PIPESTATUS[0]}
set -e

if [[ "${status}" -eq 0 ]]; then
  if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
    {
      echo "## Android CI"
      echo
      echo "Build completed successfully."
      echo
      echo "Full build log is attached as artifact: \`android-build-log\`."
    } >> "${GITHUB_STEP_SUMMARY}"
  fi
  exit 0
fi

echo
echo "Gradle build failed with exit code ${status}"
echo "Last 200 lines from ${LOG_FILE}:"
tail -n 200 "${LOG_FILE}" || true

annotation_message="$(tail -n 25 "${LOG_FILE}" | tr '\n' ' ' | tr '\r' ' ' | sed 's/  */ /g' | cut -c1-600)"
echo "::error title=Gradle build failed::${annotation_message}"

if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  {
    echo "## Android CI Failed"
    echo
    echo "Exit code: \`${status}\`"
    echo
    echo "Last 120 log lines:"
    echo
    echo '```text'
    tail -n 120 "${LOG_FILE}" || true
    echo '```'
    echo
    echo "Full build log is attached as artifact: \`android-build-log\`."
  } >> "${GITHUB_STEP_SUMMARY}"
fi

exit "${status}"
