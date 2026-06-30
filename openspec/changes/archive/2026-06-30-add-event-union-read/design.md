# Design — event-wide union read endpoint

## Context

After `dedup-files-device-manifests` the storage model is:

```
/files/<device-id>/<assetId>-<role>.<ext>     bytes · device-global · uploaded once · reused across events
/events/<event-id>/metadata.json              event marker {eventId,name,createdAt}
/events/<event-id>/device/<device-id>.json    per-event device manifest (mutable full-state projection)
```

The edge exposes per-device reads only — `GET /files/device/<id>` (raw list) and
`GET /files/device/<id>/<file>` (byte download) — and the per-event device manifests are unreachable
from the device. `device.json` is **write-only in v1**; the dedup change explicitly deferred the
**event-wide union** to an external/admin-direct reader (design.md §3.5).

The planned on-device download/restore feature needs, for one event, to enumerate every **foreign**
device's **complete** assets and the URLs to fetch their resources. The app holds no storage
credential, so this must be an **edge** read. This change builds exactly that read — and nothing of
restore itself — and renames the manifest's resource fields so the union is a straight projection.

## Goals / non-goals

- **Goal:** one edge read returns, for an event, every contributing device's **complete** assets,
  each with its resources' download URLs and the owning `deviceId`.
- **Goal:** the manifest and the union share one field vocabulary (`key` = fetch handle, `filename` =
  human name), so the union is a projection, not a translation.
- **Non-goal:** the download/restore client itself (still deferred). This builds its prerequisite.
- **Non-goal:** own-vs-foreign filtering on the server (the endpoint never knows "you").
- **Non-goal:** pagination / incremental union (bunny native LIST returns all directory children in
  one response; the single-LIST discipline is kept).

## Endpoint

```
GET /event/<eventId>/files
    → [gate]  GET events/<eventId>/metadata.json   → 404? respond 404 "event not found"
    → [list]  LIST events/<eventId>/device/          → contributing <deviceId>.json children
    → per device (parallel):  read device.json  +  LIST files/<deviceId>/
    → complete-only union
    → 200 [ { deviceId, assetId, creationDate,
              resources: [ { role, contentType, key, filename, size, url } ] }, … ]
       Cache-Control: no-store
```

- `eventId` validated as a UUID → else `400`, no upstream request. Served by the same `createApp`
  Hono app; unmatched path / non-`GET` → Hono's `404`.
- The stored `device.json` is **already** the event's date-filtered projection (the producer filters
  on capture date when projecting), so the union **trusts its asset list** and does **not** re-filter
  by date. The union's only computation over the manifest is the byte-presence (completeness) check.

## Completeness

An asset is included **iff every** resource it names is present in that device's byte store:

```
present(deviceId)  = { object names from LIST files/<deviceId>/ }       (decoded, == the uploaded key)
complete(asset)    = ∀ r ∈ asset.resources :  r.key ∈ present(asset.deviceId)
union              = ⋃ devices  { asset ∈ device.json.assets : complete(asset) }
```

`r.key` is the storage object name (`<assetId>-<role>.<ext>`) — the same string the per-device LIST
returns as its decoded object name, so the membership test is plain equality (the round-trip the
upload/list/download filenames already obey). Each resource's `url` is built by the **existing**
`downloadUrl(config, deviceId, key)` — the sole URL builder — so the union, the per-device list, and
the download route agree by construction. `size` comes from the same LIST entry that proved presence.

A per-device **file**-dir `404` means "no bytes present" (every asset incomplete) — **normal**, not a
failure: that device simply contributes nothing.

## Data flow

```
GET /event/E/files
  │
  ├─ readMarker(E)              null → 404   |  throw → 502   |  marker → continue
  │
  ├─ listDir(events/E/device/)  null(404) → 200 []           |  throw → 502
  │     └─ children .json → deviceIds  [d1, d2, …]
  │
  └─ parallel for each di:
        manifest_i ← GET events/E/device/di.json     (parse)   ── any read/parse failure → reject
        present_i  ← listDir(files/di/)              (404 → ∅)  ── non-404 failure       → reject
        assets_i   ← manifest_i.assets.filter(complete vs present_i)
                     .map(a → { deviceId: di, assetId, creationDate,
                                resources: a.resources.map(r → { role, contentType, key:r.key,
                                                                 filename:r.filename, size, url }) })
     any reject → 502 (no partial union)   |   all ok → 200 [ …flatten assets_i ]
```

## Key decisions

