## 1. Backend: extract shared modules (no behavior change)

- [x] 1.1 Extract storage helpers (LIST/PUT/DELETE, key builders `markerKey`/`deviceManifestDir`/`deviceConfigKey`/`deviceAttestKey`/byte-partition path) from `backend/src/app.ts` into an importable module (e.g. `src/storage.ts`), parameterized by `(fetch, config)`
- [x] 1.2 Extract lifecycle + membership logic (`classifyEvent`, `resolveMembership`, `StoredEventMarker`) into `src/lifecycle.ts`
- [x] 1.3 Rewire `app.ts` to import from the new modules; confirm `deno check`/`deno test` green with no behavior change

## 2. Backend: lifecycle collapses to two states

- [x] 2.1 Change `classifyEvent` to return only `live` (`now <= endsAt`) / `grace` (`now > endsAt`); legacy markers (missing `endsAt`) classify as `grace`
- [x] 2.2 Remove the on-touch expiry reap from `gateEvent` (no delete on read); expired-but-unswept events now serve under grace rules
- [x] 2.3 Delete `reapExpiredEvent` and its call sites
- [x] 2.4 Update `event-limits` backend tests to the two-state model (no reap-on-touch; grace open-ended)

## 3. Backend: leave becomes rename-only

- [x] 3.1 In `DELETE /events/:eventId/devices/:deviceId`, remove the last-active-member reap and its GC; return `200` after the departed rename regardless of remaining membership
- [x] 3.2 Delete `gcDeviceIfUnreferenced` and `deviceAppearsInAnotherEvent` (no remaining caller)
- [x] 3.3a Update leave backend tests (Deno) — assert rename-only, event persists, nothing collected
- [x] 3.3b Update `LeaveCascadeWorldTest` (`:test:world`, Kotlin) — done with Group 7 (shared toolchain)

## 4. Backend: ADMIN_NOTIFY_KEY notify auth + config

- [x] 4.1 Add `ADMIN_NOTIFY_KEY` to `config.ts`'s fail-closed secret read (fourth required Edge Script env secret)
- [x] 4.2 In `POST /events/:eventId/notify`, accept the `ADMIN_NOTIFY_KEY` bearer as an alternative to a device token; keep the `401`-before-existence gate; ensure the admin key authorizes no other route
- [x] 4.3 Tests: admin-key notify fans out; admin key rejected on other routes; missing `ADMIN_NOTIFY_KEY` fails boot

## 5. The nightly sweep (backend Deno script)

- [x] 5.1 New sweep entry point in `backend/` importing the shared `storage`/`lifecycle`/`config` modules; reads target the storage main region
- [x] 5.2 Event phase: LIST events, delete those with `now > endsAt + grace` (incl. legacy markers) — notify via `ADMIN_NOTIFY_KEY` first (best-effort), then delete marker + all manifests
- [x] 5.3 Asset phase (against surviving events): build the referenced-key set (active `.json` + `.left.json`) and each device's active-event `min(startsAt)` floor; collect a byte iff unreferenced AND `DateCreated < floor` (floor over ∅ = +∞)
- [x] 5.4 Asset phase: collect `devices/<id>.json` + `devices/<id>.attest.json` for devices in no surviving event
- [x] 5.5 `--dry-run` (log candidates, no delete); best-effort per-object deletes (log-and-continue, idempotent); run summary; exit non-zero only on systemic/auth failure
- [x] 5.6 `deno test` coverage of both predicates (stale-event, stale-asset incl. the min-startsAt floor and the +∞ orphan case)

## 6. Scheduled workflow

- [x] 6.1 New `.github/workflows/nightly-cleanup.yml`: nightly cron + `workflow_dispatch`, running the sweep with `BUNNY_STORAGE_ACCESS_KEY` + `ADMIN_NOTIFY_KEY` from GH secrets
- [x] 6.2 Header docs: non-gating, dry-run posture, why it runs outside the edge (scheduler + subrequest caps)

## 7. Client: register push on join

- [x] 7.1 Trigger `PushRegistration` on join (event provision) in `:domain` (`feature/push` / the join flow), in addition to launch and rotation — not on foreground
- [x] 7.2 `commonTest`: registration fires on join (JVM + `iosSimulatorArm64`); idempotent re-registration is harmless
- [x] 7.3 Wire the join-time trigger through `:app:ios` (`SnapSyncRoot`) as wiring-only

## 8. Docs, ops, and hygiene

- [x] 8.1 Fix `backend/README.md` stale `ZONE = snap-sync` → `snap-sync-dev`
- [x] 8.2 Runbook note: set `BUNNY_STORAGE_ACCESS_KEY` + `ADMIN_NOTIFY_KEY` (GH secrets) and `ADMIN_NOTIFY_KEY` (Bunny edge env) BEFORE merge; validate `--dry-run` on the dev zone first
- [x] 8.3 `./gradlew build` green (architecture guards, JVM + offscreen UI tests); `deno fmt`/`lint`/`check`/`test` green; `npx @fission-ai/openspec@1.5.0 validate --specs --strict` green
