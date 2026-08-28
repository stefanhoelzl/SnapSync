# deployment configuration Specification

## Purpose

How a **deployment** is declared, composed, resolved and rendered — the one upstream source for every
value that identifies *which* backend, *which* Apple identity and *which* build channel an artifact is
built for. Four toolchains hold these facts (Deno, Gradle, Xcode, Astro) plus the App Store listing, and
none can import another's source.

**Why this exists.** Before it, the device-facing domain lived in **nine** production homes; two were
guarded, one was generated, and **six were pinned by nothing** — including `BACKGROUND_UPLOAD_URL_BASE`,
the device-facing upload host itself, whose own comment called it *"the SINGLE SOURCE of the deployed
device-facing host"* while another file twenty lines away claimed the same title. The Apple team id and
bundle id were written twice in two languages with nothing checking they agreed, while composing the App
Attest `rpIdHash` and the AASA `appIDs` — where drift fails every attestation and stops every universal
link matching, **silently**. The lesson is not that guards are bad but that **guards are opt-in**: they
cover what someone remembered. Generation is total.

**What it preserves.** `backend-deployment` establishes that CI ships code but cannot ship platform
config, because bunny issues no scoped API key — so config must travel in the same artifact as the code
that reads it, or it drifts. Resolving a declared deployment at build time keeps that property exactly
while removing the cost the previous answer paid for it: values pinned into one toolchain's source, so
that reaching a different account meant editing code.

Decision record: `changes/archive/2026-08-25-add-deployment-resolver-and-boot-probe`.
## Requirements
### Requirement: A deployment is a composition of declared components

A **deployment** SHALL be a JSON file declaring an `extends` list of component files plus its own keys.
The composition rule SHALL be a **shallow merge of top-level keys, in list order, with the deployment's
own keys last**. Components SHALL NOT themselves declare `extends`; nesting, deep merge, interpolation and
conditionals SHALL NOT be supported.

The rule is deliberately too weak to grow a templating language: anything it cannot express is a signal to
restructure the data. Deep merge is specifically excluded because it is the point at which a resolved value
can no longer be predicted by reading one file, which for configuration deciding which bucket holds users'
photos is the wrong trade.

Deploying to a different account SHALL therefore require **adding a deployment file and selecting it**, not
editing code.

#### Scenario: A deployment resolves to the merge of its components

- **WHEN** a deployment declares `extends: [A, B]` and its own key `k`
- **THEN** the resolved configuration is A's keys, overridden by B's, overridden by the deployment's own

#### Scenario: A component may not extend

- **WHEN** a component file declares `extends`
- **THEN** resolution fails, naming the file

#### Scenario: A new deployment needs no code change

- **WHEN** a deployment file is added and selected
- **THEN** every consumer targets it with no change to any source file

### Requirement: One resolver, one invocation, every rendering

A single resolver SHALL resolve a named deployment and emit **all** renderings in one invocation, at fixed
paths. There SHALL NOT be a per-rendering invocation mode.

This is what makes it impossible for two artifacts to be rendered from **different** deployments — an
xcconfig built from one and a bundle from another would disagree, which is the exact failure class this
capability exists to remove. It also gives "has the resolver run?" a single answer for the whole repository.

The resolver SHALL be implemented in a runtime available to **every** consumer's toolchain without an
install step, because no CI job carries both the backend's runtime and the Gradle toolchain.

#### Scenario: One invocation produces every rendering

- **WHEN** the resolver is invoked for a deployment
- **THEN** every rendering is written, all derived from that one resolution

#### Scenario: Renderings cannot disagree

- **WHEN** any two renderings are compared
- **THEN** they reflect the same resolved deployment, because no invocation can produce only one of them

#### Scenario: The resolver runs on every consumer's toolchain

- **WHEN** the resolver is invoked from the backend's CI job, the Gradle CI job, or the macOS build job
- **THEN** it runs with no toolchain installation step in any of them

### Requirement: The key inventory is the contract of record

The resolver SHALL hold an inventory declaring, for every configuration key: the **renderings** it appears
in, its **scope**, its **required-if** condition, its **default** where absence has a defined meaning, and
its **rationale**.

The inventory SHALL be the documented contract for these values. Rationale that today lives as comments in
backend source SHALL move here, because the values are consumed by several toolchains and a comment in one
of them is invisible to the others.

#### Scenario: The inventory documents every key

- **WHEN** a configuration key exists
- **THEN** the inventory declares its renderings, scope, required-if condition, default where applicable,
  and rationale

#### Scenario: An undeclared key is rejected

- **WHEN** a deployment or component declares a key the inventory does not name
- **THEN** resolution fails, naming the key and the file

### Requirement: A value is a literal or an environment reference, and baking is explicit

A configuration value SHALL be either a **literal** or an **environment reference** of the form
`{ "env": "<NAME>", "scope"?: "build" | "runtime" }`. `scope` SHALL default to **`runtime`**.

The default is deliberately asymmetric: baking a value into a build artifact SHALL always be the explicit
act, because a value that is not baked cannot leak into an artifact.

