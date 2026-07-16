## Why

`sync-status` contains a test somebody wrote in English and nobody ever ran:

> #### Scenario: Status compiles without the engine
> - **WHEN** `:domain:status` is compiled
> - **THEN** `:domain:engine` is not on its compile classpath and no ledger type is reachable from status code

It is **half true, and the false half is the half it asserts**.

**True**: `domain/status/build.gradle.kts` declares no engine dependency, and status imports zero engine
types (`grep app.snapsync.engine domain/status/src` → nothing). `ledger-free-status` dropped that
dependency on 2026-06-29 and it has stayed dropped.

**False**: engine *is* on status's compile classpath, and any ledger type *is* reachable. `domain/gallery`
declares `api(project(":domain:engine"))` — necessarily, because `GalleryResourceEnumerator.enumerate()`
returns `List<Resource>` and `Resource` lives in engine — and status depends on gallery. `api` propagates.
Proven rather than argued: a probe file importing `LedgerWriter` into `:domain:status` **compiles**.

Nothing caught it because nothing ran it. The scenario asserts a classpath fact — the most mechanically
checkable kind of claim there is — and lived for weeks as prose.

**The requirement's substance is sound; only its scenario overreaches.** The leak being plugged is
`engine → presentation`, and that holds: status→gallery is `implementation`, so engine stops at status, and
the *second* scenario ("Presentation compiles without the engine or gallery") is true. What cannot hold is
status being engine-free, while gallery's public API returns an engine type.

So: state the invariant that is **true and enforceable**, and mechanize it. Status declares no engine
dependency and names no engine type. That is what `ledger-free-status` actually bought, it is what stops a
future status file from importing `LedgerWriter`, and — unlike the classpath claim — a guard can hold it.

## What Changes

- **`sync-status`**: the scenario stops claiming engine is off the classpath and states what is true and
  guarded — status declares no engine dependency and references no engine type. The requirement records
  *why* the classpath is not clean (gallery's API returns `Resource`), so the next reader does not
  "restore" the stronger claim and find it already false.
- **`architecture-guards`**: gains a rule — no `app.snapsync.engine` reference in `:domain:status`
  production source. Konsist, same shape as `KeychainContainmentTest`, including its vacuity check.
- **No code changes.** The guard passes on the first run: status references engine zero times today. It is
  a ratchet, not a repair.

## Impact

- **Affected capabilities**: `sync-status` (one scenario corrected), `architecture-guards` (one rule added).
- **Affected code**: `:test:architecture` only — a new guard. Nothing in `domain/` moves.
- **What this deliberately does not do**: make the classpath claim true. That needs `Resource` out of
  `:domain:engine` into a leaf both engine and gallery depend on. It is arguably the better layering — and
  it is a real refactor: `Resource` is imported by `:capability:upload`, `:capability:upload-url`,
  `:capability:membership`, both iOS upload tiers and their tests, and engine is currently a leaf. That is
  a lot of risk for an invariant **no code violates today**, and the guard makes the violation impossible
  to introduce accidentally in the meantime. Recorded as the open question it is, rather than closed by
  silence.
- **The guard is weaker than the sentence it replaces, and knowingly.** It reads source text, so it sees an
  `import` or a fully-qualified `app.snapsync.engine.X`. It does **not** see status consuming `Resource`
  through type inference from `enumerate()` — which status does, legitimately, and which no rule here
  forbids. What it catches is the thing the requirement was written to stop: a status file reaching for the
  ledger.
