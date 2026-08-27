## Context

`/api/v1` is frozen by the shipped install base, and the programme it has to carry — retiring the
on-device ledger and making the backend authoritative for what a device has uploaded — needs shapes it
cannot move. The first step of that programme, reporting per-resource upload state, was designed twice
against v1 and abandoned both times: as an opt-in query parameter it left the load-bearing safety rule
(*the default response must stay filtered to uploaded rows*) enforced by a test asserting two key names,
one line away from silent data loss; as a second route it created a second projection over the same table.

The wall underneath both attempts is structural rather than cosmetic. In v1:

- `resources` has **two writers** — the byte route (which knows a byte landed) and the manifest publish
  (which asserts `uploaded ?? true` for whatever a device lists). The backend's record of what it
  *witnessed* is overwritten by what a device *claims*.
- `memberships` has **two writers** — the enrollment statement and the manifest publish's
  `UPDATE … SET state = 'active'`, the second of which is pure redundancy: `ENROLL` already ends
  `ON CONFLICT (event_id, device_id) DO UPDATE SET state = 'active'`.
- There is **no join route at all**. `PUT /events/<e>/devices/<d>` *is* the enrollment; the client seam is
  named `Enrollment` and the manifest is its body.
- The manifest lists only `COMPLETED` resources, because the union could not otherwise tell an uploaded
  asset from a merely-discovered one — a workaround `device-manifest` records as demoting the union's
  completeness check to *"defense-in-depth rather than the primary completeness mechanism."*

Every knot the earlier designs kept hitting — the best-effort repair, `uploaded ?? true`, monotonicity via
`MAX()`, pending rows that can never retract — exists to reconcile two writers of one fact.

The server is already built for a second version: the auth gate strips `/api/v\d+` version-agnostically,
`/health` is root-mounted so *"a future `/api/vN` should neither duplicate nor strand it"*, and the device
API is a single sub-app so that *"a future `/api/v2` is one more `app.route(...)`, without touching v1."*
`backend-deployment` already carries the scenario admitting a future version.

## Goals / Non-Goals

**Goals:**

- Serve `/api/v2` beside `/api/v1`, with v1's wire contract byte-for-byte unchanged and its 67 behaviour
  tests passing **unmodified**.
- Give every table exactly one writer.
- Separate what a member *intends to contribute* from what the backend *has*, so per-resource upload state
  is derivable rather than stored.
- Migrate the store to one clean schema rather than maintaining two entangled ones.
- Make a too-old client's refusal actionable rather than indistinguishable from a transient failure.

**Non-Goals:**

- **Any Kotlin change.** The client stays on v1 for the whole of this change; no shipped build alters
  behaviour and nothing reaches TestFlight or the App Store as a result.
- The device-side manifest declaring intent, and ledger retirement — those are the programme's next steps
  and depend on a v2 client that does not yet exist.
- v1's retirement, which is gated on install-base decay rather than on anything here.
- Freezing v2. It stays mutable until the first App Store promote of a build targeting it.

## Decisions

### D1 — A second version, not a widened route

**Decision.** Mount `/api/v2` beside `/api/v1`.

**Alternatives.** *(a)* An opt-in parameter on the existing listing. Rejected: the default response must
stay filtered because `ExtensionReconciler` seeds one `COMPLETED` ledger row per returned filename via
`resetTo`, so a pending row would make the engine answer `AlreadyUploaded` for a resource that never
uploaded — and the enriched row would then be republished, raising the backend's own `0` to `1` and
putting a dangling presigned URL into the union for every other member. That safety property would have
lived as a discipline, enforced by one assertion, inside the same handler as the new contract.
*(b)* A second route over the same table. Rejected: two projections that can drift, and the field set is
closed either way, so it buys nothing while also costing a row in a route table declared closed.

Under v2 the property becomes **structural**: shipped devices call v1 and cannot receive a pending row at
all.

### D2 — One writer per table

**Decision.**

```
events          ← POST /events            (+ sweep deletes)
memberships     ← join / leave            ONLY
event_assets    ← manifest PUT            ONLY
resources       ← byte PUT                ONLY  (+ sweep deletes)
devices         ← attest / config PUT     ONLY (one writer per column group)
```

