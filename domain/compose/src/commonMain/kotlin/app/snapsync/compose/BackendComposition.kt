package app.snapsync.compose

import app.snapsync.ports.Clock
import app.snapsync.ports.Crypto
import app.snapsync.services.backend.BackendExchange
import app.snapsync.services.backend.BackendGateway
import app.snapsync.services.backend.BackendServices
import app.snapsync.services.trust.DeviceAttestation
import app.snapsync.services.upload.Resuming
import app.snapsync.services.upload.UploadStandings

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

/**
 * What makes the app's file uploads resume where a failed attempt stopped (`docs/architecture.md`, "Resumable
 * uploads"): where a failed upload stands, asked of the backend through [backend]'s gateway, and the hash its file is
 * declared by. A top-level factory for the reason [attestationFor] is.
 */
internal fun uploadResumingFor(services: AppServices, backend: BackendServices, crypto: Crypto): Resuming =
    Resuming(
        UploadStandings(backend.gateway, services.config::activeEventId, services.deviceIdentity),
        crypto,
    )
