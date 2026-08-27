## MODIFIED Requirements

### Requirement: The module set withholds; packages organize
The system SHALL consist of exactly the modules enumerated below, and **every** module the build
declares SHALL appear in exactly one group. A group names the law that justifies its members'
existence; a module justified by no law is a package with a derived text gate instead.

- **Withholding modules** — each exists because it withholds a third-party or platform dependency
  from its consumers by compile error: `:domain` (one module; zero `project()` dependencies; no
  `iosMain` source directory), `:ui:presentation`, `:ui:screens`, `:ui:components` (the only module
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

The adapter tree SHALL be uniformly two-level — `adapter:<platform-axis>:<linkage-leaf>` — with each
platform-axis prefix (`adapter/ios/`, `adapter/generic/`) a pure path grouping that is not itself a
module (no build file: a prefix module would withhold nothing). All finer structure SHALL be packages
whose boundaries are enforced by derived text gates, not modules. The named test-equipment zone
(harness panels, world inspector) is likewise exempt from production-module laws.

The enumeration SHALL be exhaustive and SHALL NOT use wildcards: it is the expected value the
module-set gate compares the build's include set against (capability `architecture-guards`), and a
wildcard cannot be compared. Within a group, a backticked `:`-prefixed token **is** a membership
claim; prose in a group SHALL refer to another module by description rather than by its backticked
path, or it silently enrols that module in a second group. Adding a module therefore requires amending this requirement with the
group it joins and the argument for that group.

#### Scenario: A structural boundary that withholds nothing is rejected
- **WHEN** a new module is proposed whose dependency block withholds no third-party or platform
  dependency from any consumer, and which is neither compile-time contained nor never-shipped
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
- **WHEN** any file under `:domain` references a platform API or a non-allowlisted library
- **THEN** compilation fails (unresolvable symbol), because `:domain` declares no project
  dependencies and only the per-zone allowlisted libraries
