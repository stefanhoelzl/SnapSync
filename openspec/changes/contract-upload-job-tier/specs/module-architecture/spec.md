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
  site into the iOS app shell, linked under `-Psnapsync.rig`), `:test:contracts` (the port contracts,
  linked under `-Psnapsync.rig` into the app, into the upload extension, and into the rig-gated source sets of
  the two iOS adapter modules, the extension-safe one and the app-only one; it withholds the test-assertion library from every other main source set — it is the
  only module whose main code may assert). A contained module is grouped by the law that governs it,
  **not** by its name prefix: these three are the same species and the containment law describes
  exactly their shapes.
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

#### Scenario: A rig-gated source set in the app-only adapter module
- **WHEN** the app-only adapter module carries a source directory compiled only under `-Psnapsync.rig`, holding
  in-app contract bindings
- **THEN** the contracts module is linked there only under the same property, and a build without it
  contains neither

#### Scenario: The contracts module in the upload extension

- **WHEN** the upload extension is built with `-Psnapsync.rig`
- **THEN** the contracts module is linked into it together with the rig-gated source it runs through, and a
  build without the property contains neither

### Requirement: A build-time-only module is contained by compilation, not by a runtime check
A test-only module that links into a shipped-format binary SHALL be contained at compile time: it is linked only under an explicit build property, and a build without that property SHALL contain **no source of that module at all** — not a stub, not a no-op implementation, and not an inert runtime branch. Such a module SHALL still earn its modulehood the ordinary way, by withholding a third-party or platform dependency from every other module by compile error.

Where such a module needs a call site inside a shell, it SHALL contribute that source itself — a source
directory the shell's build script adds only under the property — rather than requiring the shell to carry
a permanently-compiled seam. The shell's own production source SHALL gain no declaration naming the
module, and any visibility widening it requires SHALL be the narrowest that compiles (`internal` before
`public`, so no platform framework header changes).

Where the thing to be contained is reached **through** a shell's own switch rather than by contributing a
call site — so that removing it would leave the shell naming a type that no longer exists — containment
SHALL be achieved by giving it its **own binary target** over its own module, rather than by keeping an
inert branch. A separate target linking neither the shell module nor the live graph makes inertness a
property the binary cannot express, rather than one that a set of no-op members must each preserve
correctly.

Where the contained module's consumer needs a withholding module's `internal` declarations — an
adapter's operating-system seam, which the port-contract device binding records through — that
withholding module's build script MAY add a source directory to its own source set **only under the
same property**. The same all-or-nothing guarantee applies: without the property the directory is not on
the compile path, and with it the directory and the contained module arrive together. The directory
SHALL depend on nothing beyond the contained module, and no declaration SHALL be widened for it — reaching
`internal` from inside the owning module is the reason it lives there.

Where the contained module must **replace** a call path in a shell rather than add a call site — the rig
build's upload extension routing `process()` to a contract run — the shell's build script SHALL select
between two source directories by the property: a production directory holding the one declaration the
path goes through, and a directory the contained module contributes, declaring the same symbol. Exactly one
is on the compile path. The production directory is shell source like the rest; the contributed one SHALL
hold no decision either, delegating any branch to the contained module or to a withholding module's
property-gated directory, so the shell gate's zero-decision rule holds over both.

This is the inverse of `:adapter:generic:fake`, which never links into a shipped framework at all.

A dev/test control surface SHALL NOT rely on **runtime** inertness in a shipped binary. A launch-environment
variable is inert only because a production launch supplies no environment — a property of how the app is
started, not of what it contains — so it is not a containment mechanism. Where such a surface is wanted, it
belongs behind compile-time containment; a build-property-gated tree MAY read an environment variable,
because the file reading it is absent from a production build.

#### Scenario: A production build contains none of the module
- **WHEN** the app is built without the containment property (any CI, TestFlight, or App Store build)
- **THEN** neither the module nor any source it contributes is on the compile path, and the shipped binary
  contains no declaration of it

#### Scenario: The property links the module and its contributed call site together
- **WHEN** the app is built with the containment property set
- **THEN** the module is on the compile path **and** the source directory it contributes is added to the
  shell's source set, so the call site and the module it names arrive together and cannot be half-present

#### Scenario: A runtime-flag containment is rejected
- **WHEN** containment is proposed as a runtime check — a flag, an environment variable, or a no-op
  implementation compiled into every build
- **THEN** it SHALL be rejected for a module of this kind, because a shipped binary would then contain the
  code whose absence is the guarantee

#### Scenario: A surface reached through the shell's own switch gets its own target
- **WHEN** a dev/test composition is selected by a branch in the shell's own mode switch, so that gating its
  source alone would leave the shell naming a missing type
- **THEN** it is given its own binary target over its own module, linking neither the shell module nor the
  live graph, rather than remaining an inert branch in the shipped one

#### Scenario: A withholding module carries a property-gated source directory
- **WHEN** a withholding module's build script adds a source directory only under a containment property
- **THEN** a build without the property compiles none of it, the directory depends on nothing beyond the
  contained module, and no declaration in the withholding module is widened for it

#### Scenario: The module still withholds a dependency
- **WHEN** the module is added to the module set
- **THEN** its withholding argument is recorded here, and the dependency it withholds is unreachable from
  every other module by compile error

#### Scenario: A shell's call path is substituted under the property

- **WHEN** the upload extension is built with `-Psnapsync.rig`
- **THEN** its build compiles the rig-contributed directory in place of the production one, so the entry the
  operating system calls reaches the contract runner, and a build without the property compiles only the
  production directory, whose entry reaches the core exactly as before

#### Scenario: The substituted directory holds a decision

- **WHEN** the contributed directory's declaration branches on the run request itself
- **THEN** the shell gate fails over it, and the branch moves into the contained module or the withholding
  module's property-gated directory

