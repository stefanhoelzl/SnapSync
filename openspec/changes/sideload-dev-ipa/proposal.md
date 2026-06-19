## Why

The `ios-build` job uploads a signed build to **TestFlight on every push (any ref)**, purely so each branch is installable on the physical SE2 before merge. That every-push delivery is what hits Apple's TestFlight upload limits. The per-branch-installability guarantee does not actually need TestFlight — it can be served by a **development-signed IPA published as a GitHub Actions artifact** and installed directly over the usbmuxd bridge at zero Apple-side cost. This change re-homes that guarantee onto a local artifact and narrows TestFlight to `main`.

## What Changes

- **Add a development-IPA artifact on every push.** From the same gate archive, additionally export a `method=development` IPA and upload it as a GitHub Actions artifact (`retention-days: 1`, non-gating via `continue-on-error`). This becomes the path that makes any branch installable on a registered device before merge.
- **Narrow TestFlight to `main`.** **BREAKING** (delivery behavior): the existing app-store export + `upload-testflight-build` steps run only on `refs/heads/main` instead of every ref. TestFlight becomes a release trail, not the per-branch install mechanism.
- **Single compile preserved.** The new development export reuses the existing signed archive (the merge gate); the device app still compiles exactly once per push.
- **Reuse the imported Development cert.** The development export signs with the already-imported Apple Development cert via automatic signing + `-allowProvisioningUpdates` + the ASC Admin key; the generated development profile includes registered device UDIDs. No new cert is minted.
- **Correct stale spec text.** `ios-testflight-delivery` still claims signing is "fully cloud-managed, no stored certificate"; the workflow already imports two persistent certs (Distribution + Development). Update the spec to match reality while it is being edited.
- **Operator runbook (docs only, not spec).** Document the local install loop — register the SE2 UDID once at developer.apple.com, enable Developer Mode via `uvx pymobiledevice3 amfi enable-developer-mode`, then `gh run download` the artifact and `uvx pymobiledevice3 apps install`. These operator steps are not CI behavior and carry no `SHALL` requirements.

## Capabilities

### New Capabilities
- `ios-sideload-delivery`: On every push, export a development-signed IPA from the gate archive and publish it as a short-retention, non-gating GitHub Actions artifact, signed to install on registered devices. Carries the migrated "installable on a physical device before merge" guarantee.

### Modified Capabilities
- `ios-testflight-delivery`: TestFlight upload narrows from every ref to `refs/heads/main` only; the "installable before merge" rationale moves to `ios-sideload-delivery`; signing description corrected from "cloud-managed, no stored cert" to the two imported persistent certs the workflow actually uses.
- `ios-ci`: The "every ref signs and delivers to TestFlight" requirement is updated — the archive remains the every-push merge gate and compiles `iosArm64` exactly once, but delivery now splits across two capabilities (development artifact every push; TestFlight on `main`).

## Impact

- **Workflow**: `.github/workflows/ios.yml` (`ios-build` job) — add development export + artifact-upload steps; add `if: github.ref == 'refs/heads/main'` to the app-store export + TestFlight upload steps.
- **New file**: `iosApp/ExportOptionsDevelopment.plist` (`method=development`, automatic signing).
- **Specs**: new `ios-sideload-delivery`; deltas to `ios-testflight-delivery` and `ios-ci`.
- **Docs**: `CLAUDE.md` (or `docs/`) — the local sideload install runbook.
- **One-time operator prerequisites (out of CI)**: register UDID `00008030-0018703A1A7A402E` at developer.apple.com → Devices; enable Developer Mode on the SE2.
- **No change** to: the merge gate (the archive), `ios-test`, build numbering, the imported certs, or the ASC secrets.
