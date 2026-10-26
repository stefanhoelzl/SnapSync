package app.snapsync.compose

import app.snapsync.data.backend.BackendExchange
import app.snapsync.data.crypto.EventKeyStore
import app.snapsync.data.crypto.FileCipher
import app.snapsync.data.identity.DeviceIdentity
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.data.store.SharedDatabase
import app.snapsync.data.trust.CachedCredentialStore
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.feature.album.AlbumCoordinator
import app.snapsync.feature.membership.DeviceManifestProducer
import app.snapsync.feature.upload.CycleGateRead
import app.snapsync.feature.upload.LedgerWriter
import app.snapsync.feature.upload.SelectionScopedDiscovery
import app.snapsync.feature.upload.SyncEngine
import app.snapsync.feature.upload.UploadAdmission
import app.snapsync.feature.upload.UploadCycle
import app.snapsync.feature.upload.extensionAdmission
import app.snapsync.model.SelectionScope
import app.snapsync.ports.GalleryReader
import app.snapsync.services.backend.BackendUploadRequests
import app.snapsync.services.backend.ManifestPublisher
import app.snapsync.services.crypto.UploadSealing
import app.snapsync.services.downloads.RecognitionSource
import app.snapsync.services.downloads.SuppressionSource
import app.snapsync.services.gallery.UploadDiscovery
import app.snapsync.services.gallery.galleryAccess
import app.snapsync.services.identity.requireDeviceId
import app.snapsync.services.ledger.LedgerService
import app.snapsync.services.manifest.DeviceManifestService
import app.snapsync.services.membership.MembershipProjection
import app.snapsync.services.settings.MobileDataSetting
import app.snapsync.services.upload.TransferSetup
import app.snapsync.services.upload.Uploader
import co.touchlab.kermit.Logger

/**
 * The uploader process a cycle runs in, and so where its admission comes from.
 *
 * The app's admission is the composed core's own answer (grant, selection scope and rig pin — all in-process
 * state), so it is a callback into that core. The extension's is a platform read of its own grant, so it is
 * a port, and the rule applied to it (`extensionAdmission`) lives here rather than in the extension's root —
 * which is what used to hold both, as an inline lambda reading PhotoKit.
 *
 */
sealed interface UploaderProcess {
    /** Whether THIS process may create now, read once per gate. */
    fun admission(): UploadAdmission

    class App(private val admit: () -> UploadAdmission) : UploaderProcess {
        override fun admission() = admit()
    }

    /** The extension, admitting on its own [grant] read — a status read, never a request. */
    class Extension(private val grant: GalleryReader) : UploaderProcess {
        override fun admission() = extensionAdmission(grant.access().galleryAccess())
    }
}

/**
 * What one upload-cycle assembly consumes (`docs/architecture.md`, "One shared composition"): the SERVICES a process
 * built over its ports, plus the thunks whose *call time* is load-bearing. Internal — no root builds one: the app's
 * uploader builds it from its core ([AppUploader]), the extension from its own ports ([snapSyncExtension]) — so a
 * member added to the cycle is added here once, and both tiers fail to compile until they answer, instead of one tier
 * silently shipping without it (which is how the app-driven tier once shipped without the direction gate).
 */
internal class UploadServices(
    /** The shared database the records below live in — asked first whether they can be read here. */
    val records: SharedDatabase,
    /** The membership, re-read at every invocation of the extension. */
    val membership: MembershipRepository,
    /** The three-state membership read. Read fresh once per cycle. */
    val config: MembershipProjection,
    /** The device's mobile-data choice, read as each upload job is created. */
    val mobileData: MobileDataSetting,
    /**
     * The device identity: the app's mints, the extension's only reads. Unreadable while protected data is
     * unavailable — never a placeholder; it keeps its first success, so this is one Keychain read per process in
     * practice.
     */
    val deviceIdentity: DeviceIdentity,
    /**
     * The build-time upload host — a constant of the running build, so a plain value (law "Ports are the I/O
     * boundary named for the need": a build constant is passed as a value, not a thunk). Blank when the build
     * carries none, which the gate treats as "cannot upload".
     */
    val host: String,
    val ledger: LedgerService,
    /**
     * This tier's uploader (`docs/architecture.md`, "Background execution"): the OS's upload-job queue in the
     * extension, the app's own session in the app. What its jobs mean for the ledger is the upload services', composed
     * over it here.
     */
    val uploader: Uploader,
    /** The photo library — what exports a resource to a file for an uploader that sends files. */
    val gallery: GalleryReader,
    /**
     * The cycle's photo-library reads, through which ledger keys resolve to uploadable resources: bound once per
     * root — `GalleryDiscovery` over the gallery on both device tiers — and never by a transport.
     */
    val discovery: UploadDiscovery,
    /**
     * Which uploader process this cycle runs in, which decides whether it may run a cycle now — the upload cycle
     * owns its entry decision — read once per gate. Required, with **no
     * default**: the app answers from its composed core (admit under any usable grant), the extension from its
     * own photo grant (admit only under `GRANTED`), and a default would state either answer silently — for the
     * extension, a wrong admit reads the whole library under a partial grant. It is decided before the
     * membership's policy is built, so an undetermined grant never reaches the album read that prompts.
     */
    val process: UploaderProcess,
    /**
     * What upload discovery may read: [SelectionScope.Unrestricted]
     * walks as ever; [SelectionScope.Scoped] makes discovery consume the selection snapshot with no
     * platform read. The default keeps every full-grant composition byte-identical — the extension root
     * keeps it because it never reads the library under a partial grant: the OS does invoke a surviving
     * registration there (measured SE2/26.6, 2026-09-21), but its admission withholds before any read — and
     * the world opts in per test. Derived by the app composition from current permission + the latest snapshot.
     */
    val selectionScope: () -> SelectionScope,
    val manifestStore: DeviceManifestService,
    /**
     * The device-manifest publisher — a backend service over this process's authenticated backend: the app passes
     * its core's (`AppCore.backend`), the extension the one [extensionBackend] composes.
     */
    val manifestPublisher: ManifestPublisher,
    /** Echo-suppression: required, no default. */
    val suppression: SuppressionSource,
    /** Whether the membership's received photos have been recognised: required. */
    val recognition: RecognitionSource,
    /**
     * The policy's denylisted-album read: this tier's `denylistedAlbumMembers` over its
     * own grant read and its own `AlbumLookupFailure` answer.
     */
    val albumExclusions: suspend (CaptureCutoff) -> Set<AssetId>,
    /** Event-album placement. */
    val albumCoordinator: AlbumCoordinator,
    /**
     * The process's copy of the attestation token every upload request carries — read per request, and from the store
     * of record for a retry's, which picks up a token the other process renewed.
     */
    val credentials: CachedCredentialStore,
    /**
     * The process's backend exchange: where a byte upload goes and the version it declares — the same every
     * backend request carries, read off the process's own bundle.
     */
    val exchange: BackendExchange,
    /**
     * The joined event's key, when it is encrypted (the encrypted file format, `docs/architecture.md`): what each
     * upload is sealed under, as re-read at every entry. Required: the app passes its own, the extension one over the
     * shared slot.
     */
    val keys: EventKeyStore,
    val log: Logger = Logger.withTag("UploadCycle"),
)

