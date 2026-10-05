# Design

## Context

See proposal.md for why. The outcomes are in `specs/privacy-security`; the withdrawal outcome the client now
honours was already `photo-sharing`'s. What shapes the *how*:

- **The union is one query** (`unionRows`, `api/src/db.ts`) over `event_assets` joined to `resources`. An
  asset is served only when every declared role is stored. `GET /events/<id>/files` then presigns one
  7-day S3 URL per resource on every read (`shared.ts`). The signed URL is most of each entry's bytes, and
  its signature does not compress.
- **The server already knows the moment the union gains an asset**, because that moment fires the push:
  `eventsCompletedBy` (the byte route: this write completes an asset) and `publishAddsFetchableAsset` (a
  publish declares an already-stored asset; today it answers only a boolean).
- **A publish replaces the device's `event_assets` rows wholesale** (delete + insert, `publishStatements`),
  so nothing stored on those rows survives a republish.
- **The client is add-only and dedups.** `DownloadController.reconcile` plans every foreign asset that
  `settledAmong` doesn't already know. An asset that leaves the union is never un-planned, so most of each
  read is discarded. A full re-read is also today's only refresh of an expiring URL.
- **Readers of the union** (all in `domain/`): the push receiver, the background wake (`reconcileIfDue`,
  hourly bound), the foreground flow, the join (`JoinUnion` → adoption + reconcile), the grant (adoption when
  access becomes usable), reconfigure, and the leave check (`everythingReceived`, only after close). The
  iOS upload extension never reads it. The web join page reads it through `/api/v1`.
- **Pushes collapse per event** (`apns-collapse-id` / FCM `collapse_key` = the event id), so a burst
  usually arrives as its latest push.
- **Capacity is 10 devices per event**, so the read log stays small per event.

### Measured (2026-10-05)

A redirect route is only viable if both download transports follow a 302, and the signature lifetime
depends on what they do when a redirected transfer is interrupted.

| | Android `DownloadManager` (emulator, API 36) | iOS background `URLSession` (SE2, 26.6) |
|---|---|---|
| follows a 302 to the body | yes (contract clause, live) | yes (one-off device run over an https tunnel) |
| a retry after interruption requests | the **original** URL, ~70 s later, `Range` resume | the **target** URL directly, 3 s (cut) / 72 s (stall), `If-Range` resume |
| an expired target on retry | not reached: it never reuses the target | `403`, reported as a finished transfer; the row stays pending until a reconcile restarts it |

Both runs redirected within one host (http loopback on Android, an https tunnel on iOS). Production
redirects from the edge to S3 across hosts over https; Apple documents that background sessions follow
redirects automatically. Evidence: the scratchpad probe logs of this session; the Android result is pinned
by the new `DownloadContract` clause.

## Goals / Non-Goals

**Goals:**
- No read signs anything; signing scales with downloads actually started.
- A push or background wake transfers only what is new.
- Released apps and the current event page keep working, unchanged, until the version floor passes.
- A server-side record that explains read patterns per event and dies with the event.

**Non-Goals:**
- Paging. The first read after a join is the whole list, once, at roughly 0.2 KB per asset without URLs.
- Telling the client about removals inside a delta. Removals are learnt from full reads only.
- Retiring the `url` field and the old shape. That is a later change, once the version floor allows it.
- The v1 per-device listing's presigned `url` (`v1.ts`), which only v1 apps use.
- Changing how a push is sent or collapsed, other than adding the position to its payload.

## Decisions

### D1. Downloads go through a stable redirect route
`GET /api/vN/events/<eventId>/files/devices/<deviceId>/<assetId>/<role>` answers `302` to a fresh presigned
S3 GET, with `Cache-Control: no-store, no-cache, max-age=0`. Bunny's CDN fronts the edge, and a cached 302
would hand out a stale signature.
- The route resolves the storage key from `resources` and checks that the event declares that asset and role
  in a membership of either state (active or departed), using the same predicate as `unionRows`. Otherwise it answers `404`. It
  stays ungated: the event id is the read capability, like the union's.
- The path is the upload path (`/files/devices/<d>/<asset>/<role>`) behind `/events/<e>`, so it sits under
  the union route. It is built from identity, which the client already holds, and never from the storage
  `key`. Every segment is a UUID, a canonical asset id (URL-unreserved characters only) or a role, so no
  segment needs encoding.
- **The client's HTTP adapter builds it** (`HttpBackend`, which owns every route path and its base), filling
  each `UnionResource.url` when the server sends none. The port's model and the download planner are
  unchanged, and the mocks keep their own handles.
- It is mounted in `sharedRoutes`, so both versions serve it.

*Alternatives*: (a) a batch "sign these keys" call before enqueueing. Rejected: an extra round trip inside a
short background wake, and a "planned but no URL yet" state. (b) Keep inline presigns and shrink only through
deltas. Rejected: the full re-read is today's only URL refresh, so incremental reads alone would strand queued
downloads past 7 days. (c) The event as a query parameter on the exact upload path. Rejected in favour of the
event in the path.

