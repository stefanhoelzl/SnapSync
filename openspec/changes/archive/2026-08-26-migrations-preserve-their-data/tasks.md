## 1. The gate (design.md D3)

- [x] 1.1 Add a test over `MIGRATIONS` asserting that a migration containing `DROP TABLE <x>` also
      contains an `INSERT INTO … SELECT … FROM <x>` earlier in the same migration
- [x] 1.2 Add a test asserting that a migration declaring a column `NOT NULL` which an earlier migration
      left nullable also declares a `precondition`
- [x] 1.3 Prove both fail for the right reason — run each against a deliberately bad migration fixture, so
      a gate that can never go red is not mistaken for a property that always holds
- [x] 1.4 State in the test's own comment what it cannot catch (a copy naming the wrong columns, or one
      filtered by a `WHERE`), so the next reader does not over-trust it

## 2. Specs

- [x] 2.1 Sync the two `database` requirements into `openspec/specs/database/spec.md`
- [x] 2.2 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict`

## 3. Fold the prose back

- [x] 3.1 Point `api/src/migrations.ts`'s `V2`/`V3` comments and the `precondition` KDoc at the
      requirement now that one exists, rather than restating the rule in three places
- [x] 3.2 Trim `api/README.md`'s migration paragraph to what a reader of that file needs, deferring the
      rule itself to the spec

## 4. Close-out

- [x] 4.1 `deno task check`, `deno task test`, `deno fmt --check`, `deno lint`
- [x] 4.2 `./gradlew build`
