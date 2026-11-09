package app.snapsync.composition.app

import app.snapsync.application.trust.RefreshAttestation
import app.snapsync.composition.process.ProcessGraph
import app.snapsync.data.backend.BackendExchange
import app.snapsync.data.backend.BackendGateway
import app.snapsync.data.backend.BuildTarget
import app.snapsync.data.backend.Observers
import app.snapsync.data.event.EventDirectory
import app.snapsync.data.event.EventRename
import app.snapsync.data.membership.EventJoin
import app.snapsync.data.membership.LeaveNotifier
import app.snapsync.data.push.PushEndpointPublisher
import app.snapsync.data.service.ServiceConditions
import app.snapsync.data.trust.AttestationIssuer
import app.snapsync.data.trust.AttestationStatus
import app.snapsync.data.trust.AttestingCredential
import app.snapsync.data.version.AppVersionGate
import app.snapsync.inbound.AppProcessOnly

/**
 * **The backend's protocol and the app's credential** (`docs/architecture.md`, "The backend boundary"): ONE exchange
 * over [AppPorts.http] and ONE gateway, so no request can go without the credential or without the version verdicts,
 * and every need-shaped backend service over it. Only the app process attests: `DCAppAttestService.isSupported` is
 * false in an app extension (measured), and the extension reads the token this writes.
 */
@OptIn(AppProcessOnly::class)
class BackendGraph(ports: AppPorts, process: ProcessGraph, services: ServicesGraph) {
    /** Whether the backend is refusing this build as too old — observed by the status host from its first frame. */
    val versionGate: AppVersionGate = AppVersionGate()

    /** What the app's credential has learnt: a usable token, why the service refused it, and every new token. */
    val attestationStatus: AttestationStatus = AttestationStatus()

    /** Whether SnapSync's service is failing, as the latest answer told — an opening forgets it. */
    val serviceConditions: ServiceConditions = ServiceConditions()

    /** Where every request goes, the version it declares, and the reply it reads — reporting a refused build. */
    val exchange: BackendExchange =
        BackendExchange(ports.http, BuildTarget(process.build), Observers(versionGate, serviceConditions))

    /** The bearer token EVERY backend call carries, kept alive and recovered when the backend rejects it. */
    val attestation: AttestingCredential = AttestingCredential(
        store = services.credentials,
        issuer = AttestationIssuer(ports.integrity, exchange),
        identity = services.deviceIdentity,
        clock = process.clock,
        status = attestationStatus,
    )

    /** The wake's refresh of [attestation], the use case every wake's prelude calls. */
    val refreshAttestation: RefreshAttestation = RefreshAttestation(attestation)

    /** The one gateway: a rejected token is dropped, a new one obtained, and the request retried once. */
    val gateway: BackendGateway = BackendGateway(exchange, attestation)

    val directory: EventDirectory = EventDirectory(gateway, services.metaCipher)
    val rename: EventRename = EventRename(gateway, services.metaCipher)
    val join: EventJoin = EventJoin(gateway)
    val leave: LeaveNotifier = LeaveNotifier(gateway, services.deviceIdentity)
    val push: PushEndpointPublisher = PushEndpointPublisher(gateway)
}