### D2. The signature lifetime stays 7 days
iOS resumes from the redirect target and never revisits the original (Measured). A signature shorter than an
outage turns a resume into a full restart, which waits for the next reconcile. A shorter lifetime buys
almost nothing: anyone holding the event id can mint fresh links for as long as the event lives. The one
cost is that a withdrawn photo stays reachable for up to 7 days to whoever already took its 302.

### D3. One route, two orthogonal parameters, the cursor in a header
`GET /files` keeps its bare JSON array.
- `urls=false` omits `url` from every resource. The default stays `true`, because released apps decode `url`
  as a required string (`UnionResource.url`).
- `cursor=<opaque>` returns only assets gained after that position. Without it, the whole union is returned.
- Every response carries `SnapSync-Cursor: <opaque>`, the position the answer covers. A full read therefore
  also hands back a starting cursor.
- On `/api/v2`'s default shape, `url` is the absolute redirect URL of D1 instead of a presign, so released
  App Store builds (0.4 onwards, all v2) stop costing signatures at once with no change of their own.
- **`/api/v1`'s default shape keeps its 7-day presign.** v1 is a frozen contract for builds older than 0.4
  (pre-App-Store, TestFlight only): `v1.test.ts` asserts the presign and must pass unedited. Those builds
  therefore keep links that outlive a withdrawal by up to 7 days, an accepted exception (decided
  2026-10-05). `urls=false` and `cursor` are additive on v1 too, which the event page relies on (D8).

*Alternatives*: (a) A new `/union` route. Rejected for one route with parameters. (b) Wrapping the body as
`{cursor, assets}`. Rejected: it couples the two parameters and changes the shape old decoders read.
(c) A `since=<timestamp>`. Rejected: clock skew and same-millisecond ties would need overlap windows and
dedup.

### D4. A per-event log table holds the positions
New table, migration `0006` (name settles in code): `union_log(seq INTEGER PRIMARY KEY AUTOINCREMENT,
event_id REFERENCES events ON DELETE CASCADE, kind, device_id, asset_id, trigger, cursor_from, cursor_to,
count, at)`, indexed on `(event_id, seq)`.
- **`gained`** is written where the two existing gain points are known. On the byte route, the events
  `eventsCompletedBy` answered get their rows in the same batch as the `resources` row, where possible.
  `publishAddsFetchableAsset` changes from a boolean to the set of gained refs, and those rows are written in
  the publish batch, so they ride its version and closed-event gates.
- **`removed`** is written in the publish batch for previously complete assets the new set no longer
  declares. It is a log entry only (see D6).
- **`fetch`** is written by `/files` after it has assembled its answer. The write is best-effort: a failed
  log write never fails the read.
- **A delta** is the `gained` rows with `seq > cursor` for the event, put through `unionRows`'s completeness
  and declaration filter. An asset removed since then is therefore not served, and a re-gain simply adds
  another row; the client dedups by ref.
- The cursor is the opaque encoding of a `seq`. It is global, so gaps belong to other events and are
  harmless.

*Alternatives*: (a) A `seq` column on `event_assets`. Rejected: publish rewrites those rows, which would
re-stamp every asset on every republish, unless publish became a diff (upsert + delete-missing) and the byte
route stamped the row too. That rewrites the most guarded write; deferred. (b) A per-event counter on
`events`. Rejected: the stamp still needs a per-asset row, and dense per-event numbers buy nothing behind an
opaque cursor. (c) A hash of the union as the cursor. Rejected: a hash says *whether* the union changed,
never *what* changed.

### D5. Reads say who and why; a bad token is a 401
`/files` verifies an optional bearer token. A valid one logs the fetch under its device. A present but
invalid one answers `401`, so `AuthenticatedBackend` re-attests and retries once, as on gated routes; the
route joins `isGatedRequest`'s token handling. No token is logged as anonymous, with no address, user agent
or other request detail stored.

`SnapSync-Trigger` is one of `push | wake | foreground | join | grant | reconfigure | leave-check`. An
unknown or missing value is logged as unknown and never refused, so a future client cannot break its reads.

*Alternative*: a separate token-gated app route. Rejected for one route, because the web page and old apps
read without a token.

### D6. When the client reads what
- **Delta reads:** `push` and `wake`.
- **Full reads:** `foreground` (the user is waiting, and this heals a missed stamp), `join`, `grant` (adoption
  must see every marked photo), `reconfigure`, `leave-check` (the union is frozen after close, and this is
  the one decision where doubt must keep the member), and any read with no stored cursor.
- **The push carries the gained `seq`** (an APNs top-level sibling of `eventId`; an FCM `data` field). A
  device whose stored cursor is already at or past it skips the read. A push with no `seq` (the close push,
  or an older server) reads as before.

