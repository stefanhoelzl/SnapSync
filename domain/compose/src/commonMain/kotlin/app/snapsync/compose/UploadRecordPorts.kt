package app.snapsync.compose

import app.snapsync.feature.upload.UploadLedgerAudit
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.noContribution
import app.snapsync.model.resolveUploadMechanism
import app.snapsync.ports.DeviceFilesSource
import app.snapsync.ports.JoinedEventMarker
import app.snapsync.ports.LedgerStore

/**
 * What this process knows about its own uploads — the three seams that only mean anything together
 * (spec `module-architecture`, "One shared composition").
 *
 * A **cohesive sub-bundle** of [AppPorts], which the `compose` complexity tier's config names as the way
 * that bundle's parameter ceiling comes down: three related ports arriving as three parameters says
 * nothing about how they relate, and the relation here is the whole point. The [ledger] records what this
 * device believes it uploaded; [files] is what the backend reports it actually holds; [joinedMarker] is
 * what says whether the two are yet expected to agree. A reader who holds one without the others cannot
 * answer any question about upload state, and [UploadLedgerAudit] takes exactly these three.
 *
 * Every use of this bundle in the app graph is a **read**. The records are written by whichever process
 * holds the `LedgerWriter` — the extension on iOS ≥26.1, the app's own upload tier on 18–26.0 — and the
 * marker is set and cleared only by the marker-gated re-join reconciliation. Nothing composed over this
 * bundle writes either (capability `sync-ledger`, "Reader and writer capability split").
 */
class UploadRecordPorts(
    /** The app-side ledger handle: the aggregates read, and the rows the read-only check compares. */
    val ledger: LedgerStore,
    /**
     * The per-device stored-file listing (`bunny-list-endpoint`) — the same seam `UploadPorts.deviceFiles`
     * carries for the re-join seed, here as a second, read-only consumer rather than a second endpoint.
     * On iOS 18–26.0 the app process already holds one (`uploadCore` composes there); on ≥26.1 the cycle
     * lives in the extension, so the app binds its own `HttpDeviceFilesSource`.
     */
    val files: DeviceFilesSource,
    /**
     * The persisted `joinedEventId` marker, **read only** here: a mismatch means the ledger is
     * known-divergent and the re-join reconciliation is pending, so any disagreement is expected rather
     * than informative.
     */
    val joinedMarker: JoinedEventMarker,
)

/**
 * Build the read-only foreground check (capability `event-rejoin-reconciliation`) over [ports].
 *
 * A top-level factory rather than an `AppCore` property because `AppCore` is measured: the `compose`
 * tier's `LargeClass` ceiling is what keeps that class from absorbing every composition in the graph,
 * and wiring that needs no other member of the graph does not have to live inside it. [policyFor] is the
 * one thing it does need — the composition's single policy derivation, which reads two ports and is
 * therefore built where the config and both readers are in scope.
 *
 * Composed in the **app** graph and not in `uploadCore`, which is the placement decision the whole check
 * rests on: the cycle's assembly belongs to whichever process holds the `LedgerWriter`, so a check hung
 * off it would never run on iOS ≥26.1 — the tier whose upload jobs carry no HTTP status, and therefore
 * the tier whose belief is least verifiable.
 */
internal fun uploadLedgerAuditFor(
    ports: AppPorts,
    policyFor: suspend () -> SelectionPolicy?,
): UploadLedgerAudit = UploadLedgerAudit(
    files = ports.uploadRecord.files,
    ledger = ports.uploadRecord.ledger,
    marker = ports.uploadRecord.joinedMarker,
    deviceId = ports.deviceId,
    // A membership that is gone by the time this runs contributes nothing, which makes the comparison
    // set empty rather than inventing a cutoff — the invariant this project is built against.
    policy = { policyFor() ?: noContribution() },
    // Reported, never acted on: the structural hypothesis is that the OS-driven tier is where the belief
    // goes wrong, so a report that cannot say which tier it came from answers nothing. Read at report
    // time for the reason `UploadArm` reads it per transition — it is a function of runtime permission.
    mechanism = {
        resolveUploadMechanism(
            backgroundUploadSupported = ports.osSupportsOsDrivenUpload,
            permission = ports.photoAccess.permission.value,
            override = ports.uploadMechanismOverride(),
        )
    },
    clock = ports.clock,
    log = ports.log,
)