/**
 * The ONE upload-cycle assembly (`docs/architecture.md`, "One shared composition"): the app's uploader and the upload
 * extension both call this — there is no second wiring, so a wiring difference between the tiers is impossible rather
 * than undetected.
 */
internal fun uploadCycle(process: ProcessServices, ports: UploadServices): UploadCycle {
    // [process] proves the process set up its crash reporting before this cycle was composed (`snapSyncProcess`, every
    // root's first act), and supplies the one `Files` a file uploader's staged bytes live in.
    val ledger = LedgerWriter(ports.ledger)
    // The read-discipline gate: the ONE shared assembly wraps the library reads, so every
    // tier and the world get the same walk-vs-snapshot decision — the cycle's, and the transfer's live-resource lookup.
    val library = SelectionScopedDiscovery(ports.discovery, ports.selectionScope)
    val transfer = ports.uploader.transferFor(
        TransferSetup(
            record = ports.ledger,
            resources = library,
            gallery = ports.gallery,
            files = process.files,
            network = ports.mobileData::transferNetwork,
            sealing = UploadSealing(
                ports.keys,
                FileCipher(process.crypto, process.files),
                ports.config,
                ports.deviceIdentity,
            ),
            log = ports.log,
        ),
    )
    // Constructed lazily so the device id resolves on first in-cycle use — after the gate's probe
    // has succeeded — never at composition time, where a locked device would throw out of assembly.
    val manifestProducer by lazy {
        DeviceManifestProducer(
            store = ports.manifestStore,
            publisher = ports.manifestPublisher,
            deviceId = ports.deviceIdentity.requireDeviceId(),
        )
    }
    return UploadCycle(
        readGate = cycleGateRead(ports)::read,
        // Bytes go to the joined event (/events/<eventId>/files/devices/<deviceId>/…), which owns them
        // (change `per-event-storage-layout`).
        engineFor = { config ->
            // Built per cycle because the host arrives with the gate's config, not at composition time.
            SyncEngine(
                BackendUploadRequests(
                    ports.exchange,
                    config.eventId,
                    ports.deviceIdentity.requireDeviceId(),
                    ports.credentials,
                ),
                ledger,
            )
        },
        ledger = ledger,
        platform = transfer,
        library = library,
        log = ports.log,
        // Device manifest from the cycle's OWN discovery — no second library enumeration. Catching a failed
        // publish is the cycle's; nothing bounds it but the per-request HTTP timeout (no timeout of ours).
        // The manifest DECLARES what this device will provide: every non-absent ledger row, whatever its
        // upload state. This hook therefore needs no discovery of its own — the cycle has already
        // recorded every admitted resource and backfilled the bare ones by the time it fires, and the
        // rows it recorded THIS cycle are part of what it declares.
        onDiscovery = { eventId, policy, manifestVersion ->
            manifestProducer.produce(
                eventId = eventId,
                policy = policy, // the ONE admission
                rows = ledger.manifestRows(),
                // Read by the gate BEFORE the membership, so every change the rows or the policy miss
                // carries a higher version.
                manifestVersion = manifestVersion,
                // Settled once the membership's OWN range has ended: the discovery this hook follows ran just now,
                // after that end, so every in-range photo is declared. One fresh read, like the gate. A range widened
                // past now again publishes unsettled, and the backend follows each publish both ways.
                settled = ports.config.freshReadShareHasEnded(),
            )
        },
        // The cycle applies the membership's opt-in (it arrived with the gate).
        placeInAlbum = { eventId, assetIds -> ports.albumCoordinator.place(eventId, assetIds.toList()) },
    )
}

/** The cycle's entry gate over [ports] — see [CycleGateRead]. */
private fun cycleGateRead(ports: UploadServices) = CycleGateRead(
    records = ports.records,
    ledger = ports.ledger,
    config = ports.config,
    identity = ports.deviceIdentity,
    suppression = ports.suppression,
    recognition = ports.recognition,
    host = ports.host,
    // Each root states its own answer — the app from resolution, the extension from its own grant read.
    admission = ports.process::admission,
    keys = ports.keys,
    albumExclusions = ports.albumExclusions,
)
