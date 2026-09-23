package app.snapsync.compose

import app.snapsync.feature.album.AlbumCoordinator
import app.snapsync.feature.membership.DeviceManifestProducer
import app.snapsync.feature.upload.CycleGate
import app.snapsync.feature.upload.JoinedMembership
import app.snapsync.feature.upload.LedgerWriter
import app.snapsync.feature.upload.SyncEngine
import app.snapsync.feature.upload.SelectionScopedDiscovery
import app.snapsync.feature.upload.UploadAdmission
import app.snapsync.feature.upload.UploadCycle
import app.snapsync.feature.upload.cycleGate
import app.snapsync.model.SelectionScope
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.selectionPolicyFor
import app.snapsync.model.EdgeUploadRequestProvider
import app.snapsync.model.denormalizeAssetId
import app.snapsync.ports.AlbumManager
import app.snapsync.ports.DeviceIdentity
import app.snapsync.ports.PhotoGrantRead
import app.snapsync.feature.upload.extensionAdmission
import app.snapsync.ports.BackgroundTransfer
import app.snapsync.ports.UploadDiscovery
import app.snapsync.ports.ConfigRead
import app.snapsync.ports.ConfigReader
import app.snapsync.ports.DiagnosticsReporter
import app.snapsync.ports.DeviceIdentityAbsent
import app.snapsync.ports.DeviceManifestStore
import app.snapsync.ports.ManifestPublisher
import app.snapsync.ports.SecureStoreUnavailable
import app.snapsync.ports.LedgerStore
import app.snapsync.ports.SuppressionSource
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope

/**
 * The uploader process a cycle runs in, and so where its admission comes from.
 *
 * The app's admission is the composed core's own answer (grant, selection scope and rig pin — all in-process
 * state), so it is a callback into that core. The extension's is a platform read of its own grant, so it is
 * a port, and the rule applied to it (`extensionAdmission`) lives here rather than in the extension's root —
 * which is what used to hold both, as an inline lambda reading PhotoKit.
 */
sealed interface UploaderProcess {
    class App(val admission: () -> UploadAdmission) : UploaderProcess
    class Extension(val grant: PhotoGrantRead) : UploaderProcess
}

/**
 * The ports one upload-cycle assembly consumes (spec `module-architecture`, "One shared
 * composition"): port interfaces plus the thunks whose *call time* is load-bearing. A root
 * constructs its adapters and states its policies here; [uploadCore] does the assembling — so a
 * port added to the cycle is added to this bundle once, and every tier (and the world harness)
 * fails to compile until it answers, instead of one tier silently shipping without it (which is
 * how the app-driven tier once shipped without the direction gate).
 */
class UploadPorts(
    /** The three-state membership read (capability `event-link`). Read fresh once per cycle. */
    val config: ConfigReader,
    /**
     * The device identity. Its resolve MUST throw [SecureStoreUnavailable] while protected data is
     * unavailable (never mint, never return a placeholder); the implementation caches its first success,
     * so this is one Keychain read per process in practice.
     */
    val deviceIdentity: DeviceIdentity,
    /**
     * The build-time upload host — a constant of the running build, so a plain value (law "Ports are the I/O
     * boundary named for the need": a build constant is passed as a value, not a thunk). Blank when the build
     * carries none, which the gate treats as "cannot upload".
     */
    val host: String,
    val ledger: LedgerStore,
    val transfer: BackgroundTransfer,
    /**
     * The cycle's photo-library reads (capability `ios-url-session-upload`, "Ledger keys resolve to uploadable
     * resources"): bound once per root — `IosDiscovery` on both device tiers — and never by a transport.
     */
    val discovery: UploadDiscovery,
    /** Crash/error reporting (capability `crash-reporting`). Required on both tiers — see AppPorts. */
    val diagnosticsReporter: DiagnosticsReporter,
    /**
     * Which uploader process this cycle runs in, which decides whether it may run a cycle now (capability
     * `upload-lifecycle`, "The upload cycle owns its entry decision"), read once per gate. Required, with **no
     * default**: the app answers from its composed core (admit under any usable grant), the extension from its
     * own photo grant (admit only under `GRANTED`), and a default would state either answer silently — for the
     * extension, a wrong admit reads the whole library under a partial grant. It is decided before the
     * membership's policy is built, so an undetermined grant never reaches the album read that prompts.
     */
    val process: UploaderProcess,
    /**
     * What upload discovery may read (capability `limited-photo-access`): [SelectionScope.Unrestricted]
     * walks as ever; [SelectionScope.Scoped] makes discovery consume the selection snapshot with no
     * platform read. The default keeps every full-grant composition byte-identical — the extension root
     * keeps it because it never reads the library under a partial grant: the OS does invoke a surviving
     * registration there (measured SE2/26.6, 2026-09-21), but its admission withholds before any read — and
     * the world opts in per test. Derived by the app composition from current permission + the latest snapshot.
     */
    val selectionScope: () -> SelectionScope = { SelectionScope.Unrestricted },
    val manifestStore: DeviceManifestStore,
    /** The device-manifest publisher — production passes `:adapter:generic:app`'s `HttpManifestPublisher`. */
    val manifestPublisher: ManifestPublisher,
    /** Echo-suppression (capability `photo-download`): required, no default (`upload-lifecycle`). */
    val suppression: SuppressionSource,
    /** The album port the policy's denylisted-album read goes through (capability `photo-selection-policy`). */
    val albumManager: AlbumManager,
    /** How this tier answers a failed denylisted-album lookup — see [AlbumLookupFailure]. */
    val albumLookupFailure: AlbumLookupFailure,
    /** Event-album placement (capability `event-album`); the `denormalizeAssetId` mapping is shared here. */
    val albumCoordinator: AlbumCoordinator,
    /** The attestation bearer token, read per request. Required: `{ null }` must be stated, not inherited. */
    val token: suspend () -> String?,
    /**
     * The calling build's marketing version, declared on the byte upload (capability `min-app-version`).
     *
     * A plain value, and required: each process builds its own bundle in its own root and reads its own
     * bundle there, so there is no other process's answer to bind. It used to be a thunk defaulting to `""`,
     * which let a composition declare no version to the min-app-version gate without saying so.
     */
    val appVersion: String,
    val log: Logger = Logger.withTag("UploadCycle"),
)

