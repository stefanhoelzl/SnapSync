package app.snapsync.compose

import app.snapsync.ports.Clock
import app.snapsync.services.backend.BackendExchange
import app.snapsync.services.backend.BackendGateway
import app.snapsync.services.backend.BackendServices
import app.snapsync.services.trust.DeviceAttestation

/**
 * The app's attestation service: proves with the platform's integrity service and
 * reaches the backend's open `/attest/…` routes through [exchange], which reports a refused build to the version gate.
 *
 * A top-level factory rather than an `AppCore` body because `AppCore` is measured (see [shareSetLoadFor]).
 */
internal fun attestationFor(services: AppServices, clock: Clock, exchange: BackendExchange): DeviceAttestation =
    DeviceAttestation(
        integrity = services.ports.integrity,
        exchange = exchange,
        store = services.attestStore,
        identity = services.deviceIdentity,
        clock = clock,
    )

/**
 * Every need-shaped backend service over ONE gateway whose credential is [attestation] — a rejected token is dropped,
 * a new one obtained, and the request retried once — over [exchange], whose verdicts reach the version gate.
 */
internal fun backendServicesFor(
    services: AppServices,
    attestation: DeviceAttestation,
    exchange: BackendExchange,
): BackendServices =
    BackendServices(
        BackendGateway(exchange, attestation),
        services.deviceIdentity,
        zone = { services.process.clock.timeZone().id },
    )
