> **CORRECTED AFTER ARCHIVING — D8 and D11 below are superseded.** The cutover had not run when this was
> archived, and two decisions did not survive contact with it:
>
> 1. **v2 dropped `device_records` instead of migrating it.** The rows' push tokens were to be carried by
>    the one-time program, which made data preservation depend on operator ordering — and made loss the
>    default outcome of an ordinary deploy, since CI applies migrations on every push. v2 now carries every
>    row with `INSERT … SELECT`, leaving the attestation columns nullable (their values live in the storage
>    zone, which SQL cannot reach), and a new **v3** tightens them to `NOT NULL` behind a precondition that
>    refuses rather than dropping unattested rows. The governing principle, and it is the general one: **a
>    migration migrates its data; it does not drop it.**
> 2. **The one-time program is not committed** (D8's workflow and script are deleted). That contradicted the
>    relational migration's own **D13a** — "the cutover's programs are throwaway, and are not committed…
>    they live in a scratchpad, run through `proton-env`" — which this design cited elsewhere and then did
>    not follow. It also no longer deletes the `.attest.json` objects: while the previous bundle is live it
>    is still reading them to renew, so they stay, alongside the other legacy objects D13 left in place.
>
> A third defect was found the same way: `migrate.ts` resolved config through `readSweepConfig`, which
> demands the storage access key that D7 deliberately withholds from the deploy workflow — so the first
> deploy failed on a credential the step was right not to hold. It now uses a database-only reader.

## Context

The relational migration (`changes/archive/2026-08-25-record-uploads-in-database`) moved every relational
fact out of the object store and left exactly one behind, as a stated non-goal:

> Moving the attestation record `devices/<deviceId>.attest.json` into the database. It stays an object,
> owned by `device-attestation`; only the *config* object becomes a row.

and named the follow-up in its open questions:

> **Attestation records stay objects.** If a later change moves them, the fully-orphaned collection rule in
> `scheduled-cleanup` becomes wholly relational; today it stays mixed.

That is this change. The store is live and backfilled, so this runs against a populated database — unlike
the migration that created it.

**Current state.** `app.ts` imports exactly two storage primitives, `putObject` and `readObjectText`, and
uses them at exactly two call sites: writing the attestation record after a successful attestation, and
reading it during renewal. Everything else it touches is a byte or a row. `storage.ts` still carries
`deviceAttestKey`, `AttestRecord`, and `AttestEnvironment` for those two sites. The sweep's device phase is
mixed: it deletes a `device_records` row and a storage object side by side.

**One live defect.** `knownDevices()` is `device_records ∪ resources`. A device that attested but never
registered a push token and never uploaded has no row in either, so its attestation object is invisible to
the sweep and is never collected.

**Constraints that shape everything below.**

- The token gate reads nothing. Verifying a token is one HMAC comparison, on the streaming byte-upload hot
  path; nothing may add a per-request read to it.
- The route ordering is forced by that gate. Every route but `/api/v1/attest/*` requires a token, and a
  token requires an attestation — so on any device, attestation strictly precedes every other write.
- App Attest attests a key **once**. Minting a fresh key is the throttled path, which is why renewal is an
  assertion and why anything that provokes re-attestation is expensive.
- A device token is verified from its own signature and is valid for its full lifetime whether or not the
  backend still holds the attestation behind it.
- `:app:*` Kotlin is wiring-only and untested by law, and the world harness composes `snapSyncApp`, not the
  iOS root — so anything assembled in the root is unreachable by any test.

## Goals / Non-Goals

**Goals:**

- The Edge Script touches no non-byte object. Storage holds bytes; the database holds facts.
- The sweep's device phase is wholly relational and cannot provoke an avoidable re-attestation.
- Schema evolution has a mechanism — versioned, verified, applied by CI — instead of a one-off script per
  change.
- No client change. The shipped app's behaviour is unaltered; the only Kotlin edit relocates existing
  wiring.
- Close the `knownDevices` leak.

**Non-Goals:**

- Reclaiming the other legacy objects (`events/` markers and manifests, `devices/<id>.json` configs) left in
  place by the previous migration as its rollback path. That remains one later change's job.
- Any change to how a device obtains, stores, or renews its token. The device half of
  `device-attestation` — the shared Keychain, the accessibility class, the renewal margin — is untouched.
- Binding an attestation key to a `deviceId` first-claim-wins. That remains a stated non-goal of
  `device-attestation`.
- A general-purpose migration framework. The mechanism is the minimum that makes a schema change
  repeatable: an ordered list, a version record, and a test that it agrees with the created schema.

## Decisions

### D1 — Columns on the device row, not a table of its own

The attestation becomes four columns on the existing per-device row rather than a `device_attestations`
table. One row per device, two independently-written column groups.

*Why.* There is exactly one attestation per device and exactly one push registration per device; a separate
table would model a 1:1 relationship as a join, add a second delete to every collection path, and give the
sweep two roster queries where it needs none.

*Alternative considered — a separate table.* It would have kept the two writers on separate rows, avoiding
any possibility of one clobbering the other. Rejected because the same property is obtained by each upsert
naming only its own columns, which the existing statements already do, and because the recent push-token
change established the taste: declare the columns and let `STRICT` type them rather than bury the shape in
a parser.

### D2 — `device_records` is renamed `devices`

*Why.* With attestation in it, the table no longer records a push registration; it records a device. A name
that describes only one of the two facts it holds is the kind of thing that reads as authoritative and
isn't.

*Cost, accepted.* A rename cannot be rolled back by reverting the bundle: old code writes a table that no
longer exists, so device-config writes and renewals `502` until the store is migrated back. It also creates
a short deploy window — migrate-then-publish leaves the old bundle briefly talking to the renamed table.
Both affected routes are retried by the device and neither can lose a photo.

*Alternative considered — add columns without renaming.* Preserves revert-the-bundle exactly. Rejected
because the misleading name is permanent and the rollback is worth less than it looks: the device recovers
from a lost attestation record by re-attesting, so the rollback path was never load-bearing for this fact.

### D3 — A row exists if and only if the device has attested

The attestation route inserts; every other device-scoped write updates. `PUT /devices/:deviceId` answers
`401` when it affects no row.

*Why this is safe without a client change.* The shipped client already treats any `401` as "our credential
is dead": the shared HTTP client's interceptor calls the trust feature's rejection entry, which drops the
token and triggers a refresh; the refresh finds no record, so renewal is refused and a full attestation
runs, which creates the row; obtaining a new token emits on the trust feature's token-changed flow, and the
push feature re-sends the registration the `401` had lost. The loop closes with no new status code and no
new branch.

*Alternative considered — `409`/`404` plus a client change.* Keeps `401` meaning one thing. Rejected on
version skew: builds already in TestFlight ignore an unknown status, and the app writes its registration
once per OS-delivered token, so those devices would go unregistered until the next launch redelivered one.

*Alternative considered — the config route creates the row.* Impossible without inventing an attestation.

*Where the check does not go.* Not in the gate. Verifying a token touches no storage, and that is what keeps
the byte-upload path free of a round-trip; the route reads its own record after the gate has passed.

### D4 — The row records the minted token's expiry, and the sweep waits for it

A `attest_token_expires_at` column, written by both mint routes. The sweep collects a device's row only when
it holds no membership **and** that expiry has passed.

*Why the expiry and not a mint time.* The token literally carries its expiry, and that is what the backend
compares on every gated request; storing anything else re-derives it. It also makes the row immune to a
later change in the configured lifetime — the same reason `event-limits` stamps `lifetime_seconds` per
event rather than reading a constant at deletion time.

*What it prevents.* Without it, three otherwise-reasonable decisions compose into a silent defect. Every
event has a stamped lifetime of at most thirty days, so **every user is orphaned between events**. The sweep
collects an orphaned device's row; the device still holds a valid token, so its next launch writes a push
registration, gets `401`, and completes a **full Apple attestation** — a fresh Secure-Enclave key on the
throttled path. The following night it is orphaned again. The result is one attestation per launch-day, for
as long as the device stays between events, with no error anywhere until Apple throttles it and the device
shows `Unattested` one capability away from the cause.

*What it costs.* Renewal becomes read-write. That route is not on any hot path — gated verification still
reads nothing — and the write is one statement.

*The residual, accepted.* A device woken at least once inside every renewal margin while a member of nothing
renews indefinitely, so its row is retained indefinitely. It is a live client, and the alternative — a
retention bound not tied to credential liveness — reintroduces the cycle above.

### D5 — Renewal persists before it mints

A renewal that verifies but cannot record its new expiry responds `502` and mints nothing.

*Why.* `POST /api/v1/attest/token` already refuses to mint a token whose key it could not store, for the
same reason. Minting first and writing after would hand out a token the store understates, and the
understatement is exactly what D4 protects against — the sweep would collect a device that is still using
its credential. Renewal is attempted at every wake, so a refusal is retried within hours.

### D6 — Schema evolution is a verified pair, not a script

`SCHEMA` remains the created shape. A new ordered `MIGRATIONS` list evolves an existing store, recorded in
the store so re-application is a no-op. A test builds one store from each and asserts the schemas are
identical.

*Why both forms.* They answer different questions and have different readers. The created form is how every
fresh dev and test store comes into being and is the readable statement of the current shape; the ordered
form is the only thing that can change a store holding rows. This is the device side's arrangement —
`Ledger.sq` beside `1.sqm`…`6.sqm`, with `6.sqm`'s own comment naming the property: *"the verify task
compares migrated vs created schemas."*

*Why not migrations alone.* One source of truth, but reading six migrations to learn a table's shape, and
every fresh store replaying history. The device side declined this trade; so does this.

*Why it is needed at all.* `migrate()` today is a list of `CREATE TABLE IF NOT EXISTS`, so running it
against the live store is a **no-op that reports success**. A CI step built on it would rename nothing and
report green — the precise failure `api-deploy.yml`'s own header was written about.

### D7 — CI applies migrations; the storage key stays out

`api-deploy.yml` gains `BUNNY_DATABASE_URL` and `BUNNY_DATABASE_AUTH_TOKEN` (existing repository secrets,
already used by the nightly cleanup) and applies migrations **before** publishing, failing the run without
publishing if they fail.

`BUNNY_STORAGE_ACCESS_KEY` stays out, because `backend-deployment` has a standing requirement that it is an
Edge Script environment value and *not* a deploy-workflow secret, on the grounds that bunny issues no scoped
keys. Database credentials are a third category the requirement does not name and this change adds
deliberately; the storage key would extend a compromised deploy path's reach to every user's photos.

*Consequence for this change's own migration.* Its schema step is inseparable from a data move — the values
live in the zone — so it runs once from a dispatched job holding both credentials, and records its version
so the CI runner skips it thereafter.

### D8 — The one-time migration deletes the legacy objects

The dispatched program reads each `devices/*.attest.json`, rebuilds the table with the attestation columns
`NOT NULL`, drops rows for devices with no attestation object, and deletes the objects — by **exact
suffix**, never a prefix and never a key ending in `/`, because that prefix also holds the push-config
objects and `deleteObject` deletes a directory recursively.

*Why delete rather than leave as rollback.* The rollback the objects would provide is worth little: a device
whose record is missing re-attests by itself. Leaving them adds a second reclamation story to the one the
previous migration already deferred.

*Blast radius, bounded by construction.* The `devices` table feeds none of the byte phase's inputs —
`referencedKeys` joins `event_assets` and `resources`, `activeFloors` joins `memberships` and `events`, and
the device roster comes from a storage listing. So however badly the rebuild goes, it cannot cause a byte
deletion. The worst case of a botched migration is one attestation per device.

*Rows dropped.* A `device_records` row whose device has no attestation object cannot satisfy the `NOT NULL`
columns. Those devices re-attest on their next launch, and are unreachable by push until then — which notify
already treats as ordinary. The migration logs how many it dropped.

### D9 — `knownDevices` and `storeIsEmpty` are deleted

With one collection rule, the device phase is a single predicate — no membership, expiry passed — expressed
as a query rather than a roster walk. `knownDevices` existed to find devices with bytes but no config row so
their *attestation object* could be collected; that object is now a column on the row, so the union has
nothing left to find. Deleting it also closes the leak described in Context.

`storeIsEmpty` — the refusal to sweep when the database holds no rows while the zone holds bytes — is
removed. It is code-only, with no requirement behind it. **This is a deliberate reduction in safety and is
recorded as such**: nothing then stands between "the store says nothing is referenced" and deleting every
byte in the zone. The guard covered only the empty-store case and never the wrong-but-populated-store case,
and the state it was written for — a store awaiting its first backfill — is past. A deletion budget was
considered as a strictly stronger replacement and declined for this change.

### D10 — The push-registration join moves into the shared composition

`PushRegistration` is constructed in the iOS root today, and the subscription that re-sends a refused
registration after a fresh token is wired there too. It moves to an installer on the composed core, beside
`installPermissionSubscriptions()`, invoked by the root.

*Why it rides along.* D3 makes that retry the reason a `401` on the config route is safe. It is a join
between two mutually blind features — trust emits, push consumes — and joins are behaviour. Left in the
root it is untestable by law and invisible to the world harness, so nothing would observe its removal.

*What cannot move, and gets a guard instead.* The rejection hook itself. The composed core's ports are built
over the HTTP client and the client reads its credential from the core — a construction cycle broken by two
lazy bindings — and `:domain` is platform-free, so it cannot build the platform client. Something outside
must hand the callback in, and that is the shell by definition. A `:test:architecture` pin asserts the route
is still connected, in the shape `KotlinShellGuardTest` already uses.

### D11 — The dev rig fills an absent ENROLMENT, not just an absent token

The local rig's fallback bearer attaches a dev token to a request that carries none. It now also enrols the
device the path names on `PUT /api/v1/devices/<id>`.

*Why this is required rather than a convenience.* D3 makes the push registration an UPDATE that answers
`401` when the device has no attestation record. On a physical device that is a first-launch round-trip:
the `401` drops the token, the app attests for real against the rig, and re-registers. **On a simulator it
is unrecoverable** — App Attest does not exist there (`DCAppAttestService.isSupported` is false, recorded
in the `ios-simulator` runbook), so `DeviceAttestation.refresh` returns early without attesting and the
registration `401`s forever. Without this, the simulator rig — the host for two-members-in-one-event
testing — loses push registration permanently, and `notify` silently skips every simulator member.

Supplying a credential without the enrolment it implies is half a credential, so the fallback supplies
both, in the same place and on the same condition. A caller carrying its own token is untouched.

The path matcher is extracted to `src/dev/fallback.ts` and unit-tested, because its failure is silent: a
matcher that stops matching restores the permanent `401` with every test still green.

## Risks / Trade-offs

- **The rename forfeits revert-the-bundle rollback** → Recovering means migrating the store back, which the
  ordered migration list makes a written step rather than an improvisation. The affected routes are retried
  by the device and cannot lose a photo.
- **A deploy window where one bundle talks to the wrong table name** → Migrate then publish; the exposure is
  the publish latency, on two retried routes. No ordering avoids it, which is the honest cost of D2.
- **`storeIsEmpty` is gone** → Accepted with no mitigation. Bounded for *this* change by D8's blast-radius
  argument, but it is a standing reduction for every future failure.
- **A failover loses a renewal's expiry write** → The sweep may collect a device holding a live token. It
  recovers on the next device-scoped write: one `401`, one attestation, no photo affected. This is the
  failure mode D4 reduces from routine to disaster-only, not one it eliminates.
- **Devices whose attestation object is missing lose their row** → They re-attest at next launch. Push is
  unreachable until then, which notify already treats as ordinary.
- **Indefinite retention for a device woken inside every renewal margin while a member of nothing** →
  Accepted. Any tighter bound not tied to credential liveness reintroduces the re-attestation cycle.
- **The deploy workflow now holds database credentials** → Scoped deliberately to the database; the storage
  access key stays excluded, so a compromised deploy path cannot reach the photo zone.
- **A simulator can never attest, so it can never enrol itself** → The rig enrols it (D11). Anything else
  pointed at the deployed backend without App Attest — there is nothing today — would have no such
  recovery, which is the same posture the deployed backend already takes toward an unattested caller.
- **The migration both reads and deletes in the `devices/` prefix, which also holds config objects** →
  Exact-suffix matching only, no prefix deletes, no trailing slash; the read happens before any delete, and
  the program is re-runnable.

## Migration Plan

1. **Land the code** with the migration list, the new table shape, both mint routes writing the expiry, the
   config route's `401`, the rewritten sweep phase, and the composition move — behind no flag; the deployed
   store is migrated in step 3 before the bundle that needs it is published.
2. **Add the CI migration step** to `api-deploy.yml` with the database credentials, ordered before publish.
3. **Run the one-time dispatched job** (dry-run first): read every `devices/*.attest.json`, rebuild the
   table, drop rows with no attestation data, delete the objects by exact suffix, record the version. Retain
   its output; it reports the dropped count.
4. **Publish** the bundle. Renewals begin recording expiries immediately; devices whose rows were dropped
   re-attest on their next launch.
5. **Watch the first nightly sweep.** It should collect only devices with no membership and a passed expiry.

**Rollback.** Reverting the bundle requires migrating the store back — the rename is not backward
compatible, and the legacy objects are gone. The recovery for attestation specifically is that devices
re-attest by themselves; the recovery for the rename is the reverse migration.

## Open Questions

- **The dispatched job's dry-run output is the only preview of how many rows get dropped.** If that count is
  larger than expected, is dropping still the right answer, or is a nullable-then-tighten sequence worth the
  extra deploy? Resolvable only against the real store, at step 3.
- **A deletion budget for the sweep** was declined here as out of scope. It remains the strictly stronger
  replacement for what D9 removes, and is worth its own change if the sweep's blast radius is revisited.
