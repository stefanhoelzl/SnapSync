## Context

The backend (`backend/`, Deno + Hono) is a write-only streaming proxy: the iOS background-upload
extension `PUT`s photo bytes to `/event/<eventId>/device/<deviceId>/file/<filename>`, and the
endpoint streams them into a bunny **native** Storage zone under the bare key
`<eventId>/<deviceId>/<filename>`. Authorization is possession of the event UUID alone — no token,
no registry (`docs/design.md` §4). The same `createApp` Hono app is served on both bunny Edge
Scripting and Deno Deploy (the current device-facing host); `main.ts` only picks the binding.

Two facts about the on-device key scheme drive this design:

- **`filename` is stable across a reinstall.** It is `<localIdentifier>-<resourceKind>.<ext>`
  (`UploadKeys.uploadKey`), where `localIdentifier` is the PHAsset's library identity — a property
  of the Photos library, not the app, so it survives an uninstall/reinstall and is globally unique
  per asset.
- **`deviceId` is NOT stable across a reinstall.** `IosDeviceIdStore` persists it in the App-Group
  `NSUserDefaults`, which is wiped on uninstall, so a re-joined device mints a fresh one. Its prior
  uploads remain under the **old** `deviceId`.

So the eventual consumer — a device pre-seeding its ledger on re-join — must enumerate **all**
objects for the event, across **every** device, and match by the `filename` segment. Filenames
embed a globally-unique `localIdentifier`, so cross-device filename collision is effectively
impossible; matching across devices is safe. This proposal builds only that read surface.

## Goals / Non-Goals

**Goals**
- A read-only `GET /event/<eventId>/files` that returns every stored object for the event, flat
  across all devices, authorized by the event id alone.
- Faithful results: never a partial list reported as complete.
- Zero new infrastructure, credentials, CI, or deployment changes.

**Non-Goals**
- The on-device ledger pre-seed / reconciliation that consumes this (separate later change).
- Pagination or large-event caps; `contentType`; CORS/OPTIONS; a per-device sub-route.
- An event registry (so unknown vs empty stays indistinguishable — see Decisions).
- Any event-metadata resource at `GET /event/<id>` (path left free for it).

## Decisions

### Route: `GET /event/<eventId>/files`
A sub-collection under the event, not the event resource itself, so `GET /event/<id>` stays
available for a future event-metadata resource. `files` (not `images`) is faithful: the zone also
holds videos, paired videos, audio, and adjustment-data resources. Defined on the existing Hono app
so it ships to both deploy targets unchanged. `eventId` is validated with the existing
`validateUUID`; a malformed id → `400`; any other method or unmatched path → `404` (Hono default,
exactly as the upload route behaves — Hono does not emit `405`).

### Cross-device aggregation via a per-directory walk
bunny native Storage List Files (`GET https://<host>/<zone>/<path>/`) is **per-directory, not
recursive** — it returns the files and sub-directories directly under `path`, each tagged
`IsDirectory`. So the handler:

```
  GET <host>/<zone>/<eventId>/                     ← 1 call
       └─ take entries with IsDirectory == true  → deviceIds
  GET <host>/<zone>/<eventId>/<deviceId>/          ← one call per device
       └─ entries with IsDirectory == false      → files
  flatten all files → one JSON array
```

The listing paths carry a **trailing slash** (bunny treats that as a directory listing). Under our
key scheme `<eventId>/` contains only device directories, so filtering to `IsDirectory == true`
yields the deviceIds. The `AccessKey` header (env config, already injected) authorizes each LIST.
Fan-out cost is `1 + deviceCount` subrequests — trivial for a personal backup (≈1–3 devices).

### Entry shape: `{ filename, deviceId, size, lastModified }`
Mapped from bunny's `ObjectName` → `filename`, `Length` → `size`, and the last-modified timestamp.
`deviceId` is the directory the file was listed under (not a bunny field). `contentType` is
**dropped**: bunny's canonical List Files OpenAPI schema returns `ObjectName/Length/LastChanged/
IsDirectory/...` with **no** `ContentType` (only a newer doc variant shows it), and the consumer
only needs `filename`. The full storage key is omitted too — derivable from `eventId` (caller-known)
+ `deviceId` + `filename`.

### Empty / unknown event → `200 []`
There is no registry, and bunny has no real directories: a never-created event and an event with
zero uploads are the **same** storage state (no objects under the prefix; a device dir exists only
once it has files). The backend cannot distinguish them, and "valid event, nothing uploaded yet" is
the normal first-join case the consumer hits. So any valid event id with no objects → `200 []`. The
only id-level rejection is malformed (non-UUID) → `400`.

### Faithful outcome: `502` on any sub-failure, never partial
Mirrors the upload endpoint's faithful-outcome rule. If the top-level list or **any** per-device
list fails (upstream error, timeout), the whole request → `502`; the handler never returns a
partial array and never a `2xx` for an incomplete walk. (A silently-truncated list would make the
future consumer re-upload — harmless but wasteful — but faithfulness keeps the contract honest.)

### Authorization: event id only
No token, no registry — identical to upload. The env `AccessKey` is used server-side to call bunny
LIST and is never exposed; the account API key is never used. Listing leaks the set of filenames to
anyone holding the event id, but that holder can already upload, so it grants no new capability.

## Risks / Trade-offs

- **bunny LIST shape is verified against a mock, not real bunny.** Same stance as the existing
  upload suite (its bunny-facing behavior is tested against a mocked upstream). The timestamp field
  name differs across bunny's own docs (`LastChanged` vs `DateLastModified`); the mapper reads
  whichever is present. First real-bunny call is the on-device follow-up's job.
- **No pagination.** bunny returns a whole directory in one response; the handler aggregates and
  re-serializes in memory. Fine at personal scale; a very large event could strain an Edge Script's
  memory/time budget. Accepted for v1; revisit with pagination if it bites.
- **Fan-out is sequential-or-parallel N calls.** Bounded by device count (~1–3), so negligible; no
  subrequest-count concern at this scale.

## Migration Plan

Additive only — a new route on an existing app. No data migration, no breaking change, no client
depends on it yet. Ships via the existing `backend-deploy.yml` to both targets on merge to `main`.

## Open Questions

- None blocking. When the consumer change lands, confirm whether it wants `size`/`lastModified` at
  all (it may need only `filename`); if so, the entry can be trimmed further then.
