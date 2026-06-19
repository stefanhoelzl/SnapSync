## Context

The `ios-build` job in `.github/workflows/ios.yml` compiles a signed archive of the iOS device app (the merge gate), then exports an `app-store-connect` IPA and uploads it to TestFlight — on **every push, any ref**. The sole reason for the every-push TestFlight upload is to make each branch installable on the physical SE2 before merge (internal testing, no Beta App Review). That volume of uploads is what hits Apple's TestFlight upload limits.

There is no Mac in the local environment (Linux sandbox); the only macOS is the `macos-26` runner, so the installable build must be produced in CI and pulled down. The SE2 (`00008030-0018703A1A7A402E`, iOS 26.5, iPhone12,8) is reachable from the sandbox through the host usbmuxd bridge (`USBMUXD_SOCKET_ADDRESS=UNIX:/run/host/run/usbmuxd`).

The workflow already imports **two persistent signing certs** — Apple Distribution and Apple Development — into a shared keychain each run (the empty-keychain-mints-a-new-cert cap-exhaustion fix). The Apple Development cert is the key enabler here: a `method=development` export can reuse it. The Xcode project currently has a **single** `PBXNativeTarget` (`iosApp`, `CODE_SIGN_STYLE = Automatic`) — no separate upload-extension target yet — so only one bundle id needs a development profile.

## Goals / Non-Goals

**Goals:**
- Make every branch installable on the registered SE2 without any TestFlight upload, by publishing a development-signed IPA as a GitHub Actions artifact on every push.
- Narrow TestFlight delivery to `main` so the every-push upload volume disappears.
- Keep the merge gate (the signed archive) and the single-compile-per-push property unchanged.
- Correct the stale "cloud-managed, no stored cert" wording in `ios-testflight-delivery` to the imported-cert reality.

**Non-Goals:**
- Automating UDID registration (done once, manually, at developer.apple.com for a single device).
- Committing a local install helper script (the runbook is documented commands only).
- Changing the merge gate, build numbering, the imported certs, or the ASC secrets.
- Specifying the on-device install steps as CI requirements — they are an operator runbook (docs), not system behavior.
- Ad-hoc signing or a debug build configuration (development method on the Release archive is sufficient).

## Decisions

**1. Development export, reusing the imported Apple Development cert (vs ad-hoc).**
Development export works with the existing automatic signing + imported Development cert: `xcodebuild -exportArchive -allowProvisioningUpdates` authenticated by the ASC Admin key mints/updates a development provisioning profile that automatically includes all registered team devices. Ad-hoc would avoid the on-device Developer-Mode requirement but needs a manually managed ad-hoc profile (automatic signing does not create one) — more moving parts. We accept the one-time Developer-Mode toggle on the SE2 in exchange for near-zero CI change. *Trade-off:* dev-signed apps require Developer Mode enabled on the device; ad-hoc would not.

**2. Same archive, two exports (vs a second archive).**
The development IPA exports from the *same* `SnapSync.xcarchive` the gate produces. The device app compiles exactly once; the two exports differ only by `ExportOptions` plist (`development` vs `app-store-connect`). Preserves the single-compile-per-push invariant.

**3. Non-gating artifact (vs failing the build on export error).**
The development export + artifact upload run with `continue-on-error: true`, mirroring the existing app-store export. The merge gate is the archive compile alone; a signing/export flake must not block merges. The artifact simply won't be present that run (visible in the run log).

**4. TestFlight gated by `if: github.ref == 'refs/heads/main'` (vs deleting it).**
Keep a release trail on `main`; just stop the per-branch uploads. The app-store export and `upload-testflight-build` steps each get the ref guard. The per-branch-installability rationale moves wholesale to the sideload artifact.

**5. New capability `ios-sideload-delivery` (vs merging into `ios-testflight-delivery` or `ios-ci`).**
Additive — lowest risk to the contract of record. The two delivery channels differ on every axis (trigger every-push vs main; method development vs app-store-connect; destination artifact vs ASC; both non-gating). `ios-testflight-delivery` is merely narrowed; the sideload capability is born clean.

**6. `pymobiledevice3` via `uvx` for the local install (vs ideviceinstaller).**
iOS 26.5 is new; `pymobiledevice3` has first-class iOS 17–26 support, can also enable Developer Mode, and respects the usbmuxd socket. Run ephemerally with `uvx pymobiledevice3 …` (no global install). Install over `installation_proxy`/lockdownd needs no CoreDevice developer tunnel — only launching under a debugger would. This is operator tooling, documented, not part of CI.

## Risks / Trade-offs

- **`method=development` export has never run in this pipeline** → Verify during apply that `-exportArchive` mints the development profile from the imported Development cert + ASC Admin key on the runner (the cert is present and the key is Admin, so it should). Non-gating, so a failure here doesn't block merges.
- **Device not registered before first export** → The development profile would exclude the SE2 and the IPA won't install. Mitigation: register the UDID once at developer.apple.com → Devices before relying on the artifact; `-allowProvisioningUpdates` regenerates the profile to include it on the next export.
- **Developer Mode disabled on the SE2** → dev-signed app won't launch. Mitigation: enable once via `uvx pymobiledevice3 amfi enable-developer-mode` (software-triggered reboot — sidesteps the SE2's dead hardware buttons).
- **TestFlight narrowed to `main`** → branches no longer have a TestFlight build; that is the intended swap, with the artifact taking over per-branch installs. Anyone relying on branch TestFlight builds uses the artifact instead.
- **Artifact retention 1 day** → an old run's IPA disappears quickly; intended (you install the latest, not archive history). Re-run the workflow if a build aged out.

## Migration Plan

1. Land the spec deltas + new capability, the `ExportOptionsDevelopment.plist`, and the `ios.yml` edits (dev export + artifact upload every push; `main`-only guard on the TestFlight steps).
2. One-time operator step: register UDID `00008030-0018703A1A7A402E` at developer.apple.com → Devices; enable Developer Mode on the SE2.
3. Document the install runbook in `CLAUDE.md`/docs.
4. Rollback: revert the `ios.yml` edits — TestFlight every-push delivery returns; the artifact steps disappear. No state to unwind (certs/secrets untouched).

## Open Questions

None blocking. The single verify-during-apply item is decision-1's runner behavior (development profile minting), explicitly handled as non-gating.
