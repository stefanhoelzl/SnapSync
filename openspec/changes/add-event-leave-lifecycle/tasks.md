## 1. Prerequisite

- [x] 1.1 Confirm `restructure-storage-url-layout` is merged to `main` and rebase this branch onto it (all paths below assume the post-reorg layout: `events/<E>/devices/<D>.json`, `files/devices/<D>/`, `devices/<D>.json`)

## 2. Backend — membership primitives (LWW)

- [x] 2.1 Add `LastChanged` to the `BunnyEntry` type (app.ts:~127) and stop discarding it in `listDir`
- [x] 2.2 Add a membership resolver over a `events/<E>/devices/` listing: group siblings by deviceId, classify each `active`/`departed` by last-write-wins on `LastChanged` (tie → active), exposing "active device ids" and "all (winning) manifests"
- [x] 2.3 Add helpers `deviceLeftManifestKey(eventId, deviceId)` and an event-tree lister (`events/<E>/**`) alongside the existing key helpers

## 3. Backend — leave endpoint + cascade

- [x] 3.1 Add `DELETE /events/:eventId/devices/:deviceId`: UUID validation (400), event-marker gate (404), method/path fall-through (404)
- [x] 3.2 Implement the departed rename: read `<D>.json`, `PUT <D>.left.json` (fresh timestamp, read-then-PUT — never a metadata-preserving copy), then delete `<D>.json`; idempotent when already departed
- [x] 3.3 Implement the last-active-member reap: LIST `events/<E>/devices/`, and if no active member remains (2.2), delete the whole `events/<E>/` tree (marker + all manifests)
- [x] 3.4 Implement reference-checked GC: for each freed device, check all surviving events for any `<D>.json`/`<D>.left.json`; if none, delete every object under `files/devices/<D>/` (per-object loop) and `devices/<D>.json`
- [x] 3.5 Ensure the whole handler is idempotent and leak-safe (write `.left.json` before deleting `.json`; deletes of absent objects are no-ops)

## 4. Backend — union & notify LWW

- [x] 4.1 Union (`GET /events/<E>/files`): enumerate both `<D>.json` and `<D>.left.json`, count each device once via the LWW winner (2.2), read the winner's manifest; departed devices stay in the union
- [x] 4.2 Notify (`POST /events/<E>/notify`): target LWW-**active** members only, excluding departed devices from the fan-out

## 5. Backend — tests & docs

- [x] 5.1 `backend/test`: DELETE route (validation/gate/fall-through); rename; reap; reference-checked GC (orphan collected vs still-referenced retained); idempotent duplicate DELETE; partial-rename leak-safety
- [x] 5.2 `backend/test`: union includes a departed device; both-siblings counted once (LWW); notify excludes departed; a fresh-`.json` rejoin wins over an older `.left.json`
- [x] 5.3 Update `backend/README.md`: the leave/GC contract, and that the reap's active-member LIST + GC surviving-event checks depend on the existing main-region read-after-write invariant (never a replica endpoint)

## 6. Device — module rename

- [x] 6.1 Rename `:capability:rejoin` → `:capability:membership` (`settings.gradle.kts`, directory, `build.gradle.kts`, package/imports across dependents); `./gradlew build` green
- [x] 6.2 Refresh incidental module-name prose in `event-rejoin-reconciliation` spec/docs references

## 7. Device — leave notifier & use-case

- [x] 7.1 Add `LeaveNotifier` interface + `HttpLeaveNotifier` (`DELETE /events/<E>/devices/<D>`, returns `Result`, never throws) in `:capability:membership`, mirroring `HttpDeviceFilesSource`; `commonTest` over a fake HTTP client
- [x] 7.2 Extend `LeaveEvent` with the injected best-effort notify step; order `disable producer → notify → clear config`, reading `eventId`/`deviceId` before clear; update `LeaveEventTest` (order + failed-notify still clears)

## 8. Device — switch fires leave

- [x] 8.1 In the provisioning path (`deeplink-config`/`:capability:config`), detect a different-event provision, fire the best-effort `LeaveNotifier` for the previous `eventId` before persisting the new config; same-event provision stays a no-op; tests for switch vs re-provision vs failed-leave-still-switches

## 9. Device — app wiring

- [x] 9.1 Wire `HttpLeaveNotifier` (deviceId + main-app HttpClient) into `SnapSyncRoot`: the explicit Leave button and the switch path both call it; `:app:ios` stays wiring-only

## 10. Harness — world model

- [x] 10.1 Extend the `:test:world` MiniEdge to answer `DELETE /events/<E>/devices/<D>` with the full cascade (rename → reap → reference-checked GC) over the in-memory store; apply LWW to its union/notify read-models
- [x] 10.2 Update the `leave()` composition helper to run the real backend notify against the MiniEdge (retain imports + ledger; reflect world outcome)

## 11. Integration tests

- [x] 11.1 `:test:integration`: leave with other members present → device's manifest renamed departed, union still serves its assets, event intact
- [x] 11.2 `:test:integration`: last active device leaves → event tree deleted + orphaned byte partition/config GC'd; a device still in another event keeps its bytes
- [x] 11.3 `:test:integration`: switch (provision B while in A) leaves A on the backend; re-scan after leave re-joins and reconciles (no re-upload) via a fresh `.json` superseding the `.left.json`

## 12. Docs & verification

- [x] 12.1 Rewrite `docs/design.md` §3/§3.2 from "leave is local-only" to the event-leave lifecycle, incl. the leak-safety + main-region-consistency risk notes
- [x] 12.2 `openspec validate add-event-leave-lifecycle --strict`; `./gradlew build`; `compileIosMainKotlinMetadata`; `deno task test` + `deno fmt/lint`
- [ ] 12.3 Deploy backend + app together, wipe the zone, drive the headless dev loop: join → leave → confirm the event tree + byte partition are gone in the bunny zone
