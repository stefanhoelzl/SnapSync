## MODIFIED Requirements

### Requirement: Build-property-gated source sets are compiled and run by CI, tests included

CI SHALL compile every build-property-gated source set on every push, and that obligation SHALL cover a
gated **test** source set as well as a gated main one.

Source compiled only under a build property (`-Psnapsync.forge=true`, `-Psnapsync.rig=true`) is source the
canonical check never sees, because the canonical check runs without those properties — that is what
compile-time containment means, and it is also a blind spot.

Compiling the main half only does not discharge this. It is the failure that has actually occurred:
the presentation module's forge test source set (then at `ui/presentation`, now `domain/presentation`),
gated with the presets it asserts, stopped compiling when the presentation state changed shape and stayed broken for weeks with every gate green — while the step that
exists to catch exactly this rot ran beside it, compiling main metadata.

Where a gated test is cheap to execute on the runner already in use, CI SHALL **run** it rather than only
compile it. A gate that compiles an assertion without evaluating it reports on the assertion's syntax, not
on the property the assertion exists to hold — and for the forge presets that property is what the App
Store listing's honesty rests on (capability `ios-appstore-metadata`).

This requirement fixes the obligation, not its placement: which workflow and which job carry it is a CI
concern, and satisfying it by extending an existing step is preferred to adding a job for a source set
nothing else consumes.

#### Scenario: A gated test source set stops compiling

- **WHEN** production code changes shape such that a source set compiled only under a build property no
  longer compiles
- **THEN** a CI check on that push fails, naming the compilation error, rather than every gate reporting
  green

#### Scenario: A gated assertion is evaluated, not merely compiled

- **WHEN** a gated test source set is cheap to execute on a runner CI already uses
- **THEN** CI executes it, so a preset that compiles but no longer reduces to the frame it claims fails the
  push

#### Scenario: The canonical check is unchanged

- **WHEN** `./gradlew build` runs without any build property
- **THEN** it compiles none of the gated trees, which remain contained at compile time exactly as before