The cursor lives in `downloads.db` (the app is its one writer) and is advanced **in the same transaction as
`planAll`**. A cursor can then never move past an asset that was not planned.

### D7. A full read prunes what was withdrawn and not received
On a full read, a foreign row that is still only planned, enqueued or staged (no import marker, not claimed
in `importing`, not terminal) and whose ref the union no longer lists is **deleted**, and its staged bytes are
released. It is deleted rather than settled, so `settledAmong` doesn't exclude it: a photo that comes back
re-enters through a later `gained` row and is planned again.
- Imported and unimportable rows are untouched.
- An OS download already in flight for a deleted row lands on no row and its bytes are discarded.
- A download of a withdrawn object gets the redirect's `404`. It stays pending, with no special meaning,
  until the next full read prunes it.

*Alternative*: tombstones in deltas plus `410 Gone` on the redirect. Rejected: a withdrawn photo can come
back (`manage-membership`: narrowing then widening restores it), so "gone for good" is false, and full reads
already bound the delay.

### D8. The event page builds its URLs too
`join.astro` reads with `urls=false` and builds each resource's URL from D1's template. The default shape then
serves only released apps, and its retirement depends on the version floor alone.

### D9. JSON stays; compression is the pull zone's
With URLs gone, the remaining fields are repeated keys and ids that compress well. **Measured 2026-10-05**
against the deployed `snapsync.stho.net`: the bunny pull zone answers origin JSON responses
(`application/json`, `cdn-cache: MISS`, `no-cache` — the AASA and `assetlinks.json`) with
`content-encoding: br`, and HTML the same. The union is served through the same zone and script as
`application/json`, so it needs no compression of its own. The union route itself was not probed: that
needs an event id, and only a genuine app can create one. Compact keys and binary formats were rejected
as a codec for little gain.

## Risks / Trade-offs

- [A gain point is missed or its log write fails, so an asset never reaches any delta] → every foreground is
  a full read and plans it; join and the leave check are full reads too. The cost is a delay until the next
  opening, never a lost photo.
- [Commit order differs from `seq` order, so a client reads past a row that commits later with a smaller
  `seq`] → SQLite and libsql serialize writes, so the insert order of `AUTOINCREMENT` is the commit order.
  The foreground full read heals any residual. This needs re-checking if the store ever stops serializing.
- [Builds older than 0.4 (v1) keep presigned links that serve a withdrawn photo for up to 7 days] →
  accepted (D3): v1 is frozen, and those builds never reached the App Store.
- [v1 writes (pre-0.4 builds' byte route and publish) log no `gained` rows, so their photos reach v2
  members only through full reads] → v1 stays frozen; every foreground is a full read.
- [Whoever already took a photo's 302 can reuse its S3 target for up to 7 days after a withdrawal] →
  accepted with D2; the address the spec speaks of (the stable one) stops serving at once.
- [A copied photo link reveals the event id, so one link grants the whole event] → accepted and stated in
  `privacy-security`. The links are never shown in the app or on the page.
- [An iOS resume reuses an expired target and restarts] → D2 keeps 7 days. A resumed iOS transfer reports
  `206` with the remaining length as `expectedBytes` (measured), which `mayBeStaged` accepts. A future
  integrity check must not require `200` or compare against the object's full size.
- [A maintenance window now refuses downloads that have not started (the redirect reads the store)] →
  windows are short and announced; Android retries a `503`, and on iOS the row stays pending until the
  next reconcile re-enqueues it. Downloads already redirected are unaffected.
- [The edge sees a request per download start] → a 302 is a few hundred bytes with one signature, which is
  the cost the listing used to pay per read for every resource.
- [The read log grows with reads] → bounded by pushes, foregrounds and 10 members, and dropped with the event.
- [The trigger and install id per read are new data about app activity] → `privacy-security`'s new
  requirement, the Privacy Policy update, and a review of the App Store privacy answers and Play's Data
  safety form ship in the same release.
- [The cross-host edge→S3 redirect is unmeasured] → one download on the SE2 after deploy (tasks). An
  hours-long outage is not re-measured: with 7-day signatures it exercises only iOS's existing resume.

## Migration Plan

1. **api first.** The migration adds `union_log`. The redirect route goes live. The default `/files` shape
   switches its `url` to the redirect URL. `urls=false`, `cursor` and the header are additive. Old apps and
   the current page keep working.
2. **Site** moves to `urls=false` and builds URLs (D8). It deploys with the api.
3. **App release** reads with `urls=false`, cursors and triggers. Mixed fleets are fine: every shape is served.
4. **Later change**: once the version floor passes step 3's version, remove the `url` field and the default
   shape.

Rollback: the api change is additive except the default `url` swap. Reverting to inline presigns is a
one-line change in the route and needs no client action. The table can stay.