/**
 * The ONE upload-cycle assembly (spec `module-architecture`, "One shared composition"): both device
 * tiers' roots and the world harness call this — there is no second wiring, so a wiring difference
 * between the harness and production is impossible rather than undetected.
 *
 * [scope] is the process scope the composition contract receives (the law's signature). Nothing in
 * the upload subset consumes it yet; migration step 8 installs the port-state-transition
 * subscriptions here, which do.
 */
@Suppress("UNUSED_PARAMETER")
fun uploadCore(scope: CoroutineScope, ports: UploadPorts): UploadCycle {
    // First act (idempotent — the app process composes this beside snapSyncApp): the extension
    // process has no other composition entry, so this is where its reporter comes up.
    ports.diagnosticsReporter.start()
    val ledger = LedgerWriter(ports.ledger)
    // Constructed lazily so the device id resolves on first in-cycle use — after the gate's probe
    // has succeeded — never at composition time, where a locked device would throw out of assembly.
    val manifestProducer by lazy {
        DeviceManifestProducer(
            store = ports.manifestStore,
            publisher = ports.manifestPublisher,
            deviceId = ports.deviceIdentity.deviceId(),
        )
    }
    return UploadCycle(
        readGate = { readGate(ports) },
        // Bytes go to the device's event-independent partition (/files/devices/<deviceId>/…); the
        // eventId drives only the producer's event scope + the device-manifest write, not the byte URL.
        engineFor = { config ->
            // Built per cycle because the host arrives with the gate's config, not at composition time.
            SyncEngine(
                EdgeUploadRequestProvider(
                    config.host,
                    ports.deviceIdentity.deviceId(),
                    ports.token,
                    ports.appVersion,
                ),
                ledger,
            )
        },
        ledger = ledger,
        platform = ports.transfer,
        // The read-discipline gate (capability `limited-photo-access`): the ONE shared assembly wraps
        // the library reads, so every tier and the world get the same walk-vs-snapshot decision.
        library = SelectionScopedDiscovery(ports.discovery, ports.selectionScope),
        log = ports.log,
        // Device manifest (capability `device-manifest`) from the cycle's OWN discovery — no second
        // library enumeration. Bounding is the cycle's.
        // The manifest DECLARES what this device will provide: every non-absent ledger row, whatever its
        // upload state. This hook therefore needs no discovery of its own — the cycle has already
        // recorded every admitted resource and backfilled the bare ones by the time it fires, and the
        // rows it recorded THIS cycle are part of what it declares.
        onDiscovery = { eventId, policy, manifestVersion ->
            manifestProducer.produce(
                eventId = eventId,
                policy = policy, // the ONE admission (capability `photo-selection-policy`)
                rows = ledger.manifestRows(),
                // Read by the gate BEFORE the membership, so every change the rows or the policy miss
                // carries a higher version (capability `sync-ledger`).
                manifestVersion = manifestVersion,
            )
        },
        // The cycle applies the membership's opt-in (it arrived with the gate); this translation
        // only reverses the normalized `assetId` (`_`→`/`) — previously copied identically at all
        // three call sites.
        placeInAlbum = { eventId, assetIds ->
            ports.albumCoordinator.place(eventId, assetIds.map(::denormalizeAssetId))
        },
    )
}

