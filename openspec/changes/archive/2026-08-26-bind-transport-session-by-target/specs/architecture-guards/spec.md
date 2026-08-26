## ADDED Requirements

### Requirement: The transport-binding gate

`:test:architecture` SHALL pin, by source text, which `URLSession` configuration each iOS target's
transport-session seam yields (`ios-url-session-upload`, "The transport binding is fixed by the compilation
target"). The gate SHALL assert, **exactly in both directions**:

- the `iosArm64` actual — the one every shipped binary links — names
  `backgroundSessionConfigurationWithIdentifier`; and
- the `iosSimulatorArm64` actual does **not** name it.

A source-text gate is the mechanism here because no executable test can reach the artefact that matters.
This repo's iOS tests run on `iosSimulatorArm64` and nothing else, so the **device** actual is never
executed by anything in CI; a swap of the two actuals, or a "simplification" that gives both targets the
default configuration, would ship a foreground session to real users and pass every existing gate,
including `codesign`, the build, and the whole `iosSimulatorArm64Test` suite. This is the same reasoning
that makes "Keychain access is confined to one module" a text gate — it catches what no linter can see on
`iosMain`.

The gate SHALL fail on a missing actual as well as on a wrong one, so deleting a target's actual is not a
way past it. Adding a third iOS target SHALL require extending this pin rather than silently escaping it,
by the rule in "Gates fail closed on novelty".

The gate SHALL NOT assert anything about the *runtime behaviour* of either session — that a background
session transfers on a device, or that a default session does not survive suspension. Those are platform
facts with their own forcing proofs and expiry triggers in `ios-url-session-upload`, and a text gate
cannot evidence them. The stated residual gap: this pin shows the device actual **names** the background
factory, never that the resulting session behaves; only a device run shows that.

#### Scenario: Swapping the two actuals fails the build

- **WHEN** the `iosArm64` actual is changed to yield a default session configuration
- **THEN** `./gradlew build` fails on the transport-binding gate, naming the file and the expected literal

#### Scenario: A simulator actual that reaches for the background factory fails the build

- **WHEN** the `iosSimulatorArm64` actual is changed to name `backgroundSessionConfigurationWithIdentifier`
- **THEN** the gate fails, because the pin is exact in both directions

#### Scenario: A deleted actual fails the build

- **WHEN** either target's actual is removed
- **THEN** the gate fails rather than passing vacuously

### Requirement: The simulator transport binding is asserted where it can be executed

`:adapter:ios:app-only` SHALL carry an `iosSimulatorArm64` test asserting that the seam yields a
configuration with a **nil** session identifier on that target, and that the reported binding names the
default one. This is the executable half of the pin above, and it covers what the text gate cannot: that
the actual selected by the build for this target really produces a non-background configuration, rather
than merely being spelled that way.

The two halves SHALL NOT be collapsed into one. The text gate covers the target no test can run; this test
covers the behaviour text cannot show. Neither subsumes the other.

#### Scenario: The simulator seam yields a default configuration

- **WHEN** the seam is called on `iosSimulatorArm64`
- **THEN** the returned configuration's identifier is nil, and the reported binding names the default one
