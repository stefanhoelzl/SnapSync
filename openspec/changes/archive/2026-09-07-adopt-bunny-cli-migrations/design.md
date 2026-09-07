## Context

The relational store (capability `database`) is migrated by a hand-rolled mechanism introduced with the
store itself: an ordered list of TypeScript `Migration` objects in `src/migrations.ts`, a runner, a
`schema_migrations` version table, and `src/scripts/migrate.ts`, whose three-outcome exit code decides
whether `api-deploy.yml` opens a maintenance window. Four migrations have shipped. `db.ts` additionally
carries `SCHEMA`, a hand-written statement of the current shape, bound to the migration list by a verify
test.

bunny now ships `bunny db migrations` (self-labelled *experimental*), which applies numbered `.sql`
files against the same store and records them in `__bunny_migrations` with checksums. Critically it
authenticates with `--url`/`--token` — the two database secrets CI already holds — so adopting it needs
no bunny **account** key and does not widen the deploy path's reach, which `backend-deployment` forbids.

Everything below rests on measurements taken during design; they are reproduced in the decisions that
depend on them rather than kept in a separate findings file, because each one settles exactly one choice.

## Goals / Non-Goals

**Goals:**

- Replace the bespoke migration mechanism with the platform's, without weakening any property the
  bespoke one had.
- Gain checksum drift refusal — the hand-rolled runner tracks version numbers only, so an edited
  shipped migration applies nothing and reports success.
- Apply migrations with foreign-key enforcement genuinely off, which the current runner cannot express.
- Remove the hand-maintained second form of the schema without losing what it caught.
- Close the `SELECT *` transposition hazard, which is latent in migration v3 today.

**Non-Goals:**

- The device's SQLDelight migrations. Untouched.
- A general-purpose migration framework. The mechanism stays the minimum that makes a schema change
  repeatable.
- Rollback of a migration. There is none, here or in the CLI; a bad migration is fixed by another.
- Building guards for failures this schema cannot currently exhibit — see D12.

## Decisions

### D1 — The CLI applies in CI; the credentials do not change

`api-deploy.yml` installs `@bunny.net/cli` and runs
`bunny db migrations apply --url "$URL" --token "$TOKEN" --force`.

*Why.* It is the same two secrets the workflow already holds. `backend-deployment` requires that CI hold
no bunny account key, and this respects that exactly — the account key is needed to write an Edge
Script's environment, not to migrate a database.

*Alternative considered — keep the runner and add checksums.* It would buy drift detection without a new
toolchain, but leaves every other bespoke part in place and still cannot express D5's foreign-key
bracketing without reimplementing the wire pattern by hand.

### D2 — Migrations are `.sql` files, applied in CI by the CLI and locally by a small in-repo replayer

*Why two runners.* The CLI **cannot** target a local store: measured, `--url file:…` and `http://` are
both refused with *"Database URL must use an encrypted connection"* (`libsql://`, `https://`, `wss://`
only). The local rig, every test fixture and the schema generator therefore need their own applier. It
is small — `node:sqlite`'s `exec()` runs multi-statement SQL, so replaying a file needs no statement
splitter — and it reads the same files, so there is one source of truth.

The replayer writes `__bunny_migrations` in the CLI's own shape, so the two cannot disagree about what a
store has applied:
`(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE, checksum TEXT NOT NULL, applied_at
TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)`, with `checksum = sha256(contents.replace(/\r\n/g,"\n").trim())`
and `name` the path relative to the migrations directory. Ordering is a lexicographic sort on `name`.

*Alternative considered — fixtures and the rig build from the created form instead.* Fewer moving parts,
but an existing `.localstore/api.db` would then silently never migrate, since the created form is all
`CREATE TABLE IF NOT EXISTS`.

### D3 — v1–v4 are squashed into `0001_baseline.sql`, which also drops `schema_migrations`

Replaying v1–v4 against the deployed store is not an option: v2's `INSERT … SELECT` reads a table that no
longer exists, and v3 would rebuild `devices`. The baseline is the current schema — every statement
`CREATE TABLE IF NOT EXISTS` — so applying it to the deployed store is inert and the CLI records and
checksums it itself. No `__bunny_migrations` row is hand-seeded and the checksum algorithm need not be
reproduced.

