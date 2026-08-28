## MODIFIED Requirements

### Requirement: The module set withholds; packages organize
The system SHALL consist of exactly the modules enumerated below, and **every** module the build
declares SHALL appear in exactly one group. A group names the law that justifies its members'
existence; a module justified by no law is a package with a derived text gate instead.

- **Withholding modules** — each exists because it withholds a dependency from its consumers by compile
  error. The withheld dependency is usually a third-party or platform one; it MAY also be **another zone
  of the core**, where a module boundary is the only construction that makes the zone edge unresolvable
  rather than merely forbidden. Members: `:domain:model`, `:domain:ports`, `:domain:feature`,
  `:domain:flow`, `:domain:compose` (the core's zones, each depending only along the permitted zone edge
  and only via `implementation()`, so no zone leaks transitively; no `iosMain` source directory anywhere
  in the tree), `:ui:presentation`, `:ui:screens`, `:ui:components` (the only module
  that may depend on Material 3), `:adapter:ios:ext-safe`, `:adapter:ios:app-only`,
  `:adapter:generic:app`, `:adapter:generic:fake`, `:app:ios`, `:app:ios:extension`, `:app:desktop`.
- **Contained modules** — each exists so that something is absent from a production build, governed
  by "A build-time-only module is contained by compilation, not by a runtime check": `:app:ios:forge`
  (its own binary target, linked under `-Psnapsync.forge`), `:test:rig` (contributes its own call
  site into the iOS app shell, linked under `-Psnapsync.rig`). A contained module is grouped by the law that
  governs it, **not** by its name prefix: these two are the same species and the containment law
  describes exactly their two shapes.
- **Support modules** — never linked into any shipped-format binary, and exempt from the
  production-module laws: `:test:world`, `:test:integration`, `:test:architecture`,
  `:test:harness-driver`, `:tools:diagrams`.

The core's zone split is the one place the withholding law is satisfied by an **internal** boundary, and it
is admitted for a stated reason: the zone edges were previously held by text gates that had to enumerate
the forms a violation could take, could not see generated source, and passed green when their scope
directory was renamed. A module boundary enumerates nothing and cannot be renamed into passivity. The cost
is bounded and was measured before the split: eighteen `internal` declarations across the whole core, none
in `ports/`, `flow/` or `compose/`.

The adapter tree SHALL be uniformly two-level — `adapter:<platform-axis>:<linkage-leaf>` — with each
platform-axis prefix (`adapter/ios/`, `adapter/generic/`) a pure path grouping that is not itself a
module (no build file: a prefix module would withhold nothing). The core's `domain/` prefix is likewise a
path grouping and not itself a module. All finer structure SHALL be packages
whose boundaries are enforced by derived text gates, not modules. The named test-equipment zone
(harness panels, world inspector) is likewise exempt from production-module laws.

The enumeration SHALL be exhaustive and SHALL NOT use wildcards: it is the expected value the
module-set gate compares the build's include set against (capability `architecture-guards`), and a
wildcard cannot be compared. Within a group, a backticked `:`-prefixed token **is** a membership
claim; prose in a group SHALL refer to another module by description rather than by its backticked
path, or it silently enrols that module in a second group. Adding a module therefore requires amending this requirement with the
group it joins and the argument for that group.

#### Scenario: A structural boundary that withholds nothing is rejected
- **WHEN** a new module is proposed whose dependency block withholds no third-party dependency, no
  platform dependency, and no zone of the core from any consumer, and which is neither compile-time
  contained nor never-shipped
- **THEN** the structure SHALL be a package with a gate instead, and the module-set gate fails
  until the module list is consciously amended

#### Scenario: A module exists to be contained rather than to withhold
- **WHEN** a module exists so that a surface is absent from a production build, rather than to
  withhold a dependency from other modules
- **THEN** it belongs to the contained group and is justified by the containment law, and it SHALL
  NOT be recorded as withholding a dependency it does not withhold

#### Scenario: A module reaches the build without reaching the spec
- **WHEN** an `include(...)` lands in `settings.gradle.kts` naming a module no group enumerates
- **THEN** the module-set gate fails, naming the module and the three groups it could join, and it
  cannot be satisfied by editing the gate

#### Scenario: The core cannot reach a platform
- **WHEN** any file in a core zone module references a platform API or a non-allowlisted library
- **THEN** compilation fails (unresolvable symbol), because a zone module declares only its permitted
  zone dependency and the per-zone allowlisted libraries

#### Scenario: A zone edge is crossed
- **WHEN** a file in one core zone module references a declaration from a zone its module does not depend
  on
- **THEN** the reference does not resolve and the build fails at compilation

#### Scenario: A zone dependency is exposed transitively
- **WHEN** a core zone module declares another zone with `api()` rather than `implementation()`
- **THEN** the boundary leaks to every downstream consumer, and the declaration is a defect the split
  exists to prevent
