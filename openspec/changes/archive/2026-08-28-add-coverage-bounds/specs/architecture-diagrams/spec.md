## ADDED Requirements

### Requirement: The module graph counts architectural dependencies only

The module dependency graph SHALL be derived from **build** configurations — those that put classes
on a compile or runtime classpath. Configurations that exist to aggregate *reports* rather than to
build anything SHALL be excluded, and the exclusion SHALL be named in the generated file's own header
so a reader is not misled about what the graph counts.

Kover's `kover` configuration is such a configuration today. A `kover(project(...))` declaration
merges another module's coverage **data**; it puts nothing on a classpath, creates no compile edge,
and cannot hand a module a dependency it could call. Counting it produced three edges in this tree,
and **every one pointed the opposite way to the real dependency**: `:domain → :adapter:generic:fake`,
`:ui:components → :ui:screens`, `:ui:presentation → :ui:screens` — the core appearing to depend on
test fakes, and the design system on the screens that consume it. In a diagram that IS the record
(`architecture-diagrams`, "Diagrams are derived, never drawn"), that is not noise; it is the record
asserting an architecture the repository does not have.

The zone graph derives its edges from `build.gradle.kts` **text** rather than from the Gradle model,
so it cannot apply this rule. A report-aggregation edge SHALL therefore not be declared in the build
script of any module the zone generator scans (`adapter/`, `domain/`, `ui/`); the root build script
is the place for it. That is independently forced for `:domain` by `architecture-guards`
(`ModuleSetTest` asserts `domain/build.gradle.kts` names no module at all, because that absence is
the precondition for the platform-free compile error).

Both renderers of the module graph SHALL state the exclusion identically. The renderer has a
byte-identical twin — one in the root build script that reads the Gradle model, one in
`:tools:diagrams` that re-renders from the committed sidecar — and the freshness test fails if they
disagree, which is the intended behaviour and not a reason to state the rule in only one of them.

#### Scenario: A report-aggregation edge is declared

- **WHEN** a module declares a dependency on another module through a configuration that aggregates
  reports rather than building anything
- **THEN** the module graph does not show an edge, because no classpath relationship exists

#### Scenario: Such an edge is declared in a scanned build script

- **WHEN** a report-aggregation edge is declared in a `build.gradle.kts` under `adapter/`, `domain/`
  or `ui/`
- **THEN** the zone graph renders it as an architectural edge, because that generator reads build
  scripts as text — so the declaration belongs in the root build script instead

#### Scenario: A reader asks what the module graph counts

- **WHEN** a reader opens `architecture/modules.md`
- **THEN** its header states that report-aggregation configurations are excluded and why, rather than
  claiming every configuration is counted
