package app.snapsync.compose

import app.snapsync.data.backend.BackendExchange
import app.snapsync.data.backend.BackendGateway
import app.snapsync.data.trust.AttestationIssuer
import app.snapsync.data.trust.AttestationStatus
import app.snapsync.data.trust.AttestingCredential
import app.snapsync.inbound.AppProcessOnly
import app.snapsync.ports.Clock
import app.snapsync.services.backend.BackendServices

/**
 * The app's credential: it proves with the platform's integrity service and reaches the backend's open `/attest/…`
 * issuers through [exchange], which reports a refused build to the version gate; its verdicts publish into [status].
 * Only the app process attests.
 *
 * A top-level factory rather than an `AppCore` body because `AppCore` is measured (see [shareSetLoadFor]).
 */
@OptIn(AppProcessOnly::class)
internal fun attestingCredentialFor(
    services: AppServices,
    clock: Clock,
    exchange: BackendExchange,
    status: AttestationStatus,
): AttestingCredential =
    AttestingCredential(
        store = services.credentials,
        issuer = AttestationIssuer(services.ports.integrity, exchange),
        identity = services.deviceIdentity,
        clock = clock,
        status = status,
    )

/**
 * Every need-shaped backend service over ONE gateway whose credential is [attestation] — a rejected token is dropped,
 * a new one obtained, and the request retried once — over [exchange], whose verdicts reach the version gate.
 */
internal fun backendServicesFor(
    services: AppServices,
    attestation: AttestingCredential,
    exchange: BackendExchange,
): BackendServices =
    BackendServices(
        BackendGateway(exchange, attestation),
        services.deviceIdentity,
        zone = { services.process.clock.timeZone().id },
    )
