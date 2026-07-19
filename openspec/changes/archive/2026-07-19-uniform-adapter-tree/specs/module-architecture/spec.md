# module-architecture — delta for uniform-adapter-tree

## MODIFIED Requirements

### Requirement: The module set withholds; packages organize
The system SHALL consist of exactly these production modules, each existing because it withholds a
third-party or platform dependency by compile error: `:domain` (one module; zero `project()`
dependencies; no `iosMain` source directory), `:ui:presentation`, `:ui:screens`,
`:ui:components` (the only module that may depend on Material 3), `:adapter:ios:ext-safe`,
`:adapter:ios:app-only`, `:adapter:generic:app`, `:adapter:generic:fake`, `:app:ios`,
`:app:ios:extension`, and `:app:desktop`. The adapter tree SHALL be uniformly two-level —
`adapter:<platform-axis>:<linkage-leaf>` — with each platform-axis prefix (`adapter/ios/`,
`adapter/generic/`) a pure path grouping that is not itself a module (no build file: a prefix
module would withhold nothing). All finer structure SHALL be packages whose boundaries are
enforced by derived text gates, not modules. Test-only modules (`:test:*`) and the named
test-equipment zone (harness panels, world inspector) are exempt from production-module laws.

#### Scenario: A structural boundary that withholds nothing is rejected
- **WHEN** a new module is proposed whose dependency block withholds no third-party or platform
  dependency from any consumer
- **THEN** the structure SHALL be a package with a gate instead, and the module-set gate fails
  until the module list is consciously amended

#### Scenario: The core cannot reach a platform
- **WHEN** any file under `:domain` references a platform API or a non-allowlisted library
- **THEN** compilation fails (unresolvable symbol), because `:domain` declares no project
  dependencies and only the per-zone allowlisted libraries

### Requirement: Ports are the I/O boundary named for the need
The system SHALL access anything touching an external system (time, timezone, files, network,
environment, and platform facilities included) only through a port interface declared in `ports/`,
named for the need it serves (the name must remain correct if a second platform ships), never
for the technology satisfying it. Adapter modules SHALL hold implementations only, named for the
technology, placed by linkage. The linkage leaf's vocabulary is per platform axis, deliberately:
on the ios axis the leaves encode PROCESS linkage (`ext-safe` may link into the extension
process, `app-only` must not), on the generic axis they encode SHIPPABILITY (`app` links into the
shipped app **and** extension binaries; `fake` never ships) — each axis names the question that
discriminates its own leaves. Adapters MAY branch on technology vocabulary. Pure logic SHALL NOT
be a port. Backend access SHALL be split into need-named ports (one adapter may implement many).

#### Scenario: Naming survives a second platform
- **WHEN** a port is proposed whose name describes an Apple technology rather than the
  application's need
- **THEN** the port is renamed for the need before it is added to `ports/`

#### Scenario: Core purity is closed by default
- **WHEN** a new technology library is used anywhere in `:domain` or `:ui:presentation`
- **THEN** the per-zone allowlist gate fails until the library is consciously allowlisted, with
  no per-technology gate edits required