/**
 * THE ENTRY-GATE TRANSLATION (capability `upload-lifecycle`, "The upload cycle owns its entry
 * decision") — one implementation over the ports, where three per-root copies used to live. It is
 * **port-pure**: one fresh [ConfigReader.read] per cycle, the identity probe, the host read, and the
 * root's admission answer — and deliberately nothing else.
 *
 * ⚖️ UNIFICATION DECISION (design D1 of `establish-shared-composition` — the one sanctioned
 * semantic change of migration step 7): the app-driven tier's copy additionally called
 * the config store's `reload()` (then Keychain-backed; today `FileBackedConfigStore.reload()`)
 * before the read, refreshing the UI-facing `ConfigSource`
 * StateFlow each cycle; the extension's copy did not. The extension's semantics win:
 *  - the spec names the gate's inputs exhaustively (membership read, identity probe, host) — a
 *    StateFlow refresh is a read-model side effect riding in the gate, not gate logic;
 *  - `reload()` exists only on the concrete adapter, not on any port, so it is inexpressible here
 *    by law — and that is the spec's own shape, not a workaround;
 *  - the gate *outcome* is provably unchanged: the controller decided from a second, fresh
 *    `read()` after the reload, identical to reading once;
 *  - the StateFlow's one real staleness case (seeded `null` while locked) is repaired by the
 *    trigger flows' membership re-read (`AppPorts.configRefresh`, migration step 12 — before that,
 *    the app shell's `ProtectedDataGate` unlock hook), which every trigger runs before acting.
 */
private suspend fun readGate(ports: UploadPorts): CycleGate {
    // The manifest version FIRST — before the membership, and so before the policy and the rows the manifest
    // is projected from (capability `upload-lifecycle`). Every change that could alter the projection
    // advances it, so a change this cycle's projection misses happened after this read and carries a higher
    // version. Unreadable (a locked device's protected ledger) is "I could not look", like the config.
    val version = runCatching { ports.ledger.manifestVersion() }
    val read = ports.config.read()
    // The identity probe — an unresolvable id is "I could not look", never "no id", so it belongs
    // on the unreadable side of the roll-up. Every outcome needs the id: the reconciler and the
    // manifest producer each close over it, so even the leave-side branch touches it.
    //
    // `DeviceIdentityAbsent` joins `SecureStoreUnavailable` here, and the two are handled identically on
    // purpose. It means the lookup succeeded, found nothing, and this process may not mint (the upload
    // extension — capability `device-identity`). Both are "proceed with no identity", and proceeding
    // is exactly what must not happen: an invented id partitions this device's bytes away from its own
    // manifest. Anything else still propagates — a genuine fault must not be silently downgraded to a
    // skipped cycle.
    val identityFailure = runCatching { ports.deviceIdentity.deviceId() }
        .onFailure { if (it !is SecureStoreUnavailable && it !is DeviceIdentityAbsent) throw it }
        .exceptionOrNull()
    val idReadable = identityFailure == null
    val payload = (read as? ConfigRead.Joined)?.config
    return cycleGate(
        configReadable = read !is ConfigRead.Unavailable && idReadable && version.isSuccess,
        membership = payload?.let {
            JoinedMembership(
                eventId = it.eventId,
                // A supplier, not a value: the derivation reads two ports and this translation must stay
                // port-pure. Closing over them is not calling them (capability `upload-lifecycle`).
                policy = {
                    selectionPolicyFor(
                        config = it,
                        suppressedAssetIds = { ports.suppression.suppressedLocalIds() },
                        albumExcludedAssetIds = { cutoff ->
                            denylistedAlbumMembers(ports.albumManager, cutoff, ports.albumLookupFailure, ports.log)
                        },
                    )
                },
                saveToAlbum = it.saveToAlbum,
                manifestVersion = version.getOrDefault(0L),
            )
        },
        host = ports.host,
        // Whether THIS process may run (capability `upload-lifecycle`): each root states its own answer —
        // the app from resolution, the extension from its own grant read.
        admission = when (val process = ports.process) {
            is UploaderProcess.App -> process.admission()
            is UploaderProcess.Extension -> extensionAdmission(process.grant.current())
        },
        // The forensics for a skip: the decision is made in shared code that cannot see WHY the
        // read failed, and an unreadable config is invisible on a device except through this string.
        skipDetail = skipDetail(read, identityFailure, version.exceptionOrNull()),
    )
}

/** The skip line [readGate] hands the cycle: which read failed, and how. */
private fun skipDetail(read: ConfigRead, identityFailure: Throwable?, versionFailure: Throwable?): String =
    "protected data unavailable (config status=" +
        "${(read as? ConfigRead.Unavailable)?.status}, deviceId readable=${identityFailure == null}" +
        // Naming WHICH identity failure occurred is the difference between "the device is locked,
        // this will pass" and "this process has no identity and may not create one", which need
        // opposite reactions from whoever reads the log.
        when (identityFailure) {
            is DeviceIdentityAbsent -> ", deviceId absent and unmintable here"
            is SecureStoreUnavailable -> ", deviceId unreadable (${identityFailure.detail})"
            else -> ""
        } +
        (if (versionFailure != null) ", manifest version unreadable ($versionFailure)" else "") + ")"
