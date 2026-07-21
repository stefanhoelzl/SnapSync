package app.snapsync.compose

import app.snapsync.feature.album.AlbumCoordinator
import app.snapsync.feature.membership.DeviceManifestProducer
import app.snapsync.feature.upload.CycleGate
import app.snapsync.feature.upload.ExtensionReconciler
import app.snapsync.feature.upload.JoinedMembership
import app.snapsync.feature.upload.LedgerWriter
import app.snapsync.feature.upload.SyncEngine
import app.snapsync.feature.upload.SelectionScopedTransfer
import app.snapsync.feature.upload.UploadCycle
import app.snapsync.feature.upload.cycleGate
import app.snapsync.model.SelectionScope
import app.snapsync.model.Contribution
import app.snapsync.model.EdgeUploadRequestProvider
import app.snapsync.model.denormalizeAssetId
import app.snapsync.model.deviceManifestAssetsFromResources
import app.snapsync.ports.BackgroundTransfer
import app.snapsync.ports.ConfigRead
import app.snapsync.ports.ConfigReader
import app.snapsync.ports.CrashReporting
import app.snapsync.ports.DeviceFilesSource
import app.snapsync.ports.DeviceIdentityAbsent
import app.snapsync.ports.DeviceManifestStore
import app.snapsync.ports.DiscoveryStore
import app.snapsync.ports.Enrollment
import app.snapsync.ports.JoinedEventMarker
import app.snapsync.ports.KeychainUnavailable
import app.snapsync.ports.LedgerStore
import app.snapsync.ports.SuppressionSource
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope

/**
 * The ports one upload-cycle assembly consumes (spec `module-architecture`, "One shared
 * composition"): port interfaces plus the thunks whose *call time* is load-bearing. A root
 * constructs its adapters and states its policies here; [uploadCore] does the assembling — so a
 * port added to the cycle is added to this bundle once, and every tier (and the world harness)
 * fails to compile until it answers, instead of one tier silently shipping without it (which is
 * how the app-driven tier shipped without a reconciler and without the direction gate).
 */
