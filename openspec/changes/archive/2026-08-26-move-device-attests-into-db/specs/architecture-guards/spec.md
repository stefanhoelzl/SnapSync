## ADDED Requirements

### Requirement: The token-rejection route into the trust feature is pinned

A guard SHALL assert that the iOS root still routes a rejected credential from the shared HTTP client into
the attestation feature — that the client is constructed with a rejection hook, and that the hook reaches
the feature's rejection entry point and triggers a refresh.

This one wiring cannot be moved into the shared composition, and the reason is a construction cycle, not an
oversight: the composed core's ports are built over the HTTP client, and the client reads its credential —
and reports its rejection — from the core. Two lazy bindings break the cycle, and `:domain` is platform-free
so it cannot build the platform client itself. Something outside the composition must hand the core's
callback to the client, and that something is the shell by definition.

The guard therefore covers what testing cannot reach here. Both sides of the route are tested — the client's
interceptor in its adapter module, the feature's rejection handling in its own suite — but the join lives in
shell source, which is wiring-only and untested by law and invisible to the world harness. Without the pin,
deleting a lambda whose purpose is not legible at its call site leaves every test green while the app loses
its only recovery from a rejected credential.

The guard SHALL assert presence, not behaviour: it establishes that the route is still connected, which is
the failure mode that would otherwise rot silently. It SHALL fail closed if the source it scans cannot be
found, so it cannot pass by scanning nothing.

#### Scenario: Removing the wiring fails the build

- **WHEN** the root no longer passes the rejection hook into the shared client, or the hook no longer
  reaches the attestation feature
- **THEN** the guard fails

#### Scenario: The guard cannot pass vacuously

- **WHEN** the shell source the guard scans is absent or renamed out from under it
- **THEN** the guard fails rather than reporting success over nothing
