package app.snapsync.compose

import app.snapsync.ports.Clock
import app.snapsync.services.backend.BackendServices
import app.snapsync.services.backend.CredentialedBackend
import app.snapsync.services.trust.DeviceAttestation
import app.snapsync.services.version.AppVersionGate

/**
 * The app's attestation service (capability `privacy-security`): proves with the platform's integrity service,
 * reaches the backend's ungated `/attest/…` routes on the raw port, and reports a refused build from them to
 * [versionGate].
 *
 * A top-level factory rather than an `AppCore` body because `AppCore` is measured (see [shareSetLoadFor]).
 */
internal fun attestationFor(ports: AppPorts, clock: Clock, versionGate: AppVersionGate): DeviceAttestation =
    DeviceAttestation(
        integrity = ports.integrity,
        backend = ports.backend,
        store = ports.attestStore,
        identity = ports.deviceIdentity,
        clock = clock,
        versionGate = versionGate,
    )

/**
 * Every need-shaped backend service over ONE authenticated backend whose credential is [attestation] — a rejected
 * token is dropped, a new one obtained, and the call retried once — and whose verdicts reach [versionGate].
 */
internal fun backendServicesFor(
    ports: AppPorts,
    attestation: DeviceAttestation,
    versionGate: AppVersionGate,
): BackendServices =
    BackendServices(CredentialedBackend(ports.backend, attestation, versionGate), ports.deviceIdentity)
