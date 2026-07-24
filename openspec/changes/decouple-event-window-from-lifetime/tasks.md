## 1. Backend — configuration and lifecycle rules

- [x] 1.1 In `api/src/config.ts`, split `EVENT_DURATION_SECONDS` into `EVENT_WINDOW_MAX_SECONDS` and `EVENT_LIFETIME_SECONDS` (both 30 days), delete `EVENT_GRACE_SECONDS`, and update the `Config` type and `sourceConstants()` accordingly
- [x] 1.2 Delete `ENV_ADMIN_NOTIFY_KEY`, `Config.adminKey`, and their validation from `readConfig`, `readSweepConfig`, and `storageConfig`
- [x] 1.3 In `api/src/storage.ts`, add `lifetimeSeconds: number` to `EventMarker` and as optional on `StoredEventMarker`; update the doc comment (the marker's limits are no longer `endsAt = startsAt + duration`)
- [x] 1.4 In `api/src/lifecycle.ts`, add a `deleteByMs(marker, config)` derivation: parse `createdAt` and `startsAt` to epoch ms (NOT a lexicographic compare — `createdAt` is not in canonical cutoff form), anchor at their max, add `lifetimeSeconds` or the configured fallback when the marker carries none
- [x] 1.5 Replace `classifyEvent`'s `live`/`grace` split with a completeness check returning the narrowed marker or `gone`
- [x] 1.6 Rewrite `eventIsStale` to deadline-or-empty: past `deleteByMs`, or the `devices/` listing is non-empty with no device resolving to `active` (via `resolveMembership`), or the marker is incomplete — with an empty listing explicitly NOT stale
- [x] 1.7 Add `commonTest`-equivalent unit tests for the anchor: a back-dated marker (`startsAt` before `createdAt`), a created-early marker (`startsAt` after `createdAt`), and a legacy marker with no `lifetimeSeconds`

## 2. Backend — routes

- [x] 2.1 In `api/src/validators.ts`, extend `validateEndsAt` to reject `endsAt - startsAt > windowMax`, and replace the "no upper-duration cap" rationale comment with the silent-loss reason
- [x] 2.2 `POST /events`: stamp `lifetimeSeconds` on the marker; include the derived `deletesAt` in the `201` body
- [x] 2.3 `GET /events/:eventId`: serve the derived `deletesAt`; drop the on-touch reap and the expiry gate; answer `404` only for an absent or incomplete marker
- [x] 2.4 Delete the `410` grace branch from the device-manifest write path in `api/src/app.ts`, leaving only the `409` capacity refusal
- [x] 2.5 `POST /events/:eventId/notify`: accept a device token only; remove the admin-key authorization branch
- [x] 2.6 Update `api/test/app.test.ts`, `api/test/attest.test.ts`, and any fixture asserting `410`, grace, or the admin key

## 3. Backend — nightly sweep

- [x] 3.1 In `api/src/sweep.ts`, feed each event's `devices/` listing into the staleness predicate so emptiness is evaluated in the event phase (the listing is already read there)
- [x] 3.2 Remove `SweepDeps.notify`, the notify-before-delete ordering, and the "failed notify does not block" handling
- [x] 3.3 Remove the admin key from the sweep's config and from the sweep workflow's secrets; confirm the sweep makes no request to the Edge Script
- [x] 3.4 Update `api/test/sweep.test.ts`: past-deadline deletes, emptied deletes, never-joined survives, one-active survives, incomplete deletes, no notify dispatched

## 4. Retire the admin secret (operator, after the backend deploy)

- [ ] 4.1 Once no deployed code reads it, delete the `ADMIN_NOTIFY_KEY` GitHub Actions secret and remove the variable from the Edge Script environment

## 5. Client — model

- [x] 5.1 Add `deletesAt: String?` to `EventConfig` with a default-decode so pre-existing configs parse; treat absent as "never reached"
- [x] 5.2 Add the pure `confirmedGone(deletesAt, now)` predicate to `model/` beside `clampToFloor`/`clampToCeiling`, with `commonTest` coverage (absent, before, exactly at, after)
- [x] 5.3 Add `deletesAt` to `JoinLoad.Found` and `EventDetails.Found`, and parse it in `HttpEventDirectory`; a `200` lacking it is a `Failed` outcome, matching the existing treatment of a missing `startsAt`

## 6. Client — feature, flow, composition

- [x] 6.1 Rename `EventName` to the need-named membership-refresh rule and change `storeRefreshedDetails` to return a sealed three-arm outcome (refreshed / inconclusive / absent), with the absent arm gated on `confirmedGone` against the persisted `deletesAt`
- [x] 6.2 Extend the same rule's backfill to fill `deletesAt` alongside `endsAt`/`maxPhotoDate` in the one whole-config save
- [x] 6.3 Widen `compose/`'s `fetchEventDetails` effect from `suspend (String) -> JoinLoad.Found?` to carry the sealed result, replacing the `as? EventDetails.Found` flattening with `toJoinLoad()`
- [x] 6.4 Add a `compose/`-built leave effect and have `Foreground` and `Provision` switch on the sealed outcome, firing it identically on the absent arm
- [x] 6.5 Update the `join-event` name-refresh call sites and `SnapSyncApp` wiring for the rename
- [x] 6.6 Add `commonTest` coverage of the two-witness matrix: 404 + past deadline leaves; 404 + before deadline keeps; failed + past deadline keeps; 404 + absent deadline keeps; stale event id keeps
- [x] 6.7 Add coverage that no background trigger flow can reach the teardown

## 7. Client — UI

- [x] 7.1 Render the deadline date and the fixed 30-day ceiling statement on the join gate's loaded phase, before the confirm action
- [x] 7.2 Extend the join-surface screen tests for the new line; verify offscreen via `:test:harness-driver` if the world harness covers the gate

## 8. Reconciliation and integration

- [x] 8.1 Extend the reconcile backfill path so a membership missing `deletesAt` is filled from the details fetch, and a `404` still skips the backfill
- [x] 8.2 Add a `:test:integration` case over `:test:world`: an event deleted underneath an active member returns the device to the unjoined state on the next foreground, and does not on a transient failure

## 9. Prose that currently states the coupled lifetime

- [x] 9.1 `openspec/config.yaml` line ~65 — the mission context injected into every agent in this root still says the event's end is "the server-stamped lifetime whose expiry deletes the event"
- [x] 9.2 `CLAUDE.md` — the mission paragraph carries the same claim
- [x] 9.3 `api/README.md` — the live/grace/expired walkthrough, the `410` row in the route table, and the `endsAt = startsAt + 30 days` mint description
- [x] 9.4 `openspec/specs/event-leave-endpoint/spec.md` Purpose — remove the last-active-member reap and the "no periodic reaper" stance, both already contradicted by the code and by `scheduled-cleanup`
- [x] 9.5 `openspec/specs/event-limits/spec.md` and `scheduled-cleanup/spec.md` Purposes — rewrite for the decoupled model and cite this change as the decision record
- [x] 9.6 `site/src/pages/index.astro` — rewrite the "How long we keep photos" statement to lead with the 30-day ceiling and present early reclamation as "often sooner", never as a condition on the date

## 10a. Status-line layout (raised on review of the on-device screenshot)

- [x] 10a.1 Render the "Event ended" marker on its own line above the status instead of as an inline `Event ended · <status>` prefix, styled subordinate to the health it labels
- [x] 10a.2 Update the `sync-status-screen` delta — the prior requirement pinned the single slot ("the joined layer never grows a second status line")
- [x] 10a.3 Screen tests assert the exact marker text AND that the inline `Event ended ·` form is gone (a substring assertion would pass either way)

## 10. Verification

- [x] 10.1 `deno test` for `api/` passes
- [x] 10.2 `./gradlew build` passes (all targets + JVM tests, including `:test:architecture`)
- [x] 10.3 `./gradlew compileIosMainKotlinMetadata` passes (the Linux-runnable iOS proxy)
- [x] 10.4 `./gradlew architectureDiagrams` run and committed if the module graph moved
- [x] 10.5 Backend smoke over the local rig (`deno task dev:local` — the real app, real routes, real gates, filesystem store): a 30-day window is accepted and 30d+1s is refused `400`; `deletesAt` is served and anchored at `max(createdAt, startsAt)`; a NEVER-SEEN device enrolls `201` eighteen days after the window closed (the old code's `410`); a past-window event still serves details and union; capacity is the only refusal (`409` at 10, never `410`); the retired admin key is `401`
- [x] 10.6 On-DEVICE smoke — the part the rig cannot reach: point a build at `deno task dev:tunnel` via ssh-mac (`BACKGROUND_UPLOAD_URL_BASE`), launch with `SNAPSYNC_RESET_STATE=1`, join an event whose window has already closed, and confirm the in-window photos on the device actually upload (PhotoKit walk + selection policy + upload extension). Also eyeball the join gate's retention line on a real screen.

## 11. Follow-up found by the on-device smoke (PRE-EXISTING — not introduced here)

- [x] 11.1 **Fixed and verified on device.** `UploadCycle` handed the device-manifest projection the **floor only** (`onDiscovery(eventId, cutoff, manifestDiscovery)`), so the capture-date **ceiling** never reaches it. The byte filter applies both bounds, so an over-ceiling photo is listed in `device.json` and counted in the status total `N` but its bytes are never uploaded — the screen pegs below 100% forever ("Synchronization pending…"). Measured on device: a 28-asset manifest with 2 assets past `endsAt`, 26 bytes uploaded, status stuck. The union masks the leak only because it projects COMPLETE assets. This is the exact failure `photo-selection-policy` names ("the same policy gates the byte upload, the device manifest, AND the status total N") and the same shape as the origin-exclusion bug the comment above that line already records. Present identically on `origin/main` — introduced by `2026-07-22-add-event-date-range`, which added the ceiling to the byte filter and not to the projection. Fix in its own change.

      The fix landed in `introduce-eventphotoset` (one admitted set, so the ceiling reaches every consumer
      by construction) and `introduce-candidate-source` (the manifest projects the ledger). Re-run on the
      SE2 against a closed window holding in-window photos: `N=8` of 48 candidates, and `device.json`
      carries no post-ceiling asset.
