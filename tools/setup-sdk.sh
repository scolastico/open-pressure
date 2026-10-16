#!/usr/bin/env bash
set -euo pipefail
sdk_dir="${1:-$PWD/.android-sdk}"
mkdir -p "$sdk_dir"
if [[ ! -x "$sdk_dir/cmdline-tools/latest/bin/sdkmanager" ]]; then
  sdk_archive=$(mktemp /tmp/open-pressure-sdk-XXXXXX.zip)
  sdk_unpack=$(mktemp -d /tmp/open-pressure-sdk-XXXXXX)
  curl --fail --location --retry 2 --output "$sdk_archive" \
    https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  unzip -q "$sdk_archive" -d "$sdk_unpack"
  mkdir -p "$sdk_dir/cmdline-tools"
  mv "$sdk_unpack/cmdline-tools" "$sdk_dir/cmdline-tools/latest"
fi
# SDK licenses are shown before acceptance; this installs only the build toolchain.
set +o pipefail
yes | "$sdk_dir/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$sdk_dir" --licenses
license_status=${PIPESTATUS[1]}
set -o pipefail
if [[ "$license_status" -ne 0 ]]; then exit "$license_status"; fi
"$sdk_dir/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$sdk_dir" 'platforms;android-36' 'build-tools;35.0.0' 'platform-tools'
printf 'sdk.dir=%s\n' "$sdk_dir" > local.properties