This is the domain's own law — *"features … coordinate via one-writer durable state behind shared ports,
written whole"* — applied to the backend for the first time. It dissolves the entangled knots listed in
Context simultaneously, rather than patching each.

Consequence: v2 needs an **explicit join route**, because enrollment currently rides the manifest write.
Capacity enforcement (`409` full / `404` absent) moves there with it, and a manifest from a non-member is
refused rather than silently joining.

**The rule applies to v2 only. v1 is legacy and keeps its current behaviour**, including the two writes
that violate it: its manifest publish continues to reactivate the membership and to repair a lost
resource record. Correcting them would be a behaviour change to a surface spoken by builds that cannot be
updated — and the repair in particular is load-bearing there, because v1's byte write stays best-effort
precisely because the repair exists. `api-endpoints` already binds those two as a pair that may not be
edited independently; this design honours that pairing rather than unpicking it.

That leaves `resources` with two writers during the transition. It is safe by construction: a device
speaks exactly one API version, so a v1 route can only ever write rows belonging to a device still on v1,
and v1's writes are additive — a row is created when missing and never removed. The second writer ends
when v1 is retired.

Row existence turns out to express every v1 behaviour exactly, which is what makes the exemption cheap:

```
v1 behaviour                        old schema        new schema
byte PUT records                    uploaded = 1      row created
byte PUT best-effort                swallow, 201      swallow, 201
manifest repairs a lost record      MAX(0,1) = 1      create row if missing
`uploaded: false` cannot un-say     MAX(1,0) = 1      do not delete an existing row
listing filters to uploaded         WHERE uploaded=1  every row for the device
```

Monotonicity is not lost, it is renamed: *"a false entry cannot un-say an upload"* becomes *"a false entry
does not delete the row."*

### D3 — Intent and reality, with pending as a set difference

**Decision.**

```
manifest    "what I contribute to e"        intent   · policy-applied · event-scoped
resources   "what is actually in storage"   reality  · policy-free    · device-scoped

union   = manifest ∩ resources
pending = manifest ∖ resources
```

There is no `uploaded` column. A `resources` row existing **is** the statement that the bytes are stored,
and the backend only ever records what it witnessed.

This requires the manifest to declare the **admitted** set rather than only completed uploads — reverting
a deliberate earlier decision. That decision's reason is genuinely gone: it existed because the union
could not distinguish uploaded from discovered, and with `resources` as an authoritative record of storage
the join now can. The union's completeness check is therefore **promoted** from defense-in-depth to the
mechanism.

**A short manifest is a retraction, not a slip.** Omitting an uploaded asset removes it from the event.
That makes the backend total — full-state replace, taken at face value, nothing to infer — and it gives
scope narrowing, library deletion and reconfiguration a first-class semantics instead of travelling
through a mechanism written to treat them as suspicious.

### D4 — `event_assets` keeps asset grain, carrying the declared role set

**Decision.** `event_assets (event_id, device_id, asset_id, creation_date, roles)` where `roles` is a JSON
array. `resources` is keyed `(device_id, asset_id, role)`.

**Alternatives.** *(a)* A separate `manifest_resources` child table at resource grain. Rejected as
unjustified normalization: **nothing reads `event_assets` at asset grain today** — both readers, the union
and the sweep's `referencedKeys`, immediately join to resource grain — but with `roles` encoding the
expected set, asset grain is recoverable and `creation_date` is stored once. *(b)* Changing
`event_assets` itself to resource grain, duplicating `creation_date` per resource. Rejected once `roles`
made it unnecessary.

Completeness is then a role-set comparison rather than a row count, which matters: see D8.

### D5 — Identity is structural in the URL

**Decision.** `PUT /api/v2/files/devices/<d>/<assetId>/<role>?filename=…`

The backend is *told* the identity instead of decoding it. This deletes `uploadKey` and
`assetIdFromUploadKey` from the client's side of the contract — including that parse's own nervous caveat
that *"the role token carries no `-`, though an `assetId` may"* — and lets the route validate `role`
against a closed vocabulary rather than trusting an opaque string.

