# Local gates

The local half of what gates a merge on `main` (contract: `~/.claude/skills/ship/hooks.md`). They
cover the gates of `ci.yml` (each a required check, beside `check-label`) that a Linux machine can
actually run:

- `build` → `./gradlew build`, which also carries the diagrams freshness half
  (`:tools:diagrams:test`), the detekt complexity tiers, and the `:test:architecture` guards
- `metadata` → its spec validation (`openspec validate --specs --strict`)
- `api-test` → the deno set below

The rest stay CI-only BY DECISION, not by oversight:

- `ios-build` / `journeys (ios)` — macOS-only; no Linux machine can run them
- `android-build` / `journeys (android)` — minutes of emulator and R8 per ship; CI runs them
  (`./gradlew androidPlatformTest`, `scripts/android-journeys`)
- `check-label` — `/ship` applies the label as it opens the PR
- `metadata`'s other steps — `scripts/resolve_deployment_test.py`,
  `scripts/await_photo_migration_test.py`, and the listing validation, which needs the pinned `asc`
  binary fetched over the network
- `migration-rehearsal`, `site-build`

Order is cheapest-first, so a failure arrives as early as it can. The openspec CLI is pinned to the
version CI runs (`.github/workflows/ci.yml`, `metadata`); there is no global `openspec` binary and
there should not be one.

## No un-archived openspec changes
```bash
npx --yes @fission-ai/openspec@1.13.2 list --json | jq -e 'if (.changes | length) == 0 then true else error("Cannot ship with un-archived openspec changes: " + ([.changes[].name] | join(", ")) + ". Archive them before shipping.") end' > /dev/null
```
`openspec list` PRINTS un-archived changes and exits 0 either way, so the assertion has to be
explicit. Two traps, both measured (1.5.0 on 2026-09-08, the `--json` shape re-checked on 1.13.2,
2026-09-29): `--json` returns an OBJECT — `{"changes": [...], "root": {...}}` — so a bare
`length == 0` counts its keys (2) and fails a clean tree; and a silent `jq -e` would fail the ship
while saying why nowhere, which is why the failure is a jq `error(...)` naming the changes.

## Spec validate
```bash
npx --yes @fission-ai/openspec@1.13.2 validate --specs --strict
```

## API test
```bash
cd api && deno fmt --check && deno lint && deno task check && deno task test && deno task schema:check && deno task bundle
```
`deno task check` and `deno task test` chain `deno task config` themselves, so the resolved
deployment exists before anything reads it. Every output lands in a gitignored path (`api/dist/`,
`api/src/deployment.ts`), so this leaves no tracked change behind. The one CI step omitted is the
bundle's `github.sha` stamp grep, which can mean nothing off a runner.

## Build
```bash
./gradlew build
```