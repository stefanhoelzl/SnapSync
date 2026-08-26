## ADDED Requirements

### Requirement: Push registration is started by the shared composition

Push registration SHALL be **started** by an explicit installer on the composed core (`compose/`),
alongside `AppCore.installPermissionSubscriptions()`, and the root SHALL do no more than invoke it from
its host-assembly path, handing over the platform-shaped pieces it built.

The registration client and the token source remain **constructed by the shell**: both are platform
objects — a Ktor client over the shell's shared HTTP stack, and the compile-time APNs environment — and
`:domain` builds no platform object. What moves is the ordering and the subscription, which is where the
behaviour is.

The installer SHALL own the subscription that re-sends a registration after a new device token is
obtained. That retry is not an optimisation: the app writes its registration **once per APNs token the OS
delivers**, so a registration refused because the credential was rejected is otherwise never re-sent, and
the device goes permanently unregistered — no silent pushes, no download wakes, and none of the wake-driven
attestation renewals that depend on them.

The reason this belongs in the composition rather than the shell is that it is a **join between two
features that are blind to each other** — the trust feature emits that a new token exists, the push feature
consumes it — and a join is a behaviour, not wiring. Assembled in the shell it is unreachable by the world
harness (which composes the shared composition, not the root) and untestable by law (`:app:*` Kotlin is
wiring-only and untested), so nothing would observe it being removed.

#### Scenario: A refused registration is re-sent after a fresh credential

- **WHEN** a push registration write is refused, and the app subsequently obtains a new device token
- **THEN** the registration is written again without waiting for the OS to deliver another APNs token

#### Scenario: The shell installs and decides nothing

- **WHEN** the root assembles the host
- **THEN** it invokes the installer with the platform pieces it built, and holds no registration ordering
  or retry logic of its own
