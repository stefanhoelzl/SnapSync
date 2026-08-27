## MODIFIED Requirements

### Requirement: Gates fail closed on novelty

Every architecture gate SHALL derive its **scope** — what it scans — from the repository's structure
at test runtime: directory listings for feature enumeration, package patterns for zones, "everything
not allowlisted" for purity. A scope SHALL NOT come from a hand-maintained inclusion list. The only
permitted scope list is loud-when-stale: the per-zone library allowlists.

A gate MAY pin an **expected value** — the answer its scope must produce — as a literal table, and
several must: the OS-held literals of `RuntimeIdentityTest`, the Apple-declared enum sets of
`PlatformVocabularyPinTest`, the shell suppression inventory of `KotlinShellGuardTest`. A pin is what
a guard asserts, not where it looks, and is therefore not an inclusion list. Every pin SHALL carry
the reason its value is fixed and what would change it.

The module set is **no longer** a permitted scope list. Its expected value is derived from
`module-architecture`'s own enumeration at test runtime, so the spec is its single home; a gate
holding a second copy tethers the build to itself and leaves the spec unwatched.

Every gate SHALL keep a non-vacuity twin proving it scanned a non-empty scope. Where a gate derives
several groups from one source, it SHALL keep a twin **per group**: a reword that empties one group
leaves the others making the gate look alive. Zone gates SHALL match source text (fully-qualified
references import nothing), not import lists.

#### Scenario: New code is born in scope
- **WHEN** a new feature package, flow file, port, or adapter is added
- **THEN** every applicable gate covers it with zero gate edits

#### Scenario: A gate's scope silently empties
- **WHEN** a rename or restructure removes everything a gate scans
- **THEN** the gate's non-vacuity twin fails rather than the gate passing forever

#### Scenario: One derived group of several empties
- **WHEN** a heading or label a gate parses is reworded so that one of its derived groups resolves
  to nothing, while the other groups still resolve
- **THEN** that group's own non-vacuity twin fails, rather than the gate passing on the strength of
  the groups that still parse

#### Scenario: A pinned expected value is mistaken for a scope list
- **WHEN** a guard holds a literal table of the values its scan must produce
- **THEN** it is a pin, not an inclusion list, and is permitted provided it states why the value is
  fixed and what would change it

### Requirement: The migration's laws are permanent gates

Every law the migration beacon measured SHALL be enforced permanently in `:test:architecture`
under `./gradlew build`. (The module-architecture migration is complete; its beacon — the
detached burn-down module and the non-required `verify` job — measured zero on every law at the
finale and was deleted, per its own contract.) The promoted gates:

- **Module-set equality**: the `settings.gradle.kts` include set SHALL equal the union of the
  groups `module-architecture` enumerates, **derived from that spec's text at test runtime** — the
  gate SHALL NOT hold its own copy of the set. Adding or deleting a module fails until the spec is
  consciously amended with the group the module joins and the argument for that group. The failure
  SHALL name all three groups and what each requires, because "must withhold a dependency" is the
  right instruction for only one of them. The gate SHALL keep a non-vacuity twin per group.
- **Mixed port/impl files**: no file under `adapter/`, `domain/`, or `ui/` SHALL declare an
  `interface` beside a Ktor or SQLDelight import — a port and its technology impl cohabiting is
  the seed of the pre-migration shape.
- **Deletion ledger**: the migration's retired dead weight SHALL stay dead — the zxing and
  kotlincrypto catalog entries, the `capability/` tree, `LedgerReader`, `LoggingPushReceiver`,
  `EventMetadataSource`, the Arrow/ArrowLevel duplicate
  enum, and any second `*Enrollment` uploader. Resurrection is not forbidden forever; it is
  forbidden **silently** — bringing an item back means deleting its guard row in the same commit,
  with the argument in the PR. The guard SHALL assemble its patterns so its own source never
  matches them (the beacon's self-match lesson).
  The `LeaveNotifier` interface, retired as single-implementation ceremony ("the class is the
  seam"), has been brought back under exactly that clause and its row deleted: a **port** is not an
  interface justified by a second implementation, it is the declared boundary where the core stops
  and an external system begins, and with the interface gone the composition carried the crossing as
  an opaque closure instead — invisible to every gate that reads types.
- **Shells** and **zones** are gated by their own standing requirements (the shell gates; the zone
  gates), now all armed and gating.

The flow-transcriber generation failure (capability `architecture-diagrams`) SHALL likewise be a
hard gate: an untranscribable flow fails `architectureDiagrams` and the freshness test under the
canonical build.

#### Scenario: A module is added without amending the target list
- **WHEN** a new `include(...)` lands in `settings.gradle.kts` with no matching edit to the
  module-set gate's target list
- **THEN** the gate fails, naming the drift and the withholding bar a new module must clear

#### Scenario: Retired dead weight grows back
- **WHEN** a retired declaration (or catalog entry, or the `capability/` tree) reappears anywhere
  in the scanned roots
- **THEN** the deletion-ledger gate fails, naming the resurrected item and its rationale

#### Scenario: A retired name comes back as a port
- **WHEN** a declaration the ledger retired is reintroduced because the judgement that retired it is
  overturned
- **THEN** its ledger row is deleted in the same commit and the reversal is argued in the change's
  decision record, so the resurrection is loud rather than silent
