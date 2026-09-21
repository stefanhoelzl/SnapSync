## MODIFIED Requirements

### Requirement: The upload producers are never both started

A `:test:architecture` guard SHALL pin the invariants of `upload-lifecycle` that the compiler cannot. Exclusion
is now **gated** — each engine declines at its own entry gate, and the transitions keep the OS registration
consistent with resolution (`upload-lifecycle`, "Exactly one mechanism writes the ledger, enforced at each
engine's entry gate") — so the guard follows the risk to the three places it now lives:

- **The resolver's cells.** The guard SHALL drive the pure mechanism resolution over **every**
  combination of OS facts, permission, and override, asserting that no combination yields a mechanism the
  OS cannot run. A wrong cell yields the OS-driven kind below iOS 26.1, where its registration selector does
  not exist, and the process aborts.
- **The admission gates.** The guard SHALL drive both processes' admission answers — the app's (from
  resolution, override included) and the extension's (from the permission alone; it cannot read the override)
  — over every combination, together with the registration state.
- **The transitions.** The guard SHALL drive sequences of the transitions — join, reconfigure, permission
  change (in both `GRANTED` ↔ `LIMITED` directions, and to and from no usable access), launch, leave, and the
  override being set or cleared — over a fake registration that refuses every write under `LIMITED` and whose
  read is grant-dependent, and a fake app-driven engine.

After every step it SHALL assert that no reachable state admits two writers — the app engine admitted while the
extension is registered and admitted — that no enable bypasses the disable → demote → enable ritual, that no
compared reconcile attempts a registration write under a non-`GRANTED` grant, and that the app engine is armed
only when resolution yields the app-driven kind for an upload-inclusive membership.

The guard SHALL NOT be retired on the grounds that "both started" is no longer expressible, nor on the grounds
that no single held mechanism exists to inspect. Exclusion moving from a structural reference to a gate
decision plus OS state is exactly what makes the guard necessary: the failure it now catches is an admission
function and a registration reconcile drifting apart.

#### Scenario: No transition sequence admits two writers
- **WHEN** the guard drives every transition, in sequence and across permission flips and override changes
- **THEN** at no step is the app engine admitted while the extension is registered and admitted, and the build
  fails if any sequence violates this

#### Scenario: A resolver cell that cannot run on its OS fails the build
- **WHEN** any combination of OS facts, permission and override resolves to a mechanism whose platform
  API does not exist on that OS
- **THEN** the guard fails the build

#### Scenario: A bare enable fails the build
- **WHEN** a transitions change registers the extension without the leading disable and the demote
- **THEN** the guard fails the build

#### Scenario: An override that leaves the extension registered fails the build
- **WHEN** a change stops the override from triggering the registration reconcile, so a pin to the app-driven
  kind under `GRANTED` leaves the extension registered
- **THEN** the guard fails the build
