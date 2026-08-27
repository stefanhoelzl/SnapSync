## Why

The programme this change opens ends with the on-device upload ledger retired and the backend
authoritative for *"which bytes has this device uploaded"*. Every attempt to reach that through the
existing surface has run into the same wall: `/api/v1`'s shapes are frozen by the shipped install base,
so each step becomes an opt-in flag on a contract that cannot move, and the flags accumulate on a
surface nothing can ever be deleted from.

The wall is not incidental. v1 encodes a **structural** mistake that no additive change can undo: two
routes write `resources`, two write `memberships`, and the record of what the backend *witnessed* is
overwritten by what a device *asserts*. That is what made the obvious first step — reporting per-resource
upload state on the existing listing — a silent-data-loss hazard rather than a feature: a pending row in
the default response would seed `COMPLETED` on the device, and those photos would never upload, with no
error, no failed request and no log line.

A second version is the cheapest way out, and the backend is already built for it — the auth gate resolves
`/api/v\d+` version-agnostically, `/health` is deliberately root-mounted, and the device API is one
sub-app precisely so *"a future `/api/v2` is one more `app.route(...)`, without touching v1."*

## What Changes

- **A second versioned mount, `/api/v2`**, served alongside `/api/v1`. v1's wire contract is **unchanged**
  and frozen; its 67 behaviour tests must pass unmodified.
- **The relational schema migrates to its v2 shape**, and v1's handlers become an adapter over it — same
  requests, same responses, new storage underneath. Measured against the deployed store: 972 resource
  rows, no `(device_id, asset_id, role)` collisions, no placeholder rows, no `uploaded = 0` rows, and
  every key already parses as `<assetId>-<role>.<ext>`.
- **One writer per table — on v2.** There, `memberships` moves to explicit join/leave only and `resources`
  becomes byte-PUT only, recording what the backend witnessed rather than what a device asserts. **v1 is
  legacy and keeps its current behaviour unchanged**, including the two writes that violate the rule: its
  manifest publish still reactivates a membership and still repairs a lost upload record. Those are frozen
  rather than corrected, because v1 is spoken by builds that cannot be updated — and the repair is
  load-bearing there, since v1's byte write stays best-effort precisely because it exists.
- **Intent and reality separate.** The manifest declares what a member contributes; `resources` records
  what is stored; the union is their intersection and *pending* is their difference — so per-resource
  upload state needs no column, no flag, and no second endpoint.
- **v2 gains an explicit join route**, splitting enrollment out of the manifest write (today the manifest
  PUT *is* the enrollment — the client seam is even named `Enrollment`).
- **v2 removes `POST /events/<eventId>/notify`**, folding the fan-out into the manifest publish, where
  ordering against the union is guaranteed by construction rather than by a comment.
- **Every v2 request carries a minimum-app-version gate**, refused with `426` and the required version —
  the first refusal in this system a client can act on rather than mistake for a transient failure.
- **NOT in this change**, named so they are not assumed: any Kotlin change (the client stays on v1
  throughout), the device-side manifest declaring intent, ledger retirement, and v1's retirement.

## Capabilities

### New Capabilities
- `min-app-version`: the version a request must declare to be served, how a too-old request is refused so
  the answer is actionable rather than ambiguous, and where the minimum lives so raising it costs a review.

### Modified Capabilities
- `api-endpoints`: now describes **two** versioned surfaces rather than one. v1's table is restated
  unchanged and frozen; v2 adds the reshaped byte upload and per-device listing, an explicit join, a
  manifest write narrowed to contribution and carrying the fan-out, a union whose completeness is a
  role-set inclusion, and no notify route. Adds the rule that the fan-out's recipients are resolved in a
  single query.
- `database`: `resources` is re-keyed to `(device_id, asset_id, role)` and reduced to what storage
  actually holds; `event_assets` carries the declared role set; the `uploaded` column is retired, its
  meaning replaced by row existence. Adds the requirement that a released version's schema survives a
  migration performed for a later one.
- `backend-deployment`: two versioned mounts are served simultaneously, while the baked device-facing base
  still names exactly one — so a client's version is a property of its build, not of its request path.
- `device-attestation`: the closed list of ungated routes spans both versions, and the version gate is
  stated as running **ahead** of the token check — a deliberate inversion of the token-first rule, because
  a too-old build cannot be helped by a valid token.

## Impact

**Code.** `api/` only — routing and the version middleware, every handler, the `db` module and a
migration, the sweep's queries. `api/test/app.test.ts` splits into `v1.test.ts` (frozen), `v2.test.ts`,
and the cross-cutting remainder, with the three schema-coupled seed sites lifted into a shared helper so
the migration touches one place rather than three test bodies.

**No Kotlin module is touched.** No shipped build changes behaviour, and nothing reaches TestFlight or the
App Store as a result of this change.

**Deployment.** No new secrets and no new configuration values beyond the minimum version itself, which
lives in source with the other non-secrets.

**Risk.** The migration is up-front rather than deferred, which is acceptable only because `database`
already guarantees every row is reconstructible from the storage zone plus one manifest publish per
device: a botched migration costs an empty union until devices republish, not a lost photo. The store
holds 972 resource rows across 13 devices, so the migration itself is seconds of work.

**Reversibility.** v2 stays mutable until the first App Store promote of a build that targets it. Until
then nothing depends on its shapes, and reshaping costs a redeploy.
