## 1. Export options

- [x] 1.1 Add `iosApp/ExportOptionsDevelopment.plist` with `method=development`, `signingStyle=automatic`, `teamID=E9Z8BADH58`, `manageAppVersionAndBuildNumber=false` (mirror the existing `ExportOptions.plist`, only the method differs).

## 2. Workflow — sideload artifact (every push)

- [x] 2.1 In `.github/workflows/ios.yml` (`ios-build` job), add an **Export development IPA** step after the archive step: `xcodebuild -exportArchive` from `$RUNNER_TEMP/SnapSync.xcarchive` using `iosApp/ExportOptionsDevelopment.plist` to a separate export path (e.g. `$RUNNER_TEMP/export-dev`), with `-allowProvisioningUpdates` + the ASC key auth flags. Set `continue-on-error: true`.
- [x] 2.2 Add an **Upload dev IPA artifact** step (`actions/upload-artifact`): path = the exported dev IPA, `name: snapsync-dev-ipa-${{ github.run_number }}`, `retention-days: 1`, `continue-on-error: true`.

## 3. Workflow — narrow TestFlight to main

- [x] 3.1 Add `if: github.ref == 'refs/heads/main'` to the existing **Export signed IPA** (app-store) step.
- [x] 3.2 Add `if: github.ref == 'refs/heads/main'` to the existing **Upload to TestFlight** step.
- [x] 3.3 Update the `ios.yml` header comment so it no longer claims every-push TestFlight delivery; describe the two channels (dev-IPA artifact every push; TestFlight on `main`).

## 4. Docs — operator runbook

- [x] 4.1 Add a "Sideload a dev IPA" section to `CLAUDE.md` (near the on-device iOS section): one-time UDID registration at developer.apple.com → Devices (`00008030-0018703A1A7A402E`); enable Developer Mode via `uvx pymobiledevice3 amfi enable-developer-mode`; per-build `gh run download … -n snapsync-dev-ipa-<n>` then `uvx pymobiledevice3 apps install …` over the usbmuxd bridge. Note it is operator runbook, not CI.

## 5. Spec prose sync (drift)

- [x] 5.1 Update the **Purpose** prose of `openspec/specs/ios-testflight-delivery/spec.md` (applied at archive): "every push (any ref)" → "`main` only"; "fully cloud-managed … no stored certificate" → the two imported persistent certs. (The requirement deltas already carry the normative changes; this keeps the non-normative Purpose header accurate.)
- [x] 5.2 Update the **Purpose** prose of `openspec/specs/ios-ci/spec.md`: the archive "doubles as the TestFlight delivery source" → it feeds two channels (dev-IPA artifact every push; TestFlight on `main`).

## 6. One-time operator prerequisites (out of CI — verify, do not automate)

- [x] 6.1 Register UDID `00008030-0018703A1A7A402E` at developer.apple.com → Devices (manual, one-time).
- [ ] 6.2 Enable Developer Mode on the SE2. NOTE: arming over the usbmux bridge hangs (reboot fires but the bit never flips) and the Settings → Privacy & Security → Developer Mode menu does not appear until a dev-signed app is installed. Deferred to first install (7.3): install the dev IPA → iOS prompts for Developer Mode → toggle on (software restart, no buttons).

## 7. Verify

- [ ] 7.1 Push the branch; confirm `ios-build` is green and a `snapsync-dev-ipa-<run_number>` artifact is published with 1-day retention, and that the dev export step succeeded (the verify-during-apply item: `method=development` mints the dev profile from the imported Development cert + ASC key on the runner).
- [ ] 7.2 Confirm a non-main branch push does **not** upload to TestFlight (app-store export + upload steps skipped), while a `main` build still does.
- [ ] 7.3 `gh run download` the artifact and `uvx pymobiledevice3 apps install` onto the SE2 over the usbmuxd bridge; confirm the app installs and launches (Developer Mode on).