**Alternatives.** *(a)* Descriptors as request headers, keeping the synthetic key. Viable on the wire —
arbitrary headers survive the OS-performed upload, measured with the origin observing `Authorization` on a
real PUT whose user-agent was `assetsd` — but rejected because headers move *descriptors* while
completeness needs *expectation*: the device is the only party that knows an asset has two resources, so
the manifest must name the expected roles regardless. *(b)* Keeping the key opaque and parsing it
server-side. Rejected as a second implementation of a load-bearing parse, in another language, on the hot
path.

### D6 — Filename is a query parameter, required

**Decision.** The capture filename travels as `?filename=`, never as a path segment.

User-controlled bytes then never touch the storage path. The existing requirement — *"a single non-empty
path segment containing no path separator … and no `..`"* — is not relaxed but made **unnecessary**: you
cannot traverse with something that never reaches the key. It also keeps arbitrary bytes out of reach of
path normalization in the pull zone that fronts the script.

### D7 — The storage object name does **not** change

**Decision.** v2 stores objects at `files/devices/<d>/<assetId>-<role>.<ext>` — v1's exact layout —
computed by the backend from the path segments plus the query filename's extension.

This **reverses** an earlier decision to drop the extension. The reversal came from asking how the two
versions coexist: a v1 device uploaded to `A1-primary.heic`, and a v2 device uploading to `A1-primary`
would consider none of its bytes uploaded and re-upload its entire library, while an event with one member
on each version would need the union to address two layouts.

Keeping the layout pays three times: no byte migration and no dual addressing; the open question of
whether bunny infers `Content-Type` from the extension simply evaporates; and the sweep's single flat
`LIST` per device is untouched. The extension was only ever redundant for *identity*, and identity is now
the primary key — as an *address* it is free.

Nested keys (`<assetId>/<role>/<filename>`) were considered and rejected outright: bunny's `LIST` is
directory-shaped (`IsDirectory`), and the sweep filters with `!e.IsDirectory`, so nesting would make it
collect **nothing** while reporting a clean run.

### D8 — Completeness by set inclusion, not count equality

**Decision.**

```sql
NOT EXISTS (SELECT 1 FROM json_each(ea.roles) j
            WHERE NOT EXISTS (SELECT 1 FROM resources r
                              WHERE r.device_id = ea.device_id
                                AND r.asset_id  = ea.asset_id
                                AND r.role      = j.value))
```

**Alternative.** Comparing `COUNT(*)` against `json_array_length(roles)`. Rejected by measurement, not
taste: `resources` is device-scoped while `roles` is event-scoped, so an asset whose event declares
`["primary"]` while the device holds both roles yields `present=2, declared=1` and reads **incomplete** —
an asset silently missing from that event's union. Verified locally: inclusion says complete, count
equality says incomplete.

JSON1 availability was measured on the **deployed** store (read-only, table-free probe): SQLite 3.45.1,
`json_valid`, `json_array_length`, `json_each` and the inclusion idiom all working.

### D9 — Migrate up front; v1 becomes an adapter

**Decision.** Migrate the store to the v2 schema in one step and rewrite v1's handlers to preserve their
wire contract over it.

**Alternative.** A shared, compromise schema tolerating both shapes — v1's columns kept, a partial unique
index added, `roles` nullable. Rejected: it entangles two schemas permanently, makes the transitional
two-writer state part of the design rather than an artifact of migration, defers the risky half of the
schema change to v1's retirement (a *second* migration), and would legitimately require editing v1's
tests, destroying the guarantee that they pass unmodified.

The up-front migration is acceptable **only** because `database` already guarantees the store holds
rebuildable state — *"Every row SHALL be reconstructible from the storage zone plus one full-state
manifest publish per device"* — so a botched migration costs an empty union until devices republish, not
a photo. The store holds 972 resource rows across 13 devices.

