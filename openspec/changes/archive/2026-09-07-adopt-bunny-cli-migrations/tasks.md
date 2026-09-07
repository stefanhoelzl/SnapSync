## 1. The migration files

- [x] 1.1 Create `api/migrations/0001_baseline.sql` from `db.ts`'s current `SCHEMA`, verbatim including
      its prose comments, plus a trailing `DROP TABLE IF EXISTS schema_migrations`
- [x] 1.2 Add a header comment to `0001_baseline.sql` stating that it is a FROZEN, checksummed input —
      never regenerated, never edited once applied — and that the generated snapshot is the moving output
- [x] 1.3 Confirm the baseline is inert against a store already holding the post-v4 shape (apply it to a
      store built from the old `MIGRATIONS` list and assert nothing changed)

## 2. The in-repository replayer

- [x] 2.1 Add a replayer that globs `api/migrations/*.sql`, sorts lexicographically by name, and applies
      each file whole via `node:sqlite`'s `exec()`
- [x] 2.2 Bracket each file with `PRAGMA foreign_keys=off` / `BEGIN` / … / `COMMIT` /
      `PRAGMA foreign_keys=on` — the pragma OUTSIDE the transaction. Do NOT use `defer_foreign_keys`
- [x] 2.3 Record each applied file in `__bunny_migrations` in the CLI's exact shape, with
      `checksum = sha256(contents.replace(/\r\n/g,"\n").trim())` and `name` relative to the directory
- [x] 2.4 Add a test that a rebuild of a referenced table keeps the referencing rows (the cascade case),
      and that re-running the replayer applies nothing

## 3. The generated schema snapshot

- [x] 3.1 Add a generator that replays the migrations into an empty store, dumps `sqlite_master`
      (excluding `sqlite_%` and `__bunny_migrations`) and writes the committed snapshot file
- [x] 3.2 Add a header comment to the snapshot stating it is GENERATED — regenerate, never hand-edit —
      and naming the command
- [x] 3.3 Wire the freshness check into `api.yml`'s required check set so a stale snapshot fails the PR
- [x] 3.4 Commit the first generated snapshot

## 4. Retire the hand-rolled mechanism

- [x] 4.1 Move `src/dev/serve.ts` from `migrate()` to the replayer
- [x] 4.2 Move `test/support/harness.ts` from `migrate()` to the replayer
- [x] 4.3 Delete `SCHEMA` from `api/src/db.ts`
- [x] 4.4 Delete `api/src/migrations.ts` and `api/src/scripts/migrate.ts`
- [x] 4.5 Rewrite `api/test/migrations.test.ts` against the migration FILES: the generated snapshot equals
      a replay, re-applying changes nothing, and the "migrates its data" gate runs over file text using
      string indices to preserve statement order

## 5. The new authoring gates

- [x] 5.1 Add the check that no migration file uses `INSERT … SELECT *`, failing with the file name
- [x] 5.2 Add a test proving a reordering rebuild with named columns carries values into the right columns
- [x] 5.3 Extend the "migrates its data" gate to require an in-file SQL precondition for a narrowing
      migration, and add the canonical `RAISE(ABORT)` guard form to follow
- [x] 5.4 Confirm the guard's temp objects are named per-migration, so an abort cannot collide on a retry

## 6. The pending-check wrapper

- [x] 6.1 Add a wrapper that runs `bunny db migrations list --url --token --output json` and re-emits the
      existing contract: exit 0 = none pending, 10 = pending, anything else fatal
- [x] 6.2 Have it print one greppable plan line, as `migrate.ts --pending` did
- [x] 6.3 Add unit tests over recorded JSON fixtures, with no network

## 7. The deploy workflow

- [x] 7.1 Add an `npm install -g @bunny.net/cli` step (unpinned, per design D10)
- [x] 7.2 Replace the pending-check step with the wrapper, keeping the existing three-outcome case
      statement and its "refusing to guess" message
- [x] 7.3 Replace the migrate step with
      `bunny db migrations apply --url --token --force`, without `--allow-drift`
- [x] 7.4 Add the shape assertion as a PRE-FLIGHT step, before the live-bundle capture, running only when
      nothing is pending
- [x] 7.5 Add the shape assertion as a POST-MIGRATE step, after apply and before the real bundle publishes
- [x] 7.6 Reuse `shapeOf`'s normaliser for both, adding `__bunny_migrations` to its exclusion list

## 8. Verify and land

- [x] 8.1 Run `deno task check`, `deno task lint`, `deno task fmt` and `deno task test` in `api/`
- [x] 8.2 Confirm the test task still carries no `--allow-net`, so no test can reach a live store
- [x] 8.3 Confirm the bundle archive holds an unexpired artifact for the currently-live commit; if not,
      land a non-migrating deploy first so the cutover's window can be lifted
- [ ] 8.4 Merge, and watch the cutover deploy: it opens a window, applies the inert baseline, drops
      `schema_migrations`, asserts the shape, and lifts the window
- [ ] 8.5 After the cutover, re-run the read-only live-schema comparison and confirm it still matches
