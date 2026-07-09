## 1. Write the 19 placeholder Purposes

Each Purpose is derived from the spec's own `## Requirements` **plus** its archived change's
`proposal.md` "Why" (the motivation). A Purpose that only paraphrases its own requirements is a failed
Purpose. Cite the decision record as `Decision record: changes/archive/<id>` where it adds value.

- [x] 1.1 `ios-app-shell` ← `archive/2026-06-17-ios-first-target`
- [x] 1.2 `ios-photokit-upload` ← `archive/2026-06-19-ios-background-upload` (also de-"backup", see 4.1)
- [x] 1.3 `event-creation` ← `archive/2026-06-27-add-event-creation`
- [x] 1.4 `event-creation-ui` ← `archive/2026-06-27-add-event-creation-ui` (also de-"backup", see 4.1)
- [x] 1.5 `backend-config` ← `archive/2026-06-27-add-image-download`
- [x] 1.6 `event-rejoin-reconciliation` ← `archive/2026-06-27-add-rejoin-reconciliation`
- [x] 1.7 `photo-download` ← `archive/2026-06-30-add-photo-download`
- [x] 1.8 `download-store` ← `archive/2026-06-30-add-photo-download`
- [x] 1.9 `full-stack-harness` ← `archive/2026-07-03-add-full-stack-harness`
- [x] 1.10 `ios-url-session-upload` ← `archive/2026-07-04-add-url-session-upload`
- [x] 1.11 `apns-push-sender` ← `archive/2026-07-05-push-notification-infra`
- [x] 1.12 `device-config-endpoint` ← `archive/2026-07-05-push-notification-infra`
- [x] 1.13 `push-registration` ← `archive/2026-07-05-push-notification-infra`
- [x] 1.14 `event-notify-endpoint` ← `archive/2026-07-05-push-notification-infra`
- [x] 1.15 `upload-completion-notify` ← `archive/2026-07-05-notify-driven-download`
- [x] 1.16 `join-event` ← `archive/2026-07-06-add-event-join-confirmation`
- [x] 1.17 `event-leave-endpoint` ← `archive/2026-07-06-add-event-leave-lifecycle`
- [x] 1.18 `photo-date-cutoff` ← `archive/2026-07-06-add-join-date-cutoff` (also de-"backup", see 4.1)
- [x] 1.19 `event-album` ← `archive/2026-07-08-add-event-album`
- [x] 1.20 Verify: `grep -rl "TBD - created by archiving" openspec/specs/` returns nothing

## 2. Replace the 8 `docs/design.md` pointers

Rewrite each Purpose so it is self-contained, then swap the outward pointer for
`Decision record: changes/archive/<id>`. Note `sync-engine`'s `Authoritative design:` wraps across a line
break — grep for `docs/design.md`, not the phrase.

- [x] 2.1 `sync-engine` (§2.2) — fold the platform-seam framing into Purpose; record ← `2026-06-12-sync-engine-ledger`
- [x] 2.2 `sync-ledger` (§2.2) — record ← `2026-06-12-sync-engine-ledger`
- [x] 2.3 `sync-status` (§2.4) — record ← `2026-07-05-notify-driven-status` (also de-"backup", see 4.1)
- [x] 2.4 `gallery-status` (§2.4) — record ← the change that introduced the own-device gallery total
- [x] 2.5 `bunny-upload-endpoint` (§3.1, §4, §8) — record ← `2026-07-06-restructure-storage-url-layout`
- [x] 2.6 `bunny-list-endpoint` (§3.1, §4) — record ← `2026-07-02-add-s3-presigned-downloads`
- [x] 2.7 `backend-deployment` (§4, §7) — record ← the backend-deployment change
- [x] 2.8 `harness-world-model` (§2.4, §3.2, §3.3, §5.1, §6) — record ← `2026-07-03-add-harness-world-model`
- [x] 2.9 Verify: `grep -rl "docs/design.md" openspec/specs/` returns nothing
- [x] 2.10 Gate: `npx --yes @fission-ai/openspec@1.4.1 validate --specs --strict` passes 44/44

## 3. Fix the `device-manifest` Purpose typo

- [x] 3.1 `device-manifest/spec.md:6` — Purpose says `/events/<eventId>/device/<deviceId>.json` (singular);
      lines 15 and 28 say `devices/` (plural). Correct the Purpose to match the Requirements.

## 4. Retire the "backup" vocabulary

Product-facing prose says *share* / *receive*. `sync` stays as the mechanism word; `SnapSync` stays as a
proper noun. **No identifier or spec-directory renames.**

