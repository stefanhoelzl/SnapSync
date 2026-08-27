# Probe findings — add-v2-device-api

Measurements this change's decisions rest on. Each records what was run, against what, when, and what
would falsify it. A claim not measured here is not measured anywhere in this change.

**Method.** Read-only, table-free where possible, against the **deployed** bunny Database via
`proton-env` (`BUNNY_DB_URL` + `BUNNY_DB_READONLY_TOKEN` — the read-only token, so "it wrote nothing" is
structural rather than a promise). Local comparisons use Deno's built-in `node:sqlite`, the engine the
tests and the local rig run.

---

## 1. JSON1 is available on the deployed store, and the inclusion idiom works

**Measured 2026-08-27**, deployed store, read-only.

```
sqlite_version      3.45.1
json_valid          ✓
json_array_length   ✓
json_each           ✓
inclusion idiom     A → complete, B → incomplete
```

The idiom is the one the union's completeness check uses — `json_each(ea.roles)` with a correlated
`NOT EXISTS` against `resources`. It was run as a `WITH … VALUES` CTE, so it read no user table.

**Why it was measured rather than assumed.** JSON1 has been built into SQLite by default since 3.38, so
"it's there" was the safe guess — but this store has already contradicted stock SQLite twice
(`PRAGMA foreign_keys` defaulting to `1`; a bare `TEXT PRIMARY KEY` accepting `NULL`), both recorded in
`changes/archive/2026-08-25-record-uploads-in-database/PROBE-FINDINGS.md`. A design resting on an
unmeasured guess about this engine has a poor record.

**Expiry trigger.** bunny upgrading libSQL, or a query reaching for JSON syntax added after 3.45.1.

## 2. The deployed SQLite is OLDER than the one CI tests against

```
deployed (bunny libSQL)   3.45.1
local / CI (node:sqlite)  3.53.2
```

**This is the dangerous direction.** A query using anything added after 3.45.1 passes the entire test
suite — because CI runs the newer engine — and fails only in production. CI cannot catch it, and nothing
in the pipeline compares the two.

Nothing in this change needs anything newer: `json_each` and `json_group_array` are 3.9, `STRICT` is 3.37,
upsert is 3.24, `RETURNING` is 3.35. Worth noting how close the margin can get, though — `ORDER BY` inside
an aggregate (which `group_concat(role ORDER BY role)` would have needed, had completeness been done that
way) landed in **3.44**, clearing the deployed floor by a single minor version.

**Recorded as a standing hazard, not enforced.** Reporting the version from `/health` and asserting a
floor in the boot probe was considered and declined as machinery guarding a hypothetical.

**Expiry trigger.** Either engine moving; or a query reaching past 3.45.1, at which point this stops being
a note and becomes a blocker.

## 3. Pre-migration survey of the live store

**Measured 2026-08-27**, deployed store, read-only, aggregate counts only — no filenames and no user
content were selected.

```
scale        972 resource rows · 13 devices · 591 event_assets · 25 memberships · 15 events

duplicate (device_id, asset_id, role)     none          ← the gate on the new primary key
placeholders (asset_id = '')              0
uploaded = 0 rows                         0
role distribution                         primary 756 · live 216
keys not matching <asset>-<role>.<ext>    0
event_assets with no matching resources   0
```

### What each result settles

**No duplicates → the new primary key holds.** `(device_id, asset_id, role)` collides nowhere in the
store.

⚠️ **But read the scale before reading that as proof.** 13 devices is the operator plus a handful of
TestFlight testers — in diversity terms closer to a few device libraries than to an install base. The
one-resource-per-`(asset, role)` invariant now has **972 supporting observations instead of zero**, which
is meaningfully better than the `n=1` a device probe would have returned, and is still **not proof**. An
asset carrying two of `{photo, video, audio}` — the three PhotoKit types that collapse to `PRIMARY` — may
simply never have occurred in this sample. It remains a client-upheld invariant the backend cannot verify;
see `database`'s "Five tables" requirement, which states that dependency rather than hiding it.

**Zero placeholders** → they are transient, as designed: v1's manifest fills them in the same cycle. The
migration therefore has nothing to parse under `asset_id = ''`, and v1's byte route can go straight to
real identity.

**Zero `uploaded = 0`** → the column was already dead weight. "Row existence means the bytes arrived" is
not a reinterpretation of the data; it is a description of it.

**Every key parses** → 972 of 972 match `<assetId>-<role>.<ext>`. This is what licenses the v1 key-parse
shim (`src/legacy-v1.ts`): it is a measurement of the format, not a guess about it. It also bounds the
narrowing that shim introduces — refusing an unparseable key affects **no** row that exists today.

**Zero `event_assets` without resources** → the silent drop the old inner-join union could produce is a
real hazard that has never fired. The `LEFT JOIN` replacing it is therefore **prophylactic, not a bug
fix**, and should not be described as one.

**Expiry trigger.** The store changes continuously. The duplicate check in particular must be re-run
immediately before the migration runs — it is the only result here that can still invalidate the schema,
and a device could create a colliding pair at any time.