class UploadPorts(
    /** The three-state membership read (capability `event-link`). Read fresh once per cycle. */
    val config: ConfigReader,
    /**
     * The device-identity resolve. MUST throw [KeychainUnavailable] while protected data is
     * unavailable (never mint, never return a placeholder); each root keeps its own caching (a
     * `lazy` caches the first success, so this is one Keychain read per process in practice).
     */
    val deviceId: () -> String,
    /** The build-time upload host, read per gate call (the extension reads its bundle each time). */
    val host: () -> String?,
    val ledger: LedgerStore,
    val transfer: BackgroundTransfer,
    /** Crash/error reporting (capability `crash-reporting`). Required on both tiers — see AppPorts. */
    val crashReporting: CrashReporting,
    /**
     * What upload discovery may read (capability `limited-photo-access`): [SelectionScope.Unrestricted]
     * walks as ever; [SelectionScope.Scoped] makes discovery consume the selection snapshot with no
     * platform read. The default keeps every full-grant composition byte-identical — the extension
     * root never sees a partial grant (the OS does not invoke it there), and the world opts in per
     * test. Derived by the app composition from current permission + the latest snapshot.
     */
    val selectionScope: () -> SelectionScope = { SelectionScope.Unrestricted },
    val discoveryStore: DiscoveryStore,
    /** The per-device stored-file listing the re-join reconciliation seeds from. */
    val deviceFiles: DeviceFilesSource,
    val joinedMarker: JoinedEventMarker,
    val manifestStore: DeviceManifestStore,
    /** The device-manifest uploader — production passes `:adapter:generic:app`'s `HttpEnrollment`. */
    val enrollment: Enrollment,
    /** Echo-suppression (capability `photo-download`): required, no default (`upload-lifecycle`). */
    val suppression: SuppressionSource,
    /**
     * Denylisted-album membership (capability `photo-selection-policy`). Supplied as a lambda —
     * not unified here — because the tiers deliberately answer failure differently today (the app
     * tier admits on doubt via its shared wrapper; the extension lets a throw fail the cycle), and
     * this step changes no behavior beyond the entry gate (design D1).
     */
    val albumExcludedAssetIds: suspend (cutoff: String) -> Set<String>,
    /** Event-album placement (capability `event-album`); the `denormalizeAssetId` mapping is shared here. */
    val albumCoordinator: AlbumCoordinator,
    /** The attestation bearer token, read per request. Required: `{ null }` must be stated, not inherited. */
    val token: suspend () -> String?,
    /** Completion-notify hook (capability `upload-completion-notify`): required, no default. */
    val onBatchUploaded: suspend (eventId: String) -> Unit,
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
    ports.crashReporting.start()
    val ledger = LedgerWriter(ports.ledger)
    // Constructed lazily so the device id resolves on first in-cycle use — after the gate's probe
    // has succeeded — never at composition time, where a locked device would throw out of assembly.
    val reconciler by lazy {
        ExtensionReconciler(
            files = ports.deviceFiles,
            ledger = ports.ledger,
            marker = ports.joinedMarker,
            deviceId = ports.deviceId(),
            // Force a full re-enumeration on a re-join: the cursor survives an app upgrade, so a
            // settled cursor would scan incrementally and find nothing. The seeded ledger dedups,
            // so nothing already stored re-uploads.
            clearDiscoveryCursor = { ports.discoveryStore.clearToken() },
            log = ports.log,
        )
    }
    val manifestProducer by lazy {
        DeviceManifestProducer(
            store = ports.manifestStore,
            uploader = ports.enrollment,
            deviceId = ports.deviceId(),
        )
    }
    return UploadCycle(
        readGate = { readGate(ports) },
        // Bytes go to the device's event-independent partition (/files/devices/<deviceId>/…); the
        // eventId drives only the producer's event scope + the device-manifest write, not the byte URL.
        engineFor = { config ->
            // The engine records under this cycle's joined event (ledger provenance, `sync-ledger`);
            // like the host, the eventId arrives with the gate's config, not at composition time.
            SyncEngine(EdgeUploadRequestProvider(config.host, ports.deviceId(), ports.token), ledger, config.eventId)
        },
        ledger = ledger,
        // The read-discipline gate (capability `limited-photo-access`): the ONE shared assembly wraps
        // the platform, so every tier and the world get the same walk-vs-snapshot decision.
        platform = SelectionScopedTransfer(ports.transfer, ports.selectionScope),
        store = ports.discoveryStore,
        log = ports.log,
        reconcile = { eventId -> reconciler.reconcile(eventId) },
        // Device manifest (capability `device-manifest`) from the cycle's OWN discovery — no second
        // library enumeration. Bounding is the cycle's.
        onDiscovery = { eventId, cutoff, discovery ->
            manifestProducer.produce(
                eventId = eventId,
                startDate = cutoff, // per-device capture-date cutoff (photo-selection-policy)
                discovered = deviceManifestAssetsFromResources(discovery.resources),
                removedAssetIds = discovery.removedAssetIds.toSet(),
                fullEnumeration = discovery.fullEnumeration,
            )
        },
        suppressedAssetIds = { ports.suppression.suppressedLocalIds() },
        albumExcludedAssetIds = ports.albumExcludedAssetIds,
        onBatchUploaded = ports.onBatchUploaded,
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
 * **port-pure**: one fresh [ConfigReader.read] per cycle, the identity probe, and the host read —
 * and deliberately nothing else.
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
 *    trigger flows' membership re-read (`AppPorts.reloadConfig`, migration step 12 — before that,
 *    the app shell's `ProtectedDataGate` unlock hook), which every trigger runs before acting.
 */
private fun readGate(ports: UploadPorts): CycleGate {
    val read = ports.config.read()
    // The identity probe — an unresolvable id is "I could not look", never "no id", so it belongs
    // on the unreadable side of the roll-up. Every outcome needs the id: the reconciler and the
    // manifest producer each close over it, so even the leave-side branch touches it.
    //
    // `DeviceIdentityAbsent` joins `KeychainUnavailable` here, and the two are handled identically on
    // purpose. It means the lookup succeeded, found nothing, and this process may not mint (the upload
    // extension — capability `device-identity`). Both are "proceed with no identity", and proceeding
    // is exactly what must not happen: an invented id partitions this device's bytes away from its own
    // manifest. Anything else still propagates — a genuine fault must not be silently downgraded to a
    // skipped cycle.
    val identityFailure = runCatching { ports.deviceId() }
        .onFailure { if (it !is KeychainUnavailable && it !is DeviceIdentityAbsent) throw it }
        .exceptionOrNull()
    val idReadable = identityFailure == null
    val payload = (read as? ConfigRead.Joined)?.config
    return cycleGate(
        configReadable = read !is ConfigRead.Unavailable && idReadable,
        membership = payload?.let {
            JoinedMembership(
                eventId = it.eventId,
                contribution = Contribution.of(it.direction.includesUpload, it.minPhotoDate),
                saveToAlbum = it.saveToAlbum,
            )
        },
        host = ports.host(),
        // The forensics for a skip: the decision is made in shared code that cannot see WHY the
        // read failed, and an unreadable config is invisible on a device except through this string.
        skipDetail = "protected data unavailable (config status=" +
            "${(read as? ConfigRead.Unavailable)?.status}, deviceId readable=$idReadable" +
            // Naming WHICH identity failure occurred is the difference between "the device is locked,
            // this will pass" and "this process has no identity and may not create one", which need
            // opposite reactions from whoever reads the log.
            when (identityFailure) {
                is DeviceIdentityAbsent -> ", deviceId absent and unmintable here"
                is KeychainUnavailable -> ", deviceId unreadable (status=${identityFailure.status})"
                else -> ""
            } + ")",
    )
}
