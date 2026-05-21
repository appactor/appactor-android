#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPORT_DIR="$ROOT_DIR/appactor-android/build/outputs/connected-smoke"
LOG_FILE="$REPORT_DIR/connected-interactive-smoke.log"

mkdir -p "$REPORT_DIR"

require_env() {
  local key="$1"
  if [[ -z "${!key:-}" ]]; then
    echo "Missing required environment variable: $key" >&2
    exit 1
  fi
}

detect_physical_device() {
  local devices=()
  while read -r serial state; do
    [[ -z "${serial:-}" || "$state" != "device" ]] && continue
    local is_emulator
    is_emulator="$(adb -s "$serial" shell getprop ro.boot.qemu | tr -d '\r')"
    if [[ "$is_emulator" != "1" ]]; then
      devices+=("$serial")
    fi
  done < <(adb devices | tail -n +2)

  if [[ "${#devices[@]}" -ne 1 ]]; then
    echo "Expected exactly 1 authorized physical device, found ${#devices[@]}." >&2
    adb devices >&2
    exit 1
  fi

  printf '%s\n' "${devices[0]}"
}

command -v adb >/dev/null 2>&1 || { echo "adb is required." >&2; exit 1; }
command -v bash >/dev/null 2>&1 || { echo "bash is required." >&2; exit 1; }

require_env "APPACTOR_LIVE_API_KEY"
require_env "APPACTOR_LIVE_SUBS_PRODUCT_ID"
require_env "APPACTOR_LIVE_INAPP_PRODUCT_ID"

DEVICE_SERIAL="$(detect_physical_device)"
export ANDROID_SERIAL="$DEVICE_SERIAL"
export APPACTOR_LIVE_RUN_INTERACTIVE_PURCHASE="true"
export APPACTOR_LIVE_RUN_RECOVERY_SMOKE="${APPACTOR_LIVE_RUN_RECOVERY_SMOKE:-true}"

echo "Running interactive connected smoke on device: $DEVICE_SERIAL"
echo "Report log: $LOG_FILE"

(
  cd "$ROOT_DIR"
  ./gradlew \
    :appactor-android:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.appactor.android.ConnectedInteractiveSmokeTests
) | tee "$LOG_FILE"

echo "Interactive connected smoke completed."
echo "Gradle output captured at: $LOG_FILE"