*Measured.* The premise is verified, not argued: a read-only `sqlite_master` comparison against the live
store found all five objects identical to a replay of v1–v4 after the existing normaliser. Since the
verify test binds the created form to that replay, the baseline is provably inert.

`DROP TABLE IF EXISTS schema_migrations` is included so the retired bookkeeping does not linger as a
second, stale history that a later reader could mistake for the truth.

*Alternative considered — transcribe v1–v4 as `0001`–`0004`.* It would preserve replayable history, but
`__bunny_migrations` would need four hand-seeded rows with valid checksums, and a wrong one makes every
file read as `modified`, blocking `apply` permanently.

### D4 — Preconditions become an in-file SQL guard

A narrowing migration declares its refusal as a uniquely-named temp guard table plus a `BEFORE INSERT`
trigger raising `RAISE(ABORT, '<message>')`, fed a `COUNT(*)` of offending rows.

*Why in the file.* Under the CLI the file is the only thing that runs. A check in TypeScript would not
execute in CI at all, and a check in the replay harness would only ever meet test data — which, as the
existing gate's header already argues, is exactly what an author writing a bad migration would not supply.
The guard must run against production rows, and only the file does.

*Measured.* The message comes through verbatim, and the same idiom works as a *post*condition over
`pragma_foreign_key_check`, refusing a copy narrowed by a `WHERE` before commit.

*Trade-off, accepted.* `RAISE(ABORT, …)` takes a **string literal** — no interpolation — so unlike the
TypeScript preconditions the message cannot carry the offending count. `database` requires a refusal to
name what would satisfy it; the message therefore names the **diagnostic query** the operator should run.
Guard objects are named per-migration because an abort leaves them in the connection (their `DROP`s never
run).

### D5 — Migrations apply with foreign keys genuinely off, and the replayer matches exactly

*Why this is not optional.* SQLite treats `DROP TABLE` as an implicit `DELETE FROM`, which fires
`ON DELETE CASCADE`. Measured on the same parent rebuild: `foreign_keys=ON` → children **0**;
`defer_foreign_keys=ON` → children **0**; `foreign_keys=OFF` → children **1**. Deferring defers the
*check*, not the cascade *action*.

The current runner is exposed — `db.batch(…, "write")` inherits bunny's default of 1 — and cannot fix it
in place, because `PRAGMA foreign_keys` is a **silent no-op inside a transaction** and the batch is the
transaction. The CLI's applier calls `batch(…, {foreignKeys:false, mode:"immediate"})`, which brackets the
wire batch as `PRAGMA foreign_keys=off` → `BEGIN IMMEDIATE` → … → `COMMIT` → `PRAGMA foreign_keys=on`:
outside the transaction (the only place SQLite honours it) and inside one request (the only place the
connection is guaranteed, since the HTTP client pins a session only for interactive transactions).

**The replayer SHALL use the same bracketing and SHALL NOT use `defer_foreign_keys`.** Getting this wrong
diverges CI from local in *data* rather than in errors, and no schema comparison would notice.

*Note on motivation.* This is not an independent argument for adopting the CLI — the same fix is roughly
fifteen lines against the `Db` port. It is a worked example of the cost of the bespoke mechanism: the fix
requires deriving two non-obvious constraints and satisfying both at once, with no test able to catch
getting it wrong.

### D6 — The created form is GENERATED, committed, and checked for freshness

`SCHEMA` is removed from `db.ts`. In its place, replaying the migrations into an empty store and dumping
`sqlite_master` produces a committed snapshot; CI regenerates and fails if it differs.

*Why.* The two forms can then no longer disagree, because one is derived from the other, and no SQL
parser is needed — real SQLite does the parsing. `sqlite_master` **preserves comments verbatim**
(measured), so the generated file reads as well as the hand-written one, provided prose lives in the
migration files. The pattern is house style: `architecture/` is generated, committed, and stale output
blocks the PR.

