## Context

The backend is a stateless streaming proxy over a bunny native Storage zone (Deno + Hono,
`backend/src/app.ts`); object presence *is* the registry, there is no database. Today the storage
layout has two top-level namespaces:

```
events/<eventId>/metadata.json            ← event marker (existence)
events/<eventId>/device/<deviceId>.json   ← per-event device manifest (membership + assets)
files/<deviceId>/<filename>               ← a device's raw uploaded photo objects
```

The `files/<deviceId>/` prefix is addressed over the URL labels `/files/device/<deviceId>/…` for
both write (`PUT /files/device/<deviceId>/<filename>`) and read (`GET /files/device/<deviceId>`), and
the event-wide union (`GET /event/<eventId>/files`) lists each contributor's `files/<deviceId>/`
partition to compute completeness.

A follow-up change (`push-notification-infra`) needs a **second** per-device object — a device config
document holding the APNs push token — written and read by deviceId. There is no clean home for it in
the current layout: the natural spot `files/<deviceId>/config.json` sits inside the very prefix that
`GET /files/device/<deviceId>` enumerates as *photo objects*, so it would surface as a bogus asset in
the per-device list and poison the union's completeness computation.

This change carves out a single device-owned namespace so multiple device objects coexist without
colliding, and does so **now** — before the push change — so the push work builds on a clean layout
rather than threading around the old one.

## Goals / Non-Goals

**Goals:**
- Reserve `devices/<deviceId>/` as the device's own namespace, with photo bytes under
  `devices/<deviceId>/files/` and room for a `config.json` sibling later.
- Repoint the two device-scoped URL routes and the device-side URL builder to the new layout.
- Keep the change **behavior-preserving** apart from the moved paths: identical auth model, presigned
  URLs, completeness semantics, faithful-outcome rules, and last-write-wins.
- Ship as a small, reviewable, self-contained step (app + backend together).

**Non-Goals:**
- Adding the `config.json` object, any push-token endpoint, or any notification behavior — that is the
  follow-up `push-notification-infra` change.
- Migrating existing `files/<deviceId>/` objects or providing back-compat reads (clean cut, below).
- Changing the `events/<eventId>/…` namespace: the marker, the per-event device manifest, and the
  manifest write route `PUT /event/<eventId>/device/<deviceId>` are untouched.
- Changing the device manifest format: a resource's `key` is the per-resource filename/uploadKey (not
  a full storage path), so it is unaffected by the prefix move; `:domain:gallery` is not touched.

## Decisions

### Decision 1 — Namespace shape: `devices/<deviceId>/files/<filename>`

Photo bytes move to `devices/<deviceId>/files/<filename>` and the URL routes become
`PUT /devices/<deviceId>/files/<filename>` and `GET /devices/<deviceId>/files`. The URL label ordering
now mirrors the storage key exactly (`devices/<id>/files/…`), unlike today where the route
(`/files/device/<id>`) inverts the key (`files/<id>/…`).

**Alternatives considered:**
- *Keep `files/<deviceId>/` and put config elsewhere* (e.g. a top-level `config/<deviceId>.json`).
  Rejected: scatters device-owned state across unrelated top-level prefixes; the whole point is one
  device namespace.
- *`devices/<deviceId>/photos/…`* or other sub-label. `files` chosen to stay close to the existing
  vocabulary (the list route already speaks of "files") and the field name in list responses.

### Decision 2 — Clean cut, no migration, no back-compat

The old paths are removed outright; no dual-read window and no data migration. Rationale: the app is
pre-release (TestFlight, only throwaway personal data), the device is the **sole** client, and the app
and backend deploy from the same repo — so a synchronized cutover is trivial and back-compat would be
pure cost. Old objects under `files/<deviceId>/` are simply abandoned and may be wiped by hand in the
bunny zone.

**Alternative considered:** dual-read (list/union read both prefixes during a transition). Rejected —
no real data to preserve and no independent clients to stagger.

### Decision 3 — The union route path stays; only its internals move

`GET /event/<eventId>/files` keeps its path (it is event-addressed, and the `events/` namespace is
unchanged). Only its per-device fan-out is repointed: it LISTs `devices/<deviceId>/files/` and mints
presigned URLs against `devices/<deviceId>/files/<filename>` keys. This keeps the event-facing surface
stable while the device-facing byte layout moves.

### Decision 4 — The manifest resource `key` is unchanged

The device manifest names each resource by a `key` that is the per-resource upload filename
(`uploadKey`), compared at union time against the *decoded object names* returned by the device's LIST.
Both sides are the filename, independent of the storage prefix, so moving the prefix does not touch the
manifest schema, the gallery `uploadKey`/`assetIdFromUploadKey` logic, or the completeness comparison's
substance — only the LIST prefix it runs against.

## Risks / Trade-offs

- **[BREAKING device↔backend contract]** The byte upload and per-device list URLs change, so an old
  app build talking to a new backend (or vice-versa) fails. → Mitigation: ship app + backend together;
  no external consumers; pre-release. A stale sideloaded build is simply reinstalled.
- **[Stranded old objects]** Existing `files/<deviceId>/` objects become unreferenced after cutover. →
  Mitigation: accepted (throwaway data); wipe the old prefix manually in the bunny zone.
- **[Missed path reference]** A hardcoded old path left in a test fake or harness would pass compile
  but break at runtime. → Mitigation: grep the tree for `files/device` and `files/<` / `"files/"`
  usages; the backend `Deno.test` suite and the `:capability:upload-url` `commonTest` pin the new
  paths; verify with `./gradlew build` + `deno test`.

## Migration Plan

1. Backend: update route templates + storage-key helpers (byte upload, per-device list, union fan-out)
   in `backend/src/app.ts`; update `backend/**/*.test.ts` and `backend/README.md`.
2. Device: repoint `EdgeUploadRequestProvider` composed path + its `commonMain` tests.
3. Test infra: fix any `:test:world` / harness fake hardcoding old paths/keys.
4. Verify: `deno fmt/lint/check/test`, `./gradlew build`, `./gradlew compileIosMainKotlinMetadata`.
5. Deploy backend to `main` (DNS/runtime unchanged); the paired app build follows the normal TestFlight
   path. No rollback coordination needed beyond redeploying the previous bundle + reinstalling the app,
   since there is no persisted data contract to preserve.

## Open Questions

- None. The `config.json` object, push-token delivery, and notification behavior are deliberately
  deferred to `push-notification-infra`; this change only reserves the namespace they will use.
