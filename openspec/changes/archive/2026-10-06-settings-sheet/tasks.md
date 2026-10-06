# Tasks

## 1. Neither direction (`:domain:model`, design D6)

- [x] 1.1 Add `Direction.Neither` (wire `none`); make `includesUpload` / `includesDownload` explicit so `Neither` answers false to both; keep `Direction.fromWire` and the invite link's dev `direction` key refusing `none`; verify `:domain:model:jvmTest` with new cases (both getters, `fromWire("none") == null`, an `EventConfig` holding `Neither` round-trips through `ConfigFile`)
- [x] 1.2 Add the both-false arm to `RangeResolution`'s `shareOn/receiveOn → Direction` mapping; verify a presentation unit test maps (false, false) → `Neither` while the join gate's confirm still refuses both off (existing join-gate test stays green)
- [x] 1.3 Cover the consumers with no special case: every gate (the upload policy, the download controller's single `downloadEnabled` choke point that the silent push also goes through, a closed event's "has everything") reads `includesUpload` / `includesDownload`, so `Neither` is covered by the getter test, a model test that its policy is `DenyAll`, and a `ReconfigureEventTest` case that switching both off saves `Neither`, cancels downloads, starts no reconcile and kicks the upload arm; verified `./gradlew :domain:model:jvmTest :test:feature:jvmTest`

## 2. Both-off status line (`sync-status`, design D7)

- [x] 2.1 Add `SyncHealth.Inactive`, reduced in `StatusContainerHost` when the membership is `Neither` and no arrow is shown, ranked first after Loading (ahead of access, network, not-started, unverified); counts hidden; not tappable; verify `StatusContainerHostSurfacesTest` (or the health tests) for: both off + missing access → Inactive; both off + upload still draining → Syncing; then Inactive once it drains
- [x] 2.2 Render it in `AppStatusLine` ("Not sharing or receiving" / "Du teilst und empfängst nichts", a neutral icon) via `JoinedLayer.toAppSyncStatus`; strings in `values` and `values-de`; verify a `StatusScreenTest` case shows the line and no counts line

## 3. Settings as an overlay that applies per change (`:domain:presentation`, design D1–D5)

- [x] 3.1 Keep `JoinedSurface.Reconfigure` as the open-settings state (design D1, revised during apply) and give it `askingToStopSharing`; seed the form from `EventConfig` on open and re-seed it after every applied change (D2); `OpenReconfigure` opens, `CancelReconfigure` is the close (writes nothing, drops a held question); drop `UiIntent.Reconfigure`; verified by the surface tests
- [x] 3.2 With settings open, `ShareOn` / `ReceiveOn` / `SaveToAlbum` / `MobileData` / `RangePreset` / `RangeCustom` each apply one reconfigure built from the current config with that field changed (D3); in the join gate they keep editing the form; verify host tests: one `commands.reconfigure` per intent, in order, last one standing after three quick flips
- [x] 3.3 Withdrawal question (D4): sharing off, or a committed range later-start / earlier-end than the config's, sets `pendingWithdrawal` instead of applying; add `ConfirmWithdrawal` / `KeepSharing` intents; verify host tests: no reconfigure until confirm, keep clears it, widening applies directly, a range narrower at one end and wider at the other asks
- [x] 3.4 Failure (D5): `SaveFailed` sets `changeFailed` (cleared by the next success or on close), `NotCurrent` closes the overlay; verify host tests including "the shown value is still the config's after a failure"
- [x] 3.5 Update the rig's `/user` vocabulary (`test/rig/.../UserCommands.kt`: `reconfigure` opens and applies field by field, `cancelReconfigure` → close, new confirm/keep verbs) and `:test:control`'s client; verify `./gradlew :test:control:test` and the `RigVocabulary` guard

## 4. UI (`:ui:components`, `:ui:screens`, design D8–D9)

- [x] 4.1 `ParticipationSections` as one `AppToggleCard`: share (+ range row, notes), receive, album, mobile data as rows divided by `AppToggleDivider`, for the join gate and the settings alike; verify `JoinScreenTest` and `StatusScreenTest` find every switch and note, and the join screen still disables Join with both off
- [x] 4.2 Add the full-height settings sheet primitive (drag handle only, top held below the status bar plus a strip, `skipPartiallyExpanded`, swipe / back / scrim tap → one `onDismiss`); verify a components jvmTest dismisses by scrim tap and by swipe
- [x] 4.3 `ReconfigureScreen` → the sheet's body: no header, no Save/Cancel, no standing "stop sharing" note, the "both off" reason removed, the failure line at the top while `changeFailed`; drawn from `StatusScreen`'s overlays over the composed joined screen; verify `StatusScreenTest`: opening from the footer and from an explanation link shows the sheet over the joined screen, each switch fires its intent, the failure line shows
- [x] 4.4 The withdrawal dialog on `AppConfirmDialog` ("Stop sharing these photos?", the narrowing statement, Stop sharing / Keep sharing) in `values` and `values-de`; delete the now-unused strings (`settings_stop_sharing_note`, Save/Cancel-only copy); verify `StatusScreenTest` shows it for `pendingWithdrawal` and fires each intent, and `HostStatusActionsTest` drives sharing off → confirm end to end
- [x] 4.5 Update the KDoc that describes Save/Cancel (`ReconfigureEvent`, `ReconfigureOutcome`, `JoinedSurface` remnants, `ParticipationSections`) to per-change wording; verify `grep -rn 'Save\b' --include=*.kt domain/feature/src/commonMain/kotlin/app/snapsync/feature/membership` shows no stale "on Save" claim

## 5. Integration

- [x] 5.1 `:test:integration`: a rig scenario that opens settings, turns receiving off (downloads stop, no Save), turns sharing off and confirms (manifest empties, status reads "Not sharing or receiving"), and closes; verify `./gradlew :test:integration:test`
- [x] 5.2 Full `./gradlew build` green, `./gradlew architectureDiagrams` committed if it moved, `npx --yes @fission-ai/openspec@1.13.2 validate --specs --strict` and `validate settings-sheet --strict` pass
- [x] 5.3 Looked at on the A40 (rig build, 360×703 dp): the sheet with its strip and handle, the one card, the stop-sharing question with a red confirm — accepted by the user; the harness at XS/SE2 sizes was not run, and swipe/back/strip closing is covered by the UI tests rather than on device