**Scope narrowed during apply** (D8 holds): only the **3 `## Purpose` hits** are editorial. The other 12
"backup" occurrences sit inside `## Requirements` and would need `MODIFIED` deltas — deferred. Two of those
(`event-creation-ui`'s *"Sharing framing in create and status copy"* and `sync-status-screen`'s *"Copy avoids
backup framing"*) are **correct as written**: they are requirements that mandate avoiding backup framing, and
must never be "fixed".

- [x] 4.1 De-"backup" the 3 Purpose hits: `sync-status:5`, `sync-status-screen:6`, `permission-gate:5`
- [x] 4.2 De-"backup" the KDoc of the 4 files already being touched in §5: `SyncProgress.kt`
      ("Snapshot of backup truth", "the backup machinery"), `Ledger.kt` ("existence is the proof of
      backup"), `UploadKeys.kt` ("we back up"), `IosBackgroundScheduler.kt` ("a first whole-library
      backup"). Comments only.
- [x] 4.3 Verify: no `backup` outside `openspec/changes/archive/`, the 12 deferred requirement-zone
      occurrences, and git history

## 5. Repoint the code references onto the owning spec

`§2.2` → `openspec/specs/sync-engine` · `§2.3`/`§2.4` → `sync-status` · `§5` → `design-system` ·
`§5.1` → `desktop-test-harness` / `full-stack-harness` · `§3.1` → `bunny-upload-endpoint` /
`device-manifest` · `§4` → `backend-config` · `§6` → `CLAUDE.md`'s testing rules.

- [x] 5.1 `domain/engine/.../SyncEngine.kt` (6 refs) and `Ledger.kt`
- [x] 5.2 `domain/status/.../SyncProgress.kt`, `LedgerBackedSyncStatusSource.kt`
- [x] 5.3 `domain/gallery/.../RawAsset.kt`, `GalleryStatusSource.kt`, `UploadKeys.kt`
- [x] 5.4 `domain/presentation/.../UiState.kt` (2 refs)
- [x] 5.5 `domain/ui/components/.../ScreenLayout.kt`
- [x] 5.6 `capability/config/.../ConfigPorts.kt`, `ConfigDeeplink.kt`, `QrGeneratorMain.kt`
- [x] 5.7 `capability/upload/.../UploadLivenessNotification.kt`,
      `capability/upload-url/.../EdgeUploadRequestProvider.kt`, `capability/join/.../JoinEvent.kt`
- [x] 5.8 `app/ios/.../SnapSyncRoot.kt` (2 refs),
      `app/ios/photokit-extension/.../UploadExtensionRoot.kt` (2 refs),
      `app/ios/url-session-upload/.../IosBackgroundScheduler.kt`
- [x] 5.9 `app/desktop/build.gradle.kts`, `app/desktop/ui/build.gradle.kts`,
      `domain/ui/components/build.gradle.kts`
- [x] 5.10 Gate: `./gradlew build` passes

## 6. Update the remaining docs

- [x] 6.1 `CLAUDE.md` line 3 — replace "SnapSync v1 — a personal one-way iOS photo backup to S3" with the
      event photo-sharing statement
- [x] 6.2 `CLAUDE.md` "Read first" — point at `openspec/specs/` (contract of record) and
      `openspec/changes/archive/<id>/design.md` (decision record); delete the maintainer note about not
      `@`-importing `docs/design.md`
- [x] 6.3 `CLAUDE.md` module list — add `:capability:album`, `:capability:join`, `:capability:push`
- [x] 6.4 `CLAUDE.md` line 51 — "physical-device-only on the iOS 27 beta" contradicts `app/ios/CLAUDE.md`
      (the app ships against the deprecated 26.1 `PHBackgroundResourceUploadExtension`, the only protocol
      runnable on current GM devices; the test device is an SE2 on 26.5). Reconcile.
- [x] 6.5 `CLAUDE.md` — repoint the remaining `docs/design.md §N` refs (9 total) onto the owning specs
- [x] 6.6 `app/ios/CLAUDE.md` (1 ref) and `backend/README.md` (2 refs) — repoint
- [x] 6.7 Verify: `grep -rn "design\.md"` outside `openspec/changes/archive/` and `.claude/` returns nothing

## 7. Delete the docs (last — every step above still reads them)

- [x] 7.1 `git rm docs/design.md` (988 lines)
- [x] 7.2 `git rm docs/sync-refactor.md` (239 lines; shipped as `archive/2026-07-02-relocate-upload-cycle`,
      header still reads "Status: proposal (not yet implemented)")
- [x] 7.3 Verify `docs/` is empty and removed; no root README is created

## 8. Implement `openspec-archive-command`

- [x] 8.1 `.claude/skills/openspec-archive-change/SKILL.md` — add a post-archive step that replaces any
      minted `TBD - created by archiving` Purpose, derived from the change's `proposal.md` and delta specs
- [x] 8.2 Same file — add a pre-success check over the whole `openspec/specs/` tree that fails the archive
      and names offending files when `TBD - created by archiving` survives
- [x] 8.3 Same file — require Purposes to be self-contained (no pointer outside `openspec/`; cite
      `Decision record: changes/archive/<id>` instead)

## 9. Ship

- [x] 9.1 Gate: `./gradlew build`
- [x] 9.2 Gate: `npx --yes @fission-ai/openspec@1.4.1 validate --specs --strict`
- [x] 9.3 `/openspec-archive-change` (archived; ship-command aborts on un-archived changes), then branch → PR → `/ship`