A **build**-scope reference SHALL be resolved by the resolver, which reads the environment at resolution
time and emits the value. A **runtime**-scope reference SHALL be copied into the rendering **verbatim** —
the rendering carries the variable's **name**, never its value — and resolved by the consuming program when
it runs.

The resolver SHALL NOT read the environment for a runtime-scope key. Doing so would place the value in the
deployed artifact and would require CI to hold runtime secrets it is forbidden to hold.

#### Scenario: A runtime reference reaches the artifact as a name

- **WHEN** a key declares `{ "env": "X" }` with no scope
- **THEN** the rendering contains the name `X` and not its value, and the consuming program resolves it at
  run time

#### Scenario: A build reference is resolved and baked

- **WHEN** a key declares `{ "env": "X", "scope": "build" }`
- **THEN** the resolver reads `X` from its own environment and emits the value into the rendering

#### Scenario: An absent build variable takes its declared default

- **WHEN** a build-scope key's environment variable is absent and the inventory declares a default
- **THEN** the default is emitted, and the inventory states what that default means

#### Scenario: Omitting scope never bakes

- **WHEN** a value declares an environment reference without `scope`
- **THEN** it is treated as runtime-scope and its value is never placed in any artifact

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

### Requirement: Storage is a sealed kind, not an overridden shape

The storage configuration SHALL be a **sealed, discriminated union** over a closed set of kinds, resolved
by a pure, tested resolver. A deployment SHALL declare exactly one kind, and which secrets are required
SHALL follow from that kind.

A deployment whose storage kind cannot run in the deployed runtime SHALL therefore be **structurally unable
to boot there**, rather than booting and behaving wrongly.

#### Scenario: An unknown storage kind fails resolution

- **WHEN** a deployment declares a storage kind outside the sealed set
- **THEN** resolution fails, naming the kind and the permitted set

#### Scenario: Required secrets follow the kind

- **WHEN** a deployment declares a storage kind that authenticates against no external system
- **THEN** no storage credential is required of it, and resolution succeeds with none declared

#### Scenario: A local-kind deployment cannot serve as the deployed runtime

- **WHEN** a deployment whose storage kind is unavailable in the deployed runtime is deployed there
- **THEN** it fails to start, rather than starting and operating against the wrong target

### Requirement: Resolution is validated and fails closed

Resolution SHALL fail, naming the file and the key, when: a declared key is not in the inventory; a
required key is absent after merging; a referenced component cannot be found; a component declares
`extends`; a storage kind is outside the sealed set; or a runtime-scope key names a baked rendering.

A resolution that fails SHALL write **no** rendering, so a partially-updated set of artifacts cannot exist.

#### Scenario: A misspelled key is an error, not a silent absence

- **WHEN** a deployment declares a key whose name differs from an inventory key by a typo
- **THEN** resolution fails naming that key, rather than resolving to a configuration missing the intended
  one

#### Scenario: A failed resolution writes nothing

- **WHEN** resolution fails for any reason
- **THEN** no rendering is written and any previously written renderings are left untouched

### Requirement: Renderings are generated, never committed

Every rendering SHALL be generated and SHALL NOT be committed. A consumer that reads a rendering without
the resolver having run SHALL fail loudly — a missing input, not a stale or placeholder value.

Committing a rendering would create an artifact that can silently disagree with the authored files it
derives from; generating it means "the resolver has not run" is indistinguishable from nothing at all, and
therefore cannot be mistaken for a correct value.

#### Scenario: A consumer without a rendering fails loudly

- **WHEN** a build or check runs without the resolver having produced its rendering
- **THEN** it fails naming the missing input, rather than proceeding with a default

#### Scenario: No rendering is committed

- **WHEN** the repository is inspected
- **THEN** no rendering is present in version control

### Requirement: Renderers may derive; composition may not

A **renderer** MAY derive an output value from resolved values, deterministically. **Composition** SHALL
NOT: merging performs no computation.

Where several outputs must agree, they SHALL be derived from **one** resolved value rather than stated
separately and checked, so that they cannot disagree.

#### Scenario: Outputs that must agree are derived from one value

- **WHEN** two or more build settings are required to agree with one another
- **THEN** they are derived by a renderer from a single resolved value, and no combination exists in which
  they disagree

#### Scenario: Merging computes nothing

- **WHEN** a deployment is resolved
- **THEN** every resolved value is a value present in an authored file or an environment reference, with no
  value computed during merging

### Requirement: The deployment is selected explicitly at every call site

Every invocation of the resolver SHALL name the deployment it resolves. There SHALL be no implicit default
deployment, and omitting the name SHALL be an error.

Naming an unknown deployment SHALL fail closed. Reading any single call site SHALL therefore tell a reader
which deployment that path targets, without consulting shared state.

#### Scenario: Omitting the deployment is an error

- **WHEN** the resolver is invoked with no deployment named
- **THEN** it fails rather than resolving some default

#### Scenario: An unknown deployment fails closed

- **WHEN** a call site names a deployment that does not exist
- **THEN** resolution fails naming it, and no rendering is written

