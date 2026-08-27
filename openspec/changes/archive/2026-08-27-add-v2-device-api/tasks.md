## 1. Split the test suite (no behaviour edits)

Done first so the schema work that follows it lands against a `v1.test.ts` that is already in place —
what changes there afterwards is then visibly small, and reviewable as such. The whole change ships as
ONE pull request; this group is a separate commit within it, not a separate PR.

- [x] 1.1 Move the eleven route sections of `api/test/app.test.ts` into `api/test/v1.test.ts` verbatim
- [x] 1.2 Leave the version-neutral sections in `app.test.ts` — `GET /health`, the auth gate, the
      lifecycle derivation, presigned URLs
- [x] 1.3 Lift shared machinery into `api/test/support/` — store creation, `migrate`, the fetch recorder,
      token minting — but leave paths, bodies and expected shapes literal in each version's file
- [x] 1.4 Lift the three `INSERT INTO resources` seed sites into a `support/` helper, so the schema change
      touches one place instead of three test bodies
- [x] 1.5 Confirm the full suite passes with **no assertion edited**, and that the test count is unchanged

## 2. Migrate the schema

- [x] 2.1 Re-key `resources` to `(device_id, asset_id, role)`, keeping the stored object name as a column
- [x] 2.2 Retire the `uploaded` column; row existence becomes the record that bytes arrived
- [x] 2.3 Add `roles` to `event_assets`, holding the JSON array of roles the manifest declares
(The live cutover itself is group 7 — it runs after the PR merges, because the deployed entrypoint does
not migrate at boot.)

## 3. Adapt v1 onto the new schema

- [x] 3.1 Add the key parse shim (`<assetId>-<role>.<ext>` → identity), confined to the v1 adapter, with a
      test pinning it against the same examples the Kotlin implementation uses
- [x] 3.2 v1 byte route: parse identity from the key, record the resource; reject a key that does not
      parse (a deliberate narrowing — no real client produces one)
- [x] 3.3 v1 manifest route: replace the asset set, populate `roles` from the resource entries it already
      carries, and **keep** its two legacy writes — reactivating the membership, and repairing a missing
      resource row (an entry that does not say otherwise creates the row; one that says not-uploaded never
      deletes an existing row, which is monotonicity under the new schema)
- [x] 3.4 v1 byte route: **keep** the record best-effort — a database failure still answers the storage
      outcome, because v1's repair path is what makes that collapse safe and it is preserved
- [x] 3.5 v1 listing: serve the stored object names from the new schema, `{filename, url}` unchanged
- [x] 3.6 v1 union: completeness by declared-role inclusion, producing the same answers as before
- [x] 3.7 Update the sweep's queries for the new schema, preserving which bytes it collects
- [x] 3.8 Confirm every **wire-contract** test in `v1.test.ts` passes unmodified — if one needs editing,
      the adaptation is wrong
- [x] 3.9 Re-express exactly the **five** tests that assert the retired `uploaded` column, each to the
      same fact in row-existence terms, and list them in the change so an edited test stays
      distinguishable from a changed behaviour. (Design.md predicted four — it missed the sweep test's
      own direct seed, which lives in `test/scripts/sweep.test.ts` rather than `v1.test.ts`.)

## 4. Mount v2 and gate it

- [x] 4.1 Extract the `/api/vN` prefix normalization into one shared helper used by every middleware
- [x] 4.2 Add the version gate as top-level middleware registered **before** the auth gate, acting only on
      v2 (middleware on the v2 router would run after the token check — the wrong order)
- [x] 4.3 Add the minimum version to source configuration, pinned by a test
- [x] 4.4 Implement two-part numeric version comparison, with a test case where string and numeric
      ordering disagree (`0.10` vs `0.9`)
- [x] 4.5 Refuse below-minimum, absent and unparseable versions with `426` carrying the required minimum
- [x] 4.6 Mount `/api/v2` as a second router, leaving v1's registration untouched

## 5. Build the v2 surface

- [x] 5.1 Byte upload: identity from `<assetId>/<role>`, required `?filename=`, role validated against the
      closed vocabulary
- [x] 5.2 Compose the stored object name backend-side, byte-identical to what v1 composes for the same
      resource — verify by uploading the same asset under both versions and asserting one object
- [x] 5.3 Fail the request when the resource cannot be recorded; the write is no longer best-effort
- [x] 5.4 Per-device listing returning `assetId`, `role`, `filename` and no `url`
- [x] 5.5 Explicit join route, idempotent, owning the `409` full / `404` absent decision
- [x] 5.6 Manifest route at `…/manifest`: full-state replace only, refusing a non-member, creating no
      membership
- [x] 5.7 Union completeness by role-set inclusion via `json_each`, with a test covering the case count
      equality gets wrong (device holds two roles, event declares one)
- [x] 5.8 Resolve fan-out recipients in one join over memberships and device records
- [x] 5.9 Fan out after the transaction commits, on every accepted publish, bounded so a stalled
      connection cannot delay the response
- [x] 5.10 Serve no notify route under v2

## 6. Verify and record

- [x] 6.1 `v2.test.ts` covering every v2 route, the version gate, and the shared-object-name property
- [x] 6.2 Confirm the v1 and v2 tables are each closed — a v1-only path under v2 is `404` and vice versa
- [x] 6.3 Record in `PROBE-FINDINGS.md`: the pre-migration survey (972 rows, 13 devices, no
      `(device_id, asset_id, role)` collisions, no placeholders, no `uploaded = 0`, every key parsing),
      noting the sample is 13 devices rather than a population
- [x] 6.4 Record the deployed SQLite floor (3.45.1, measured read-only) and that the test engine is
      **newer** (3.53.2), so CI cannot catch a post-3.45 feature; expiry trigger is a bunny upgrade or a
      query reaching past the floor
- [x] 6.5 Confirm the boot probe passes locally against a freshly migrated store

## 7. Ship

THE LIVE MIGRATION CARRIES ITSELF. `api-deploy.yml` already asks `migrate.ts --pending` on every deploy
to `main`, and a pending migration makes it publish the maintenance bundle, migrate, then publish the real
one — restoring the previous bundle if anything fails. v4 is an entry in `MIGRATIONS`, and `--pending`
reads that same list through the same `pendingMigrations()` the runner uses, so this change is carried by
that pipeline with no separate job and no schema to reconcile by hand.

Two things that were open before this was understood are therefore closed: there is no window in which
new code meets an old schema (the migration precedes the publish, and `/api/*` answers `503` in between),
and the duplicate check that guards the new primary key runs as v4's `precondition` against the live store
moments before the rebuild — rather than as a survey run by hand and assumed still true.

- [x] 7.1a v4 is in `MIGRATIONS`, and `SCHEMA` agrees with it (`migrations.test.ts`: the created and
      migrated schemas are identical)
- [x] 7.1b v4 refuses rather than drops — its `precondition` rejects duplicate `(device_id, asset_id,
      role)` identities and any surviving placeholder row
- [x] 7.1c Confirm the live bundle's archive exists, so the pipeline can open a window it is able to lift.
      `api-deploy.yml` reads the live `sha` from `/health` and refuses to open one when no unexpired
      `bundle-<sha>` artifact is present — fatal BEFORE any outage, but it is the gate that decides
      whether the merge proceeds at all, and it is the one step not verifiable from the tree alone

Shipping and post-deploy verification are deliberately NOT tasks here. They are how this change reaches
production, not part of what it builds — and a task that cannot pass until after the merge would sit
incomplete in the archived record forever, reading as abandoned work rather than as process.
