## Why

`docs/design.md` (988 lines) restates contracts that `openspec/specs/` owns, while eight of those specs
punt their rationale back to it (`Authoritative design: docs/design.md §N`). Nobody owns the result, so
it rotted: §3.1 documents an object-key layout superseded three times, §4 documents an endpoint contract
that §3.5 of the same file describes correctly, and §3.5/§4/§8 declare event creation "external, out of
scope" while §1 documents it as shipped. Meanwhile the specs cannot absorb the load — **19 of 44 carry a
placeholder `Purpose: TBD - created by archiving change X`**, minted by the openspec CLI and never filled
in, because `openspec-archive-change` has no step that requires it.

OpenSpec already provides both homes: `specs/<cap>/spec.md` is the contract of record, and
`changes/archive/<id>/design.md` is the decision record (`## Context` / `## Goals / Non-Goals` /
`## Decisions`). `docs/design.md` was doing that job globally, redundantly, and wrongly. Retire it —
after making the specs able to carry the weight, and after fixing the archive step that produced the
placeholders.

## What Changes

- **BREAKING (docs)**: delete `docs/design.md` and `docs/sync-refactor.md`. `docs/` is emptied; no root
  README replaces them.
  - `sync-refactor.md` is a shipped proposal whose header still reads *"Status: proposal (not yet
    implemented)"* — it landed as `archive/2026-07-02-relocate-upload-cycle`.
- **Write a real `## Purpose` for 27 specs** — the 19 `TBD` placeholders, plus 8 that currently punt to
  `docs/design.md §N`. Derived from each spec's own Requirements and its archived change. This is the
  precondition for deletion: the knowledge-holder cannot go while 43% of the contract of record says TODO.
- **Repoint 49 inbound references** away from `docs/design.md` and onto the owning spec: 16 Kotlin KDoc
  refs, 3 `build.gradle.kts`, 8 live specs, `CLAUDE.md` (9), `backend/README.md` (2), `app/ios/CLAUDE.md`.
- **Retire the "backup" vocabulary.** Product-facing prose says *share* / *receive*, matching the shipped
  UI, which already speaks only that. `sync` **survives** as the mechanism word; `SnapSync` survives as a
  proper noun. **No identifier or spec-directory renames.**
- **Correct stale claims in `CLAUDE.md`**: the header ("a personal one-way iOS photo backup to S3"), the
  "Read first" pointer, three missing modules, and the "iOS 27 beta" claim that contradicts
  `app/ios/CLAUDE.md`.
- **Root-cause fix**: `.claude/skills/openspec-archive-change/SKILL.md` gains a post-archive step that
  requires a real Purpose and fails if a `TBD` placeholder survives.
- **Drop, do not relocate**: `design.md` §1's non-goals (audited — most are falsified by shipped
  behavior) and §8's open-questions list (its still-live device-verification items already live in
  `bunny-upload-endpoint`'s non-normative `## Assumptions (unverified on device)` section; the rest is
  resolved).

## Capabilities

### New Capabilities

- `openspec-archive-command`: the archive step SHALL leave every spec it touches with a real `## Purpose`,
  never the CLI-minted `TBD - created by archiving change X` placeholder. Specced as a process capability
  alongside `ship-command`, `branch-protection`, and `ci-build` — this is the generator that produced the
  19-spec Purpose debt, and a skill patch with no contract behind it would rot the same way.

### Modified Capabilities

*(none — no existing `SHALL` / `WHEN` / `THEN` changes.)*

> **Note on the 27 existing spec files this change edits.** Those edits are **editorial**: `## Purpose`
> prose, the removal of `docs/design.md` pointers, and one typo inside a Purpose paragraph. No existing
> `## Requirements` block is touched, so they carry **no delta specs**. The only delta in this change is
> the new `openspec-archive-command` capability above. `openspec validate --specs --strict` is the gate.

## Impact

**Deleted**
- `docs/design.md` (988 lines), `docs/sync-refactor.md` (239 lines) — `docs/` becomes empty.

**Specs (editorial — Purpose prose only; 44 scanned, 27 touched)**
- 19 `TBD` Purposes written: `apns-push-sender`, `backend-config`, `device-config-endpoint`,
  `download-store`, `event-album`, `event-creation`, `event-creation-ui`, `event-leave-endpoint`,
  `event-notify-endpoint`, `event-rejoin-reconciliation`, `full-stack-harness`, `ios-app-shell`,
  `ios-photokit-upload`, `ios-url-session-upload`, `join-event`, `photo-date-cutoff`, `photo-download`,
  `push-registration`, `upload-completion-notify`.
- 8 `docs/design.md` pointers replaced with `Decision record: changes/archive/<id>`:
  `sync-engine`, `sync-ledger`, `sync-status`, `gallery-status`, `bunny-upload-endpoint`, `bunny-list-endpoint`,
  `backend-deployment`, `harness-world-model`.
- "backup" → share/receive in 6 specs: `permission-gate`, `sync-status`, `sync-status-screen`,
  `photo-date-cutoff`, `ios-photokit-upload`, `event-creation-ui`.
- `device-manifest`: Purpose says `/events/<eventId>/device/<deviceId>.json` (singular) where the
  Requirements say `devices/` (plural).

**Code (comments only — no identifiers, no behavior)**
- KDoc `design.md §N` → owning spec in: `SyncEngine.kt` (×6), `UiState.kt` (×2), `SnapSyncRoot.kt` (×2),
  `UploadExtensionRoot.kt` (×2), `SyncProgress.kt`, `LedgerBackedSyncStatusSource.kt`, `RawAsset.kt`,
  `GalleryStatusSource.kt`, `ScreenLayout.kt`, `UploadLivenessNotification.kt`,
  `EdgeUploadRequestProvider.kt`, `JoinEvent.kt`, `QrGeneratorMain.kt`, `ConfigPorts.kt`,
  `ConfigDeeplink.kt`.
- Dead "backup" vocabulary in KDoc already being touched: `SyncProgress.kt`, `Ledger.kt`,
  `UploadKeys.kt`, `IosBackgroundScheduler.kt`.
- `app/desktop/build.gradle.kts`, `app/desktop/ui/build.gradle.kts`,
  `domain/ui/components/build.gradle.kts` — comment refs.

**Docs & tooling**
- `CLAUDE.md`, `app/ios/CLAUDE.md`, `backend/README.md`.
- `.claude/skills/openspec-archive-change/SKILL.md` — the placeholder-Purpose root cause.

**Not in scope**: renaming `SyncEngine` / `SyncStatus` / `SyncProgress` or the `sync-*` spec directories;
renaming the app (`app.snapsync`, `snapsync://`, `snapsync.stho.net`); a root README.

**Gates**: `./gradlew build` and `npx --yes @fission-ai/openspec@1.4.1 validate --specs --strict`.
