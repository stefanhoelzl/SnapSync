## MODIFIED Requirements

### Requirement: Device-facing baked values are rendered to a bundled property list

The resolver SHALL emit a **property-list rendering** carrying every deployment value the iOS app and the
background-upload extension read at runtime — the device-facing upload base, the APNs environment, the
crash-reporting environment, the crash-reporting DSN, and the **App Store URL** the app offers when it must
tell the user to update (capability `min-app-version`). The rendering SHALL use the inventory's own key
names, so the artifact is a direct projection of the inventory as the JSON and site renderings already are.

A value read by more than one toolchain SHALL be declared **once** and projected to each rendering that
needs it, never restated per consumer. The App Store URL is the worked example of why: it was declared for
the backend alone and hardcoded independently in the marketing site, the two drifted, and the copy nobody
exercised was the wrong one — so the redirect served to someone who opened an event link without the app
pointed at a page that does not resolve. Agreement between consumers SHALL be **constructed** by shared
projection rather than asserted by review.

The generated file SHALL be copied into **both** bundles: the app and the extension each read their own
bundle, so a value present in one and absent from the other is a real and reachable state.

The build-settings rendering SHALL carry only values consumed as Xcode build settings, entitlement substitutions, or **`Info.plist` substitutions**. Those values SHALL all be sourced from literals in authored files. This is what removes the
comment-truncation hazard **structurally** rather than escaping around it: a property list escapes, while
the build-settings grammar opens a comment on `//` anywhere in a line and offers no escape. No hand-rolled comment guard SHALL remain in the build-settings renderer. A value that must reach a grammar with no escape SHALL therefore be **composed at its destination** from settings that cannot carry the offending character, rather than escaped at the emission site: a per-site escape covers what someone remembered.

A resolved value MAY have a reader **outside this repository** — an Apple daemon reading a key out of a bundle's own `Info.plist`. Such a value SHALL be carried in the file that reader opens. No rendering the resolver owns can substitute for it, because the external reader has never been told to look there, and the failure is silent in both directions: the artifact is well-formed, our own readers are satisfied, and only the external one is starved. Where a value has both an internal and an external reader it SHALL be carried in both places, and the archive verification SHALL assert the two agree.

Where the externally-read carrier restates a **version-bearing** portion of a value that also lives in a
rendering the resolver owns, the two SHALL move together, and the agreement assertion above is what
enforces it. A device-facing base carrying an API version prefix is exactly that case: the value our code
composes requests from and the value the external daemon validates against are the same URL, so changing
one without the other yields a build whose registration succeeds and whose uploads may be refused — with no
error either carrier can report.

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

#### Scenario: A value with an external OS reader is carried where that reader looks

- **WHEN** a resolved value is read by a platform daemon out of a bundle's own `Info.plist`
- **THEN** it is carried in that `Info.plist`, not only in a rendering the resolver owns, because the
  daemon opens no file this repository chose for it

#### Scenario: The two carriers of one value are asserted to agree

- **WHEN** a built bundle is verified after archiving
- **THEN** the value read from its `Info.plist` is non-empty and exactly equal to the value read from its
  generated property list, so a deletion, an unresolved substitution, a truncated build setting, or a
  rendering that reached only one bundle fails the build

#### Scenario: Moving the API version moves both carriers

- **WHEN** the device-facing base changes the API version it names
- **THEN** the generated rendering and the externally-read `Info.plist` carrier both change, and the
  agreement assertion fails the build if only one did

#### Scenario: One declaration reaches every consumer

- **WHEN** a value is read by the backend, the marketing site and the device
- **THEN** it is declared once and projected to each rendering, and no consumer carries its own copy

#### Scenario: The device can name where to get the update

- **WHEN** the app must tell the user their build is too old
- **THEN** the App Store URL is available from the bundled rendering, so the refusal offers a destination
  rather than only a version number
