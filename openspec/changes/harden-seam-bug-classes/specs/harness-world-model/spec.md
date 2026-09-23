## MODIFIED Requirements

### Requirement: The world composes the app graph through snapSyncApp

The world SHALL hold the app-side graph as a real `AppCore` produced by the **same** `snapSyncApp`
composition the iOS app shell calls (spec `module-architecture`, "One shared composition"),
constructed over an `AppPorts` whose ports are the world's fakes and mini-edge seams. The world SHALL
bind no `AppPorts` field to a body of its own that stands in for core machinery: the provision a join
performs, the attestation refresh and the push registration are built by `snapSyncApp` and run for real in
the world, exactly as on iOS. The world's operator surface is its own levers **beside** the composed core
(the operator `provision()`, `leave()`, the inject/fail levers), never a second body for a seam the core
calls: `onEventMinted` is a routing hook whose default provisions the minted event through the composed
Provision flow (the desktop inspector points it at the status host's pending-join gate), and the
upload producer is inert (nothing auto-runs — the operator plays the OS).
The world's exposed download controller, status sources, creation status, join use-case, and
user-tap command bundle SHALL be `AppCore`'s instances — never world-local rebuilds — so a wiring
difference between the harness and the app shell is impossible rather than undetected.

**One** named deviation is permitted, an operator-synchronicity concern and nothing else: the world's
operator `leave()` MAY remain a synchronous faithful edge beside the bundle's production-ordered leave
(whose backend notify is fire-and-forget by design); tests driving the bundle's leave await the backend
outcome.

The former second deviation — re-installing the composed `downloadJobs.onStaged` hook with an identical
body plus Job retention — is **withdrawn**. It existed only because the seam was non-suspend and the
composition discarded the Job; the feature now tracks its own launches, so the harness has nothing left to
re-install and the permission would only license a divergence nobody needs.

#### Scenario: The harness's app graph is the production graph

- **WHEN** the world harness or an integration test fires a user-tap command (create, commit-join,
  leave)
- **THEN** the command runs through `AppCore.userCommands` — the same compose-built bundle the iOS
  shell injects — over the world's ports, and its effects land in the world's fakes and mini-edge

#### Scenario: A join in the world runs the real Provision flow

- **WHEN** an integration test commits a join through the command bundle
- **THEN** the join's provision step runs the composed `flow/Provision` — membership entry, upload
  transition, push registration, album ensure, status refresh and download reconcile — with no world-local
  body in its place

#### Scenario: The world cannot rebuild what the composition owns

- **WHEN** the world or its inspector needs a status source, download controller, or join use-case
- **THEN** it reads the composed `AppCore`'s instance; no second assembly of a feature graph exists
  in harness code

## ADDED Requirements

### Requirement: The world boots cold

Constructing the world SHALL force no member of the composed `AppCore` that the iOS root does not force at
process start. In particular it SHALL NOT touch a lazily-built feature, flow or controller to obtain a side
effect of its construction; if a harness needs a feature's instance, it reads it at the point of use, as a
production entry point would. The world therefore starts from the same state a cold iOS process starts
from, so a path that only works once something else has been built fails in the world as it would on a
device.

#### Scenario: A wiring that depends on build order

- **WHEN** a collaborator is wired only as a side effect of building another lazy member
- **THEN** a world-driven test that exercises the first collaborator without the second fails, rather than
  passing because the world built the second at construction

#### Scenario: The world's source touches the core eagerly

- **WHEN** `World.kt` reads a member of the composed core in an `init` block or an eagerly initialized
  property, outside a lambda body
- **THEN** the boots-cold gate fails, naming the line — the access must be deferred to use (`by lazy`,
  `get()`, or the function that needs it)
