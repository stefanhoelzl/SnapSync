## 1. UI change

- [x] 1.1 Add the "Allow full access" `SecondaryButton` to `StatusScreen.kt`'s joined layer,
      directly below "Choose more photos" inside the existing `canChoosePhotos` block, wired to the
      already-received `onOpenSettings` callback; keep/extend the surrounding posture comment to
      cover both affordances
- [x] 1.2 Add `:ui:screens` test coverage: renders below "Choose more photos" when
      `canChoosePhotos` is true (across health values), absent when false, and tap fires
      `onOpenSettings` (and not `onChoosePhotos`/`onRequestPermission`)
- [x] 1.3 `./gradlew build` green (includes the offscreen `:ui:screens` jvmTest and all
      architecture gates); `./gradlew compileIosMainKotlinMetadata` as the iOS-source proxy

## 2. Harness sanity check

- [x] 2.1 Drive the forge harness headless (`:test:harness-driver:driveForge`): under the `LIMITED`
      permission preset both affordances render in order; under `GRANTED` neither renders — capture
      `/phone.png` for the PR

## 3. On-device verification (operator-assisted, pre-merge)

- [x] 3.1 Open an ssh-mac session, build a Debug unsigned archive, re-sign per the runbook, sideload
      to the SE2 over usbmuxd
- [x] 3.2 Operator pre-creates a fresh event in the app; join headless via `SNAPSYNC_EVENT_LINK`
      (confirm `reconcile(eventId=…)`/`config ok` in `debug.log` match the fresh id)
- [x] 3.3 Operator sets photo access to Limited with a small selection; screenshot the joined layer
      showing both affordances; operator taps "Allow full access" and confirms it lands on the app's
      Settings page (no dialog)
- [x] 3.4 Seed above-floor post-cutoff assets via `SNAPSYNC_SEED_POLICY` (they auto-join the limited
      selection); confirm the limited-tier baseline from the policy log lines
- [x] 3.5 Operator flips Photos → Full Access in Settings; confirm the OS terminates the app
      (expected, not a crash); relaunch and verify in `debug.log`: permission `GRANTED`, the
      `GRANTED`-tier (PhotoKit extension) producer starts, no selection-change observer, reconcile
      seeds prior uploads (nothing re-uploads)
- [x] 3.6 Re-provision via the event link to trigger an extension invocation; confirm a
      newly-in-scope seed lands in the backend's bunny storage zone (the authoritative end-to-end
      check)

## 4. Spec & ship

- [x] 4.1 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` green on the change's
      delta specs
- [x] 4.2 Regenerate architecture diagrams if anything moved (`./gradlew architectureDiagrams`) —
      expected no-op for a `:ui:screens`-only change
- [ ] 4.3 Branch → PR (include the forge screenshot + device-pass evidence) → `/ship`; archive the
      change after merge per the archive gates