### Requirement: Device-facing baked values are rendered to a bundled property list

The resolver SHALL emit a **property-list rendering** carrying every deployment value the iOS app and the
background-upload extension read at runtime — the device-facing upload base, the APNs environment, the
crash-reporting environment, and the crash-reporting DSN. The rendering SHALL use the inventory's own key
names, so the artifact is a direct projection of the inventory as the JSON and site renderings already are.

The generated file SHALL be copied into **both** bundles: the app and the extension each read their own
bundle, so a value present in one and absent from the other is a real and reachable state.

The build-settings rendering SHALL carry only values consumed as Xcode build settings, entitlement substitutions, or **`Info.plist` substitutions**. Those values SHALL all be sourced from literals in authored files. This is what removes the
comment-truncation hazard **structurally** rather than escaping around it: a property list escapes, while
the build-settings grammar opens a comment on `//` anywhere in a line and offers no escape. No hand-rolled comment guard SHALL remain in the build-settings renderer. A value that must reach a grammar with no escape SHALL therefore be **composed at its destination** from settings that cannot carry the offending character, rather than escaped at the emission site: a per-site escape covers what someone remembered.

A resolved value MAY have a reader **outside this repository** — an Apple daemon reading a key out of a bundle's own `Info.plist`. Such a value SHALL be carried in the file that reader opens. No rendering the resolver owns can substitute for it, because the external reader has never been told to look there, and the failure is silent in both directions: the artifact is well-formed, our own readers are satisfied, and only the external one is starved. Where a value has both an internal and an external reader it SHALL be carried in both places, and the archive verification SHALL assert the two agree.

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

### Requirement: A key's readers follow it to the rendering that owns it

Every reader of a resolved value SHALL read it from the rendering the key inventory assigns to that key.
No reader SHALL read a fragment-owned key from an authored file the key has left, and a reader that
resolves an empty value for a key it requires SHALL fail closed — refusing to produce its output and
naming the key and the file — rather than substituting the empty value.

This is distinct from the requirement that a consumer without a rendering fails loudly. That one governs
a rendering which is **absent**. This one governs a reader still pointed at a file which is **present**
and simply no longer carries the key: the read succeeds, yields nothing, and nothing distinguishes
"this file does not assign that key" from "that key's value is empty". Where the value composes an
identity — a signing prefix, a bundle id, a domain — an empty substitution produces a well-formed
artifact making a false claim, which no validity check downstream can detect.

#### Scenario: A reader left behind on a moved key fails closed

- **WHEN** a reader extracts a fragment-owned key from an authored file that no longer assigns it
- **THEN** it fails naming the key and the file, rather than proceeding with an empty value

#### Scenario: An empty required value is never substituted

- **WHEN** a reader resolves an empty value for a key it requires
- **THEN** it produces no output, rather than emitting an artifact with the empty value interpolated

#### Scenario: A reader of the owning rendering succeeds

- **WHEN** a reader reads a key from the rendering the inventory assigns it, and the resolver has run
- **THEN** it resolves the key's value and proceeds

### Requirement: The maintenance flag is a build-scope key, absent by default

The inventory SHALL declare a **maintenance** key: build scope, rendered into the backend bundle's
rendering alone, defaulting to **off**. It is the same shape the commit stamp and the build channel already
have — a value that varies per build rather than per deployment.

It SHALL NOT be a runtime environment reference. CI holds only the script-scoped deploy key and cannot
write the script's environment (see `backend-deployment`), so a value CI must control has to ship inside
the artifact. This is the same argument that makes every other non-secret deployment-resolved rather than
environment-owned; the maintenance flag is simply the first one CI sets **per publish** rather than per
deployment.

Its default SHALL be off, so every rendering that does not deliberately set it produces a bundle that
serves normally.

#### Scenario: A deployment that does not set it serves normally

- **WHEN** a deployment is resolved without declaring the maintenance key
- **THEN** the rendered backend configuration reports the window closed

#### Scenario: The flag reaches only the backend bundle

- **WHEN** the resolver emits every rendering
- **THEN** the maintenance key appears in the backend bundle's rendering and in no other

### Requirement: A second deployment sharing a first one's values does so through a component

Two deployments that must agree on a set of values SHALL share them by **both extending the same
component**, never by one deployment extending another and never by restating them.

This follows from the composition rule already stated: a component may not itself declare `extends`, so a
deployment cannot be extended. Restating the shared values in the second file would put them in two places
with nothing binding them — the drift class this capability exists to make impossible, applied to the
device-facing domain and the credential references, which are exactly the values whose disagreement is
silent and expensive.

Applying this: the production deployment's own keys move into a component, and both the production
deployment and the maintenance deployment extend it — the latter adding the maintenance flag and nothing
else.

#### Scenario: The maintenance deployment differs by exactly one key

- **WHEN** the production and maintenance deployments are resolved
- **THEN** every key resolves identically except the maintenance flag

#### Scenario: A deployment is not extended

- **WHEN** a deployment file names another deployment file in its `extends` list
- **THEN** resolution fails, because that file declares `extends` and a component may not
