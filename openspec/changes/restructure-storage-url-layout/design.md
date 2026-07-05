## Context

The backend is a streaming proxy (`backend/src/app.ts`, Hono/Deno) fronting one bunny native
Storage zone. Its URL surface and storage keys grew three shape inconsistencies:

- **Byte vs. control addressing.** Byte objects live at `devices/<id>/files/<name>`, the config
  document at `devices/<id>/config.json`, and the per-event manifest at `events/<id>/device/<id>`.
  There is no single "here are the blobs" prefix.
- **Singular/plural drift.** The storage prefix is `events/` (plural) but every URL is `/event/`
  (singular); the per-event manifest sub-prefix is `device/` (singular) while its parent `events/`
  and the byte partition `devices/` are plural.

The device is the only client, and it is pre-release (TestFlight, throwaway data). All URL/key
construction is centralized: the backend builds keys through five helper functions and mounts eight
routes; each Kotlin client builds its one path in a single call-site; the `:test:world` `MiniEdge`
route-matches by path segments. A near-identical precedent (`2026-07-04-device-namespace-reorg`)
moved the byte store into the `devices/` namespace as a clean, no-migration relocation.

## Goals / Non-Goals

**Goals:**

- One `files/` prefix for all byte objects; a flat `devices/<id>.json` for config; consistent
  pluralization across every URL and the manifest sub-prefix.
- A purely mechanical rename: identical gating, faithful-outcome, completeness, presigned-download,
  and trust semantics before and after.
- Keep the URL↔key relationship the code already has (keys derived from route params via helpers),
  so the presigned-URL builder and validation need no logic change.

**Non-Goals:**

- No byte-download proxy. Downloads stay presigned-S3-direct; `GET /files/devices/<id>` remains a
  *listing* (no filename), not a byte fetch.
- No nested filenames. The filename stays a single flat segment; `validateFilename` is unchanged
  (rejects `/`, `%2F`, `..`).
- No migration/dual-read/back-compat. Old objects are abandoned and wiped.
- No change to the event-marker key (`events/<id>/metadata.json`), the config-read HTTP surface
  (there is none — notify reads config from storage internally), or the iOS app-shell wiring.

## Decisions

- **Full pluralization of every event URL (`/event/*` → `/events/*`), not just the manifest
  sub-prefix.** The storage prefix is already `events/`; aligning the URL noun removes the last
  singular/plural split. *Alternative considered:* rename only the manifest `device/ → devices/` and
  leave `/event/*` singular — rejected: it would leave the URL/storage noun mismatch the change set
  out to fix.
- **`GET /files/devices/<id>` stays a listing; the download proxy stays retired.** The `...` in the
  original sketch is loose notation for the directory. Reintroducing an edge byte-proxy would
  reverse a deliberate decision (iOS zero-window SYN avoidance, no edge download bandwidth) for no
  benefit. *Alternative considered:* a per-file byte GET — rejected.
- **Config becomes a flat sibling `devices/<id>.json` at `PUT /devices/<id>`, no read route.** With
  bytes moved under `files/`, the `devices/` prefix is free to hold only flat config documents; a
  flat key avoids a one-object subdirectory. No client reads config over HTTP (notify reads it from
  storage), so no `GET` is added. Route collision is clear: `PUT /devices/<id>` (2 segments) has no
  sibling under `/devices/` once bytes/listing move to `/files/devices/…`.
- **Keys stay derived from route params through the five helpers.** Only the helper bodies and the
  route/mount strings change; `presignDownloadUrl` composes over `byteKey` and so follows for free.
  The byte WRITE route stays a mounted child Hono, remounted at `/files/devices/:deviceId/:filename`;
  the listing is a separate `GET /files/devices/:deviceId` (different depth — no overlap with the
  4-segment mount). *Alternative considered:* collapse the mount into a flat route — unnecessary
  churn.
- **Clean cut via `scripts/reset-storage.ts`.** Pre-release throwaway data makes migration code pure
  cost. Old keys are simply abandoned and wiped; the app and backend ship together.
- **Spec deltas only where normative text changes.** Client specs that pin a concrete URL
  (`edge-upload-provider`, `event-creation-ui`, `photo-download`, `push-registration`,
  `deeplink-config`, `ios-photokit-upload`, `harness-world-model`) get deltas; specs that reference a
  path only abstractly or historically (`device-identity`, `event-rejoin-reconciliation`) do not —
  their incidental prose is refreshed for accuracy without a requirement change, mirroring the
  precedent's discipline.

## Risks / Trade-offs

- **[Coordinated breaking cut across app + backend.]** Every device↔backend URL changes at once. →
  The device is the sole client and pre-release; ship app and backend together, wipe the zone, and
  point the dev loop at a fresh event id. No external consumers to break.
- **[A missed call-site leaves a route 404-ing silently.]** → The call-sites are enumerated (backend
  helpers + 8 routes, 6 Kotlin clients, `MiniEdge`); `:test:integration` exercises the full
  `seam → world → UiState` path over the renamed `MiniEdge`, and `backend/test` asserts the exact
  upstream keys, so a stale path fails a test rather than only failing on-device.
- **[Route-collision regressions from the reshaped `/devices/` and `/files/devices/` trees.]** → The
  listing (3 segments) and byte mount (4 segments) differ in depth; `PUT /devices/:id` is alone under
  `/devices/`; `/events/:id/files` (literal `files`) and `/events/:id/devices/:id` (literal
  `devices`) do not overlap. Backend tests cover the 404/400 fall-through cases.
- **[Stale prose in non-delta specs/docs.]** → `device-identity` Purpose and
  `event-rejoin-reconciliation`'s historical mention are refreshed in the same change; `docs/design.md`
  §3–4 and `backend/README.md` updated alongside.

## Migration Plan

1. Land backend (`app.ts` helpers + routes + header doc), `backend/test`, and `backend/README.md`.
2. Land the six Kotlin client call-sites, `:test:world` (`MiniEdge` + manifest uploader + comments),
   and the `EdgeUploadRequestProvider` URL test.
3. Update the 13 capability spec deltas + incidental prose + `docs/design.md` §3–4.
4. Verify: `deno task test` + `deno fmt --check`/`lint`; `./gradlew build`;
   `compileIosMainKotlinMetadata`; `openspec validate --specs --strict`.
5. Deploy backend, wipe the zone via `scripts/reset-storage.ts`, install the matching app build, and
   drive the headless dev loop against a fresh event id to confirm an upload lands under
   `files/devices/<id>/…`. **Rollback:** revert the branch and redeploy; no persisted state depends
   on the new keys (zone is disposable).

## Open Questions

None — all decisions were resolved in the design interview (GET stays a listing; full
pluralization; flat config with no read route; single flat filename; clean-cut migration; full
OpenSpec + docs + all clients).