v1's byte route is the one handler that cannot be adapted cleanly: its URL carries only the key, while the
v2 schema needs `(asset_id, role)`. It therefore gets a **parse shim** in TypeScript. The earlier
objection to duplicating that parse does not apply here: the shim lives only in the v1 adapter, has a
known deletion date, and was validated against every key in the deployed store (0 of 972 fail to match
`<asset>-<role>.<ext>`). A key that does not parse has no valid identity under the new PK, so v1 narrows
to reject one — a deliberate narrowing, affecting inputs no real client produces.

### D10 — Notify folds into the manifest publish, and fires on every push

**Decision.** Remove `POST /events/<e>/notify` from v2; the manifest publish fans out after its
transaction commits. Recipients are resolved in **one join** over `memberships ⋈ devices`.

The arithmetic: capacity is 10, `sendSilent` is already `Promise.all` over tokens with the provider JWT
memoized, and the device budgets are 12 s (manifest) + 8 s (notify). Merging fits inside the 12 s and
deletes the 8 s and a whole HTTP round-trip.

The `1 + N` recipient resolution — `membersOf` plus one `readPushToken` per member — is a fossil of the
object-store era, where one document per device was the only way to ask. It survived the relational
cutover because only the union was rewritten. `membersOf` has exactly one caller.

**Trigger: every manifest push, not "the union grew."** A within-transaction before/after diff would catch
growth *caused by this push*, but misses the common case — bytes landing between pushes grow the union
with no manifest change — and catching that needs remembered state. Cost: under intent-declaration a push
can fire when an asset is merely discovered, so recipients occasionally wake to find nothing new.

**Ordering is load-bearing:** commit → fan out → respond. Fanning out inside the transaction would let a
woken recipient read the union before the commit is visible. The fan-out is best-effort inside a faithful
write — the response is the transaction's outcome and never the fan-out's, the same split
`api-endpoints` already draws for the byte route's database write — and is bounded server-side, assuming
a cold connection.

### D11 — The version gate: marketing version, ahead of the token

**Decision.**

| | |
|---|---|
| Header | required on every v2 route, carrying the marketing version |
| Position | before the token gate, `/attest/*` included |
| Comparison | two-part numeric — `0.10 > 0.9`, never string ordering |
| Refusal | `426`, body carries the required minimum |
| Absent/malformed | the same `426` |
| Minimum | in source with the other non-secrets |

**Marketing version, not build number.** The build number (`CFBundleVersion`) is a monotone integer and
technically the better key — but every build between two releases shares one marketing version, and the
marketing version is what a user can act on. The gate exists so a screen can say *"update to 0.12"*.

**Ahead of the token**, inverting `api-endpoints`' token-first rule, because the check reads nothing
upstream and cannot grow the bill the gate protects; because a too-old build cannot be helped by a valid
token; and because an old build with an expired token would otherwise be told "authentication problem"
when the truth is "update the app."

**Absent and too-old collapse deliberately**: both mean the client cannot be trusted to speak v2, and the
remedy is identical.

**Two-part numeric comparison** is pinned by a test. This repo already carries one bug of that family —
`db.ts` documents `…+00:00` sorting before `…Z` for the same instant.

### D12 — The gate is top-level middleware

**Decision.** Register it as `app.use("*")` **before** the existing auth gate, acting only on the v2
prefix.

Hono runs parent middleware for mounted sub-apps, so middleware registered on the v2 router would run
*after* the token check — the wrong order. Both gates need the same `/api/v\d+` normalization, which
becomes one shared helper rather than a second copy of the regex.

## Risks / Trade-offs

**[The migration is up front and destructive]** → Bounded by `database`'s rebuildability guarantee (an
empty union until republication, never a lost photo), by 972 rows of actual data, and by the pre-migration
survey confirming no `(device_id, asset_id, role)` collisions, no placeholders, and no `uploaded = 0` rows.

**[Rewriting v1's handlers could change v1's behaviour]** → v1's 61 route tests in `v1.test.ts` are the
contract. The split landed as its own mechanical PR **first**, so the migration PR's diff shows
`v1.test.ts` almost entirely untouched — making the freeze a reviewable fact rather than a claim.

