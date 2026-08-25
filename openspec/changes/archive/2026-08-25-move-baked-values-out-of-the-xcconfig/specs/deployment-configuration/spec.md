## ADDED Requirements

### Requirement: Device-facing baked values are rendered to a bundled property list

The resolver SHALL emit a **property-list rendering** carrying every deployment value the iOS app and the
background-upload extension read at runtime — the device-facing upload base, the APNs environment, the
crash-reporting environment, and the crash-reporting DSN. The rendering SHALL use the inventory's own key
names, so the artifact is a direct projection of the inventory as the JSON and site renderings already are.

The generated file SHALL be copied into **both** bundles: the app and the extension each read their own
bundle, so a value present in one and absent from the other is a real and reachable state.

The build-settings rendering SHALL carry only values consumed as Xcode build settings or entitlement
substitutions. Those values SHALL all be sourced from literals in authored files. This is what removes the
comment-truncation hazard **structurally** rather than escaping around it: a property list escapes, while
the build-settings grammar opens a comment on `//` anywhere in a line and offers no escape. No hand-rolled
comment guard SHALL remain in the build-settings renderer.

#### Scenario: Both bundles carry the rendering

- **WHEN** a build is produced
- **THEN** the app bundle and the extension bundle each contain the generated property list, with the same
  resolved values

#### Scenario: A value containing a comment delimiter survives

- **WHEN** a resolved value contains `//` — a URL, or a crash-reporting DSN
- **THEN** the value read back from the built bundle equals the resolved value exactly, because it was
  rendered into a grammar that escapes rather than one that comments

#### Scenario: The build-settings rendering carries no environment-sourced value

- **WHEN** the build-settings rendering is inspected
- **THEN** every value in it traces to a literal in an authored file, and none to an environment reference

## MODIFIED Requirements

### Requirement: The rendering set bounds where a value can reach

Each key's inventory entry SHALL name the renderings it appears in, and the resolver SHALL emit a key only
into those renderings. **This is the whole containment guarantee** — there SHALL be no separate
secret/non-secret classification governing where values may appear.

The rendering set bounds where a value **appears**. It does NOT assert that the value **survives** the
trip: a rendering's own grammar may reinterpret a character the resolver wrote. A rendering whose values
are interpolated raw (no escaping layer) SHALL therefore carry only values sourced from **literals in
authored files**, never from the environment. A value read from the environment cannot be reviewed before
it is written — nobody sees its contents in the context of the file it lands in — so it SHALL be rendered
only into a grammar that escapes. This is a rule about reviewability, not about secrecy, and it is what
separates the renderings rather than a classification of the values.

A **runtime**-scope key SHALL name no baked rendering. A baked rendering has no run time in which to
resolve a reference, so a runtime-scope key appearing in one could not be honoured.

#### Scenario: A value reaches only its declared renderings

- **WHEN** a key's inventory entry names a set of renderings
- **THEN** the key appears in exactly those renderings and in no other

#### Scenario: A runtime key in a baked rendering is rejected

- **WHEN** a runtime-scope key names a rendering that is baked at build time
- **THEN** resolution fails, naming the key and the rendering

#### Scenario: An environment-sourced value is not rendered into a raw-interpolated grammar

- **WHEN** a key whose value is an environment reference names a rendering that interpolates values raw
- **THEN** the declaration is rejected in review, because a value nobody can read beforehand may not enter
  a grammar with no escaping