*Why the existing rationale does not survive.* `database` justifies the hand-written form as *"how every
fresh dev and test store comes into being"*. Nothing creates from it — `harness.ts` and `serve.ts` both
replay migrations, and its only consumer is one test. The `move-device-attests-into-db` decision record
rejected "migrations alone" on two grounds, and both are now void: *reading six migrations to learn a
shape* is dissolved by D3's squash, and *every fresh store replaying history* is already what happens.

*Trade-off, accepted.* Freshness is not correctness: an author who forgets to recreate an index,
regenerates and commits gets a green CI and a smaller snapshot. The loss is visible as deleted lines in
the PR diff, which is a review signal rather than a mechanical gate — a deliberate scope choice, see D12.

### D7 — A migration's row copy SHALL NOT use `SELECT *`

*Why.* Measured: a rebuild that reorders two columns and copies with `SELECT *` silently transposes the
values — `attest_key` and `attest_env` swap — while **every** guard passes: rows carried, no orphans,
nothing vanished from the schema, and both columns are `TEXT` so no type error. Migration v3 uses
`INSERT INTO devices_attested SELECT * FROM devices` today.

This is the only failure in this design's scope that produces **wrong** data rather than missing data.
Severity is capped by `database`'s "holds only rebuildable state" — a garbled `attest_key` fails
verification, the device gets a 401 and re-attests — but detection is zero and repair is the throttled
Apple path for every affected device at once.

*Why it is cheap.* The rule is purely textual, cannot be satisfied vacuously, and D3's squash deletes
v1–v4, so it ships with no legacy exceptions.

### D8 — A deploy asserts the live store's shape, in two positions

The committed snapshot is compared against the deployed store's `sqlite_master`, split on the same
pending answer the maintenance window already branches on:

- **nothing pending → pre-flight**, before the window decision. The store should already match, so assert
  it. Fails before any publish and before any window, at **zero outage cost** — and this is where
  hand-edits land, since drift happens between deploys and involves no migration.
- **something pending → post-migrate**, after applying and before the real bundle publishes. Fails inside
  the window, which the existing rollback lifts.

*Why the split is structural.* When a migration is due, the live store legitimately does not match the new
snapshot yet, so a pre-flight assertion there would be wrong. Branching on "pending" avoids that by
construction rather than by a special case.

*Why it is needed.* `bunny db shell` is write-capable, so adopting the CLI makes hand-editing the live
store convenient for the first time; nothing else in this design would ever detect it. It is also the only
measurement of the **deployed** shape — the replay runs on `node:sqlite`, the store is libSQL, and the
repo's own doctrine holds that platform behaviour is settled by measurement.

*Measured.* Cross-engine comparison is clean: all five objects are identical after the existing `shapeOf`
normaliser, which needs only `__bunny_migrations` added to its exclusion list.

### D9 — The window decision is a thin wrapper over `bunny db migrations list --output json`

The wrapper re-emits the existing contract — `0` none, `10` pending, anything else fatal — so
`api-deploy.yml`'s branch and its "refusing to guess" reasoning are unchanged. `list` never creates the
tracking table, so the check stays read-only against a store that has never migrated.

*Why a wrapper rather than inline YAML.* The decision gates a production outage and today carries five
dedicated tests; JSON parsing in a wrapper stays unit-testable against fixtures with no network.

*A property that improves.* Today `pendingMigrations` and `migrate` are two callers of one comparison,
pinned by a test. Under the CLI, `list` and `apply` are the same binary's view of `__bunny_migrations`
and cannot disagree by construction; that test is no longer needed.

### D10 — No version pin, and no wired fallback

The CLI is installed unpinned (`npm install -g @bunny.net/cli`), and a broken CLI is a red deploy fixed
forward — the posture a failed non-migrating deploy already has.

*Why this is safer than it sounds.* Ordering protects both failure points. The pending check runs before
the live-bundle capture, so a broken CLI produces an unrecognised exit code and hits the existing fatal
branch **before any window opens**; breakage during `apply` lands inside the window, which the existing
restore path lifts.

*Stated cost.* `db migrations` is experimental and the package shipped 41 versions in six months, so a
breaking release can arrive on a merge unrelated to it. This was raised during design and the unpinned
choice was made deliberately, preferring always-current over a pin that nothing in this repo would bump.