The guarantee is precise, and the imprecise version of it is worth naming because it was believed for a
while during this design: **v1's wire contract is fully preserved** — every path, status code, response
shape and upstream effect, including the repair path and the best-effort byte write. What cannot survive
verbatim is a test that asserts a **retired column**. Four do:

| test | asserts | re-expressed as |
|---|---|---|
| byte upload records the upload | `SELECT … uploaded` is `1` | the row exists |
| manifest repairs a lost record | `uploaded` became `1` | the row was created |
| `uploaded` is monotone | `uploaded` stayed `1` | the row was not deleted |
| union excludes an incomplete asset | `UPDATE … SET uploaded = 0` | delete the row |

Each asserts the same fact in a different spelling, so editing them is not evidence of a behaviour change
— but the distinction has to be stated, or the next reader cannot tell the two apart. They are enumerated
here for exactly that reason.

**[The one-resource-per-`(asset, role)` invariant is unverifiable from the backend]** → A second same-role
upload is indistinguishable from a legitimate re-upload; both are last-write-wins on the same key. The
invariant is stated rather than enforced, and role-based keying bounds a violation to an overwrite — no
orphan byte, no row/object divergence. Evidence: 972 real resources, no collisions, and only `primary`
and `live` present. That is 13 devices, not a population — better than `n=1`, not proof.

**[The deployed SQLite (3.45.1) is older than the test SQLite (3.53.2)]** → CI runs the *newer* engine, so
a post-3.45 feature would pass every test and fail only in production. Nothing in this design needs
anything newer (`json_each` is 3.9). Recorded as a measurement with an expiry trigger — a bunny upgrade,
or a query reaching past the floor.

**[The version gate is illegible to builds that predate it]** → It makes the *next* retirement clean, not
this one. v1's withdrawal still leaves pre-v2 installs quietly inert, so its timing depends on install-base
decay judged from App Store data, not from anything the backend can observe.

**[A lost byte-PUT record now costs a re-upload]** → v1's repair (`uploaded ?? true` on the next manifest)
disappears with the manifest's write to `resources`. `api-endpoints` binds those two requirements with
*"SHALL NOT be edited independently"*, so they are rewritten as one thought. The replacement is more
honest — the old repair asserted `uploaded = true` for bytes the backend never saw — and the byte PUT's
database write therefore **stops being best-effort**, since the repair was its whole justification.

**[Recipients wake to nothing]** → Notifying on every manifest push can fire when an asset is only
discovered. Bounded by skip-if-unchanged (an unchanged projection sends nothing), but it is a real silent
push budget cost and is accepted knowingly rather than discovered later.

## Migration Plan

```
1  ✓ pre-migration survey (read-only)      done. clean.
2    split api tests                        mechanical, no behaviour, no spec
3    migrate schema + v1 adapter            v1.test.ts untouched in the diff
4    build v2 beside it                     molten until the first promote
5    v1 retirement                          separate change, gated on decay
```

Rollback for step 3 is republication, not a reverse migration: the store is rebuildable by contract, and
every active device republishes its full-state manifest every cycle.

## Open Questions

- **Does the v2 per-device listing carry `url`?** Its v1 consumer ignores it, and dropping it removes the
  per-row HMAC presign from a route whose purpose is "what do I have stored". Leaning drop.
- **What supplies the admitted set once the ledger is retired?** The library itself, enumerated fully and
  cached — a derived, rebuildable cache rather than an authority. Device-side, deferred, but it decides
  how "cannot establish the complete set" is defined once `ledgerSettled` no longer exists.
- **What does v1 answer at retirement?** A bare `404` makes stale devices silently inert. A distinguishable
  refusal would be better, but only for builds that already understand one.
- **Obligations the v2 client will inherit**, recorded here because they shaped the API: the version header
  must be set in **two** places (the shared Ktor client and `EdgeUploadRequestProvider`, since the OS
  performs the byte PUT from a request we compose), and `PlatformUploadJob` — today carrying only
  `key`, `contentType`, `error`, `data` — must gain the destination, because a retry rebuilds with
  `metadata = emptyMap()` and could not otherwise supply a required `filename`.
