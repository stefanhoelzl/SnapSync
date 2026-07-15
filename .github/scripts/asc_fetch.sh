#!/usr/bin/env bash
# Fetch the pinned `asc` App Store Connect CLI (the metadata tool) and verify its SHA-256 before use.
#
# `asc` is a THIRD-PARTY binary (github.com/rudrankriyam/App-Store-Connect-CLI), so it is pinned by
# tag AND checksum — the same discipline ssh-mac.yml uses for cloudflared. To bump: change ASC_VERSION
# and ASC_SHA256 together (the checksum is the `asc_<version>_linux_amd64` line from the release's
# asc_<version>_checksums.txt). A mismatch aborts before the caller can run any apply.
#
# Usage: asc_fetch.sh <dest-path>   (e.g. asc_fetch.sh "$RUNNER_TEMP/asc")
set -euo pipefail

ASC_VERSION="2.8.2"
ASC_SHA256="b6be35bf7d8694d312b933aa4873723d5ea15c97733309542b7a1c531431808b"  # asc_2.8.2_linux_amd64
ASC_REPO="rudrankriyam/App-Store-Connect-CLI"

dest="${1:?usage: asc_fetch.sh <dest-path>}"
url="https://github.com/${ASC_REPO}/releases/download/${ASC_VERSION}/asc_${ASC_VERSION}_linux_amd64"

curl -fsSL -o "$dest" "$url"
echo "${ASC_SHA256}  ${dest}" | sha256sum -c -
chmod +x "$dest"
"$dest" --version