### D11 — `--allow-drift` is never passed in CI

Refusing drift is the first reason for this change; a flag that waives it in the automated path returns
exactly what the change buys. Drift is resolved by a human restoring or renaming files.

*Stated cost.* The CLI blocks on drift only when something is pending, which is precisely when the window
is open — so a drift-blocked deploy costs a brief 503 and a rollback rather than an early red. Accepted
over duplicating drift logic into the D9 wrapper.

### D12 — Three guards are deliberately NOT built, each with a trigger

Scope was audited by (probability × severity) ÷ cost and narrowed. Each of the following is measured and
ready, and none is built now:

| deferred | why not now | trigger |
| --- | --- | --- |
| index / trigger / view continuity (a bare `DROP` must declare the loss) | the schema has **zero** standalone indexes, triggers and views | the first `CREATE INDEX` |
| table options (`STRICT`, `WITHOUT ROWID`) may not be lost | `STRICT` enforces lossless convertibility, not identity; its load-bearing scope is two `INTEGER` columns on `events` | a rebuild proposing to drop one |
| `foreign_key_check` postcondition in files that rebuild a parent | D5 removes the cascade hazard outright; what remains is an incomplete copy of a parent, and no parent has ever been rebuilt | the first rebuild of `events` or `memberships` |

All three need a per-step replay harness that reads `sqlite_master` after every file; it arrives with
whichever fires first. Until then those losses are visible in the D6 snapshot diff as a review signal.

*Why record them rather than drop them.* The measurements are the evidence a later reader needs to
re-decide, and `database` already holds that a measurement is evidence while a tool is scaffolding.

## Risks / Trade-offs

- **An experimental, unpinned CLI on the deploy path** → mitigated by ordering (D10): both failure points
  land where existing machinery already recovers. Not mitigated by a fallback, by decision.
- **The cutover deploy opens a maintenance window for a no-op migration** → brief and safe, but it
  requires the bundle-archive rollback path to be healthy going in; land a non-migrating deploy first if
  the archive is stale.
- **Two appliers over one set of files** → mitigated by the replayer writing the CLI's exact tracking
  shape (D2) and using the CLI's exact foreign-key bracketing (D5). Divergence in the latter would show up
  as data, not errors, so it is called out as a requirement rather than left to implementation.
- **The snapshot is freshness-checked, not correctness-checked** → accepted (D6/D12); losses degrade to a
  reviewable diff, and the mechanical guards have named triggers.
- **Refusal messages lose their counts** → accepted (D4); the message names the diagnostic query instead.
- **Cross-engine schema comparison is measured only for `CREATE TABLE`** → the store holds no indexes,
  triggers or views, so `CREATE INDEX` rendering is unmeasured. The first `CREATE INDEX` is simultaneously
  the deferred continuity guard's trigger and this comparison's first untested object type; re-measure
  then rather than assuming it generalises.
- **Prose loses a single home** → each table's comments live in whichever migration last rebuilt it,
  scattering over time. Re-baselining consolidates.

## Migration Plan

1. Land the `.sql` files, the replayer, the generator, the snapshot and the rewritten tests, with
   `api-deploy.yml` still using the old runner. Nothing changes for the deployed store.
2. Switch `api-deploy.yml` to the CLI in the same change: install, D9 wrapper for the pending decision,
   `apply` inside the window, D8 assertion in both positions.
3. The first deploy after the switch reports `0001_baseline.sql` as pending, opens a window, applies an
   inert migration, drops `schema_migrations`, and lifts the window.
4. **Rollback**: the existing bundle-archive path applies unchanged. If the baseline fails, the store is
   untouched (it is inert by construction, verified in D3) and the previous bundle is restored.

## Open Questions

- Where the schema prose lives once `db.ts` no longer carries `SCHEMA` — scattered across migration files,
  or a separate authored document beside the generated snapshot.
- How `0001_baseline.sql` (a frozen, checksummed input) and the generated snapshot (a moving output)
  announce their different lifecycles, given they are near-identical text on day one and will otherwise
  read as a duplicate someone should delete.
