# Tasks

## 1. Status line words

- [x] 1.1 Change `status_syncing` / `status_in_sync` / `status_sync_ongoing` / `status_sync_pending` in `:ui:components` `values` and `values-de` to Loading… / Up to date / Photos arriving… / Photos queued… (de: Wird geladen … / Alles aktuell / Fotos kommen an … / Fotos warten …) per design D8; update `ui/components` and `ui/screens` `TestStrings` and every test asserting the old words; verify `./gradlew :ui:components:jvmTest :ui:screens:jvmTest` passes
- [x] 1.2 Sweep KDoc and comments that quote "In sync" / "Synchronization pending…" as the screen's words (`grep -rn '"In sync"\|Synchronization' --include=*.kt`) and update the ones citing the spec's wording; verify the grep returns only deliberate historical mentions

## 2. QR sheet overlay (domain)

- [x] 2.1 Add `showingQr` to `Overlays` with `UiIntent.QrOpen` / `UiIntent.QrDismiss`, reduced in `StatusContainerHost` and masked against a non-joined or closed layer like `renaming`; verify a `StatusContainerHostSurfacesTest` case: opens, dismisses, and clears when the event closes or the membership ends
- [x] 2.2 Bind the two intents in `statusActions` (`HostStatusActions.kt`); verify `HostStatusActionsTest` clicks both

## 3. UI building blocks (`:ui:components`)

- [x] 3.1 Add an explanation row component (icon slot, title, caption with optional inline links as real clickable, accessible controls, optional trailing content) and a slashed-glyph icon variant (muted for a choice, amber for a problem) per design D5–D7; verify a jvmTest renders each variant and clicks each link
- [x] 3.2 Add the QR bottom sheet (event name, `AppQrCode` dark-on-light in both appearances, the caption) on the existing sheet primitive; verify a jvmTest renders it in dark appearance with a light QR background and dismisses on swipe/outside tap

## 4. The joined screen (`:ui:screens`)

- [x] 4.1 Replace the QR hero in `JoinedLayer` with the "How it works" section: share row (full / limited with Choose more photos + Allow full access stacked / off / no access), receive row (album named / no album / off / no access), hint row; links → `onOpenReconfigure` and the status line's access branch; closed event → receive row + hint only. Strings in `values` and `values-de` (design D5, D9). Verify `StatusScreenTest` cases for every row variant, both links' intents, and the closed layout
- [x] 4.2 Move the limited-access buttons out of the status block into the share row; verify the `photo-access` scenarios "Full access shows neither choice" and "Limited access" in `StatusScreenTest`
- [x] 4.3 Replace `JoinedBottomActions` with the docked footer in `ScreenLayout`'s pinned bottom slot: Share invite link + Show QR code (two filled buttons), divider, Event settings + Leave event (stacked text buttons); closed → Leave alone; remove the share icon. Verify `StatusScreenTest`: tapping each fires its intent, and on an SE2-sized (375 × 667) window with long German content the footer stays on screen while the content scrolls
- [x] 4.4 Render the QR sheet from `overlays.showingQr` in `StatusOverlays`; verify a test: Show QR code opens the sheet with the invite URL encoded, dismiss returns to the unchanged screen, a closing event removes it
- [x] 4.5 German joined statement → "Du nimmst an diesem Event teil"; run `./gradlew nativeStrings` if any OS-shown key changed and commit the output; verify `HardCodedUiText` passes in `./gradlew build`

## 5. Harness, screenshots, docs

- [x] 5.1 Review every joined state in the desktop world harness via the `ui-harness` skill (running, arriving, not started, limited, no access, sharing/receiving off, closed, QR sheet, light/dark, de) against the mock; verify by captured pixels
- [x] 5.2 Update `:test:integration` tests that assert the old status words or the always-visible QR; verify `./gradlew :test:integration:test` passes, including `ShotsTest`
- [ ] 5.3 Refresh the marketing screenshots (`screenshots.yml` for iOS and Android), eyeball, commit; verify `in_sync` raws show the new screen and no system notification
- [x] 5.4 Update `metadata/messaging.md` only if a new user-facing term was introduced (none expected); verify by reading the glossary against the new strings

## 6. Integration

- [x] 6.1 `./gradlew build` passes (detekt tiers included: `ui.yml` ceilings may only fall — if a ceiling must rise, state the forcing proof in the PR); `./gradlew architectureDiagrams` leaves `architecture/` unchanged or is committed
- [x] 6.2 `npx --yes @fission-ai/openspec@1.13.2 validate explain-joined-screen --strict` and `validate --specs --strict` pass
- [x] 6.3 After archiving: retitle the scenarios that still quote "In sync" in `sync-status` ("In sync shows totals", "Photos discovered after opening leave "In sync"") and `photo-access` ("In sync over the selection"), and update `sync-status`'s Purpose sentence quoting "In sync" (design D10); verify `grep -n 'In sync' openspec/specs/*/spec.md` returns nothing
