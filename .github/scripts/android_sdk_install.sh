#!/usr/bin/env bash
# Install the emulator and one system image ahead of reactivecircus/android-emulator-runner, retrying each download.
#
# The action runs `sdkmanager --install emulator` and `--install 'system-images;…'` itself, unconditionally and with
# no retry, so a new emulator build Google has just published, or one dl.google.com hiccup, fails the journeys
# before any of ours run (2026-10-01: `An error occurred while preparing SDK package Android Emulator`). Run here
# first, the action's own installs find every package current and download nothing.
#
# Usage: android_sdk_install.sh <system-image-package>   (e.g. 'system-images;android-36;google_apis;x86_64')
# Prints the installed image's fingerprint as `fingerprint=<hex>` to $GITHUB_OUTPUT, the cache key's last part.
set -euo pipefail

image="${1:?usage: android_sdk_install.sh <system-image-package>}"
sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

yes | "$sdkmanager" --licenses >/dev/null || true

for attempt in 1 2 3 4 5; do
  if "$sdkmanager" --install emulator "$image" --channel=0 >/dev/null; then
    break
  fi
  [ "$attempt" -eq 5 ] && { echo "sdkmanager failed 5 times" >&2; exit 1; }
  echo "sdkmanager failed (attempt $attempt); retrying in $((attempt * 15)) s" >&2
  sleep $((attempt * 15))
done

# The image's package.xml carries its revision, so its hash changes exactly when sdkmanager installed a new one.
dir="$ANDROID_HOME/$(echo "$image" | tr ';' '/')"
fingerprint=$(sha256sum "$dir/package.xml" | cut -c1-16)
echo "installed $image ($fingerprint) and emulator $(grep -oP 'Pkg.Revision=\K.*' "$ANDROID_HOME/emulator/source.properties")"
echo "fingerprint=$fingerprint" >>"${GITHUB_OUTPUT:-/dev/null}"
