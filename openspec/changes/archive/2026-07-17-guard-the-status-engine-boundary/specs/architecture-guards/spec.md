## ADDED Requirements

### Requirement: The status module never names an engine type

`:test:architecture` SHALL assert that no production source file under `:domain:status` references
`app.snapsync.engine` — neither an import nor a fully-qualified `app.snapsync.engine.…`.

The invariant it holds is `sync-status`'s: status was freed from the ledger by `ledger-free-status`, and
nothing structural keeps it free. Engine **is** on status's compile classpath — `:domain:gallery`
`api`-exports it, because `GalleryResourceEnumerator.enumerate()` returns `List<Resource>` — so a status
file importing `LedgerWriter` compiles today. Verified by probe, not assumed. The dependency graph cannot
express this rule while gallery's public API returns an engine type, which is exactly the case
`architecture-guards` exists for.

The guard SHALL match **source text**, so that a fully-qualified reference importing nothing is caught, and
SHALL assert its own scope is non-empty: a Konsist guard that scans no files passes, and a guard that
silently stopped guarding is worse than none, because it reports success.

It SHALL NOT attempt to forbid status *using* an engine type by inference. `OwnDeviceGalleryStatusSource`
consumes `List<Resource>` from the enumeration seam and names nothing — that is the seam working as
designed, and no rule here forbids it. What is forbidden is status naming engine, which is what reaching
for the ledger looks like.

#### Scenario: A status file importing a ledger type fails the build
- **WHEN** a file under `:domain:status` imports `app.snapsync.engine.LedgerWriter`
- **THEN** the guard fails and names the file

#### Scenario: A fully-qualified engine reference is caught too
- **WHEN** a file under `:domain:status` writes `app.snapsync.engine.LedgerBackend` without importing it
- **THEN** the guard fails — it reads source text, not the import list

#### Scenario: Consuming the enumeration seam is not a violation
- **WHEN** status calls `enumerator.enumerate(cutoff)` and reads the returned resources' fields, naming no engine type
- **THEN** the guard passes: the type arrives by inference through gallery's public API, which is what that API is for

#### Scenario: The guard proves it scanned something
- **WHEN** the guard runs
- **THEN** it asserts `:domain:status`'s production sources were in scope, so it cannot pass by scanning nothing