| Area | Decision | Rationale |
|---|---|---|
| Shape | Server-computed union, one client round-trip (`GET /event/<id>/files`) | The app is a thin reader with no credential; a single authoritative read beats N client round-trips + a client-side completeness join. |
| Completeness | Server-side, **complete-only** (omit incomplete assets) | The stated need is "foreign **complete** assets and their download URLs"; shipping incomplete assets would ship URLs that 404. The producer's manifest names resources that may not be uploaded yet, so a byte-presence check is required. |
| Date filter | None — trust the stored manifest's asset list | `device.json` is already the event's date-filtered projection; re-filtering would duplicate the producer and need the event start date the union doesn't read. |
| Response shape | Flat assets, each tagged `deviceId` | Lets the client drop its own rows by `deviceId` (own-vs-foreign is by device id) without the server knowing "you"; no grouping object to walk. |
| Resource fields | `{ role, contentType, key, filename, size, url }` | `key`/`filename`/`role`/`contentType` project straight from the manifest; `size`+`url` come free from the LIST already done for completeness; `url` via the one existing builder. |
| Own-device skip | Pure client concern; no `excludeDevice` param | Keeps the endpoint stateless and identity-free, consistent with the event-id-as-capability model; a personal app's wire savings are negligible. |
| Faithfulness | Strict: any non-404 fan-out failure → `502`, never a partial union | Mirrors the per-device list and marker routes; a transient blip must never read as "that device/asset isn't here". A device file-dir `404` is "no bytes", handled as empty, not a failure. |
| Event gating | Gate on the marker (`404` unknown event, `200 []` empty event) | Disambiguates a bogus/typo'd event id (`404`) from a real event with no complete foreign assets yet (`200 []`); consistent with `GET /event/<id>` and the manifest-write gate. One small extra GET. |
| Caching | `Cache-Control: no-store` | The union is a live read over **mutable** manifests + listings (no immutable-cache property); a stale window could hide just-completed assets. |
| Fan-out | `1 + 2N` upstream reads (N = contributing devices), per-device reads in parallel | Single LIST to enumerate devices; per device one manifest GET + one file LIST. N is tiny for a personal event; parallel keeps latency ≈ slowest device. |
| Manifest rename | `filename`→`key`, `originalFilename`→`filename`; clean cutover | One vocabulary across manifest + union (`key` = fetch handle, `filename` = human name); no backfill (disposable personal data) — the producer re-projects on the new build, the union reader expects the new names only. |

## Resolved engineering notes

- **Rename rewrites the stored manifest.** Skip-if-unchanged compares the **projected snapshot
  content**; the field-name change makes the new projection differ from any previously-stored
  snapshot, so the first cycle on the new build rewrites `device.json` to the new names — no special
  one-shot flag needed. Until that rewrite lands, a device's old-format manifest is malformed to the
  union (→ `502` under strict faithfulness); it self-heals on the next producer cycle (`device.json`
  is write-only, rewritten each cycle).
- **Parse failure is a read failure.** A `device.json` that is present but not valid JSON (or missing
  `assets`/resource fields) rejects → `502`, never silently skipped — strict faithfulness. Benign in
  practice: the sole writer rewrites a complete snapshot each cycle.
- **Device enumeration vs marker.** The marker gate is what makes "unknown event" (`404`) distinct
  from "event exists, no contributors / no complete assets" (`200 []`); the device-dir LIST alone
  could not tell them apart.
- **`key` ≡ listed object name.** The manifest `key` and the LIST's decoded `ObjectName` are the same
  uploaded filename string (the upload/list/download round-trip), so completeness is equality, no
  normalization.
- **No new config / auth.** Reuses `AccessKey` from config on every upstream read; never the account
  API key; no token. The single `createApp` app deploys unchanged.

## Accepted trade-offs (eyes open)

- **`1 + 2N` fan-out per request.** More upstream reads than a single LIST, all behind strict
  faithfulness (a partial fan-out is a `502`). Fine for a personal event's tiny N; no caching to
  amortize (live read).
- **Clean cutover, no backfill.** Old-format `device.json` objects are malformed to the union until
  the producer rewrites them; a stale device transiently `502`s the whole event union. Acceptable for
  a personal TestFlight app with disposable data and a sole writer that converges each cycle.
- **Restore still deferred.** This builds only the read; nothing consumes the union in v1.

## Spec-delta map

```
MODIFY  bunny-list-endpoint     ADD the event-wide union route GET /event/<id>/files (gate → enumerate
                                → per-device manifest read + file LIST → complete-only union; strict 502)
MODIFY  device-manifest         resource field rename: filename→key, originalFilename→filename
MODIFY  ios-background-upload    producer writes key/filename; skip-if-unchanged is over snapshot content
TOUCH   docs/design.md §3.5      union is now an edge read (reverse the external/admin-direct deferral)
UNTOUCHED  event-creation, bunny-upload-endpoint, bunny-download-endpoint, edge-upload-provider,
           device-identity, sync-status, sync-engine/ledger, presentation/ui
```
