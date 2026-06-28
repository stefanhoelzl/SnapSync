## Context

After Change 1, three observable facts fully describe an asset's sync state without the ledger:
storage holds the **complete** assets (the list endpoint computes this from manifests), the App Group
holds the **in-flight** manifests (one file per asset the extension has started and not yet confirmed),
and PhotoKit holds the **total**. Today the app instead reads the cross-process App-Group SQL ledger via
`LedgerWatcher` and corrects its lag with `observed-completion-overlay` (it reads the platform's
succeeded-but-unacknowledged upload jobs and promotes those photos). That machinery exists *only*
because the ledger trails real upload success. Reading storage truth instead makes it obsolete.

## Goals / Non-Goals

**Goals:**
- App derives `SyncStatus` from the completeness listing + on-disk manifests + PhotoKit, no ledger read.
- Keep the `SyncStatus`/`SyncProgress` contract and three-state classification identical — only re-source
  `completed` and `pending`.
- Delete `observed-completion-overlay`; make the ledger extension-private; drop `:domain:status`→`:domain:engine`.

**Non-Goals:**
- Changing the storage layout, manifest format, or the list endpoint (all Change 1).
- A download/restore client.
- A polling refresh timer (see Open Questions — liveness is event-driven only).

## Decisions

### D1 — Status from storage truth, not the ledger
`completed` = the count of complete assets returned by `GET /event/<id>/files`; `total` = the
`GalleryStatusSource` count (unchanged); `pending` = the count of App-Group manifest files whose asset is
not yet in the completed listing (the "in flight" set). Classification is unchanged: `n = min(completed,
total)`, `NOTHING_TO_SYNC | COMPLETE | IN_PROGRESS`. *Alternative rejected:* keep the ledger watcher and
overlay — they reintroduce the cross-process coupling and lag this change removes.

### D2 — Two new seams in `:domain:status`
`CompletedAssetsSource` exposes the complete-asset set/count (backed by the Change-1 `EventFilesSource`
HTTP listing on iOS, a settable fake on JVM) and refreshes on **foreground entry** and on **each manifest
`URLSession` completion**. `PendingManifestsSource` reads the App-Group manifest directory (iOS) / a fake
(JVM) for the in-flight set and **prunes** files whose asset is now complete. The listing-backed
`SyncStatusSource` combines these with `PermissionStatusSource` and `GalleryStatusSource` — the same
combine shape as today, minus the `LedgerWatcher` and `ObservedCompletionsSource` inputs. *Result:*
`:domain:status` no longer depends on `:domain:engine`.

### D3 — Liveness is event-driven, completion observed locally
A background manifest upload completes into the **app** via `handleEventsForBackgroundURLSession`; that
event (a) triggers a re-LIST (a manifest landing usually means an asset just became complete) and (b) lets
the app prune that on-disk manifest. No timer. *Alternative rejected:* a foreground polling interval
(as the deleted overlay used) — unnecessary churn for a status display; foreground re-entry already
refreshes.

### D4 — App stops watching the ledger (full privatisation is Change 3)
Remove the status-facing `LedgerWatcher` and the cross-process Darwin notification; keep the
reader/writer/`aggregates()`/record/reset operations the extension's own cycle uses (e.g. the
`pending > 0 → PROCESSING` check, rejoin's `resetTo`). The app stops **reading** the ledger for status,
but still seeds via `resetTo` on rejoin — relocating that seed into the extension (so the app touches no
ledger type at all) is the follow-on change `reconcile-in-extension`. *Alternative rejected:* deleting
the ledger outright — it remains the extension's private upload-dedup memory.

### D5 — App prunes as a backstop
The extension prunes a manifest file on its own observed upload-success (Change 1); the app prunes on the
listing as a backstop for completions delivered to the app while the extension was suspended. Both writers
target the shared App Group; deleting a file whose asset is already complete is idempotent.

## Risks / Trade-offs

- **Liveness gap** → if the app is backgrounded when an asset's *last resource* lands **after** its
  manifest, the asset becomes complete with no app-visible event, so `completed` is stale until the next
  foreground re-LIST. Mitigation: accepted — status is a display; foreground refreshes it. (Open Question.)
- **Listing cost on large/shared events** → the app LISTs on foreground/manifest-completion. Mitigation:
  the endpoint caches complete assets (immutable), so repeat LISTs are cheap server-side.
- **Two pruners on the App Group** → extension + app both delete manifest files. Mitigation: deletion is
  idempotent and only ever happens once an asset is confirmed complete.
- **Loss of instant promotion** → the overlay made a just-succeeded photo tick over before any persisted
  read. Mitigation: the manifest-completion re-LIST covers the common case; the residual is the gap above.

## Migration Plan

Apply after Change 1 is archived. Net deletions (overlay, ledger watcher, cross-process ding, app ledger
construction) are removed in one change; the `SyncProgress` contract is stable so presentation/UI is
unaffected. Rollback is reverting the change (restores the ledger-backed source and overlay).

## Open Questions

- **Liveness cadence (D3/Risk) — decided: accept for v1.** Refresh is event-driven only (foreground entry
  + manifest-`URLSession` completion); no polling timer. The documented **fast-follow** if smooth
  continuous-watch progress proves wanted: a bounded foreground re-LIST timer (while foreground and
  pending > 0) backed by an **ETag/304** on the list endpoint so unchanged polls are near-free.
