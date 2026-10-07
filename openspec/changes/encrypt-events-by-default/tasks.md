# Tasks

## 1. Production creates encrypted events (D1)

- [x] 1.1 Replace `DevControls.encryptsNewEvents()` with `createsPlainEvents()` (inert `false` in `InertDevControls`), and make `EventKeyMinting.forNewEvent()` mint unless it answers `true`; update the port's and `EventKeyMinting`'s KDoc. Verify: `InertDevControlsTest` and an `EventKeyMinting` test pin "inert → minted key".
- [x] 1.2 Flip the mocks' (`DevControlsMock`) and the rig's (`RigDevControls`) default to encrypting; keep the `device/encrypt-new-events` verb with `on=false` meaning plain; update `MockLevers`/`JvmRigCommands` wording and the `rig-channel` skill's paragraph. Verify: `:test:control` passes.
- [x] 1.3 Rewrite `EncryptedEventIntegrationTest`'s `a_shipped_build_creates_plain_events…` into "a shipped build creates encrypted events" and add a plain-via-`on=false` case; make the suite encrypted-aware (D8): the JVM world records the keys it draws, mocked downloads arrive sealed, and test-built invites carry the key (`device/invite`). Verify: `./gradlew :test:integration:test` green.

## 2. Key presence and the transfer pause (D2, D3)

- [x] 2.1 Add `KeyPresence` (`NotNeeded`/`Held`/`Unknown`/`Lost`) and `EventKeys.presence` over the joined config, re-read on membership change, foreground and after `keep`. Verify: `:test:feature` tests for each value, including Absent → `Lost`, throwing store → `Unknown`, foreign key id → `Lost`.
- [x] 2.2 Make the upload cycle's gate refuse to run while presence is `Lost` (no walk, export, seal, manifest publish or job), in the app's uploader and the PhotoKit extension. Verify: feature tests that a `Lost` member's cycle touches neither the gallery nor the backend, in both compositions, and resumes once the key is kept; a locked store (`Unknown`) still runs the cycle and withholds at the seal.
- [x] 2.3 Stop enqueueing downloads while presence is `Lost`, and stop counting "no event key is kept" toward `DownloadOpening`'s report. Verify: a feature test that a `Lost` member starts no download and resumes once the key is kept; `EncryptedTransfersTest` still green.

## 3. Restore by reopening the invite (D4)

- [x] 3.1 Add `UserCommands.restoreEventKey(linkKey)` (keep + re-read presence + a foreground cycle) and the same-event rung in `StatusContainerHost.onOpenUrl`: `Lost` and the link's key id is the event's → restore; anything else stays the logged no-op. Verify: presentation tests for restore, wrong key, no key, and a `Held` member's rescan.
- [x] 3.2 Integration test over the rig: create an encrypted event, delete the key slot (a rig verb if none exists, else through the secure-store mock's operator), see the lost-key line and no downloads, open the whole invite, see sharing resume with settings unchanged. Verify: `:test:integration:test`.

## 4. The status line (D5)

- [x] 4.1 Add the lost-key status to `UiState`'s status-line priority (after "Not sharing or receiving", before missing access), not tappable, with EN + DE strings in `:ui:screens`. Verify: presentation priority tests (lost key vs. missing access, vs. both-off), a `:ui:screens` UI test rendering the line, `HardCodedUiText` passes.
- [x] 4.2 Add a world-harness lever (or reuse the secure-store mock's) so the line is reachable in `:app:desktop`. Verify: driven once through `ui-harness` and screenshotted.

- [x] 4.3 Offer no invite without its key: `Layer.Joined.inviteUrl` is `null` for an encrypted event whose key cannot be read (lost, or locked), hiding Share and QR and closing a shown QR; the invite key is re-read on every foreground and after a restore. Verify: `KeyLostTest`, `StatusScreenTest`, and the lost-key integration test asserting no invite while lost and the whole invite after the restore.

## 5. The Play referrer carries the key (D6)

- [x] 5.1 `site/src/lib/invite.ts`: build the referrer `v=3&d=…&k=…` only from a key `event-key.ts` verified; none otherwise. Verify: `site/scripts` tests for keyed, unkeyed and wrong-key pages.
- [x] 5.2 `inviteLinkFromInstallReferrer` accepts an optional, valid `k` and answers the keyed path form; an invalid `k` answers `null`. Verify: `EventLink` tests, and the Android referrer path's existing test extended with a keyed referrer.

## 6. Privacy Policy and docs (D7)

- [x] 6.1 Update the Privacy Policy in `site/src/pages/index.astro`: photos of events created with 0.6 or later are stored encrypted under a key only the invite carries; the iPhone background-upload one-photo key; Google Play receiving the whole invite through the page's button. Verify: `site` build and `check-selfcontained` pass; read the rendered section.
- [x] 6.2 Update `docs/architecture.md`'s encrypted-file-format section (creation is the production default; key presence; the lost-key pause and restore; the referrer `k`). Verify: `./gradlew architectureDiagrams` leaves `architecture/` unchanged or regenerated and committed.

## 7. Release bookkeeping

- [ ] 7.1 After rebasing onto the merged `cleanups` branch, add two `PENDING_CLEANUPS.md` entries for the 0.6 promote: (a) the SQL check that no 0.4 device is a member of an open event, then `MIN_APP_VERSION` 0.4 → 0.5; (b) the site card + store-listing privacy line ("stored so only your group can open them"). Verify: the file lists both, each with its precondition.

## 8. Integration checks

- [x] 8.1 `./gradlew build` and `compileIosMainKotlinMetadata` green; `npx --yes @fission-ai/openspec@1.13.2 validate encrypt-events-by-default --strict` passes.
