package app.snapsync.ios.upload

import app.snapsync.config.KeychainConfigStore
import app.snapsync.engine.LedgerBackend
import app.snapsync.engine.LedgerWriter
import app.snapsync.engine.SyncEngine
import app.snapsync.engine.iosLedgerBackend
import app.snapsync.uploadurl.EdgeUploadRequestProvider
import app.snapsync.gallery.IosManifestStore
import app.snapsync.gallery.PhotoLibraryResourceEnumerator
import app.snapsync.rejoin.ExtensionReconciler
import app.snapsync.rejoin.HttpEventFilesSource
import app.snapsync.rejoin.darwinHttpClient
import co.touchlab.kermit.Logger
import kotlinx.coroutines.runBlocking

/**
 * The extension process's composition root — the single site that assembles the App-Group ledger
 * writer, the engine, the real S3 upload provider, the PhotoKit platform adapter, and the
 * [UploadCycle]. The Swift `@main` principal class calls [process] from its `process()` callback.
 *
 * Config is sourced fresh each cycle: the runtime event id from the shared Keychain
 * ([KeychainConfigStore]) combined with the compile-time upload host ([uploadHostFromBundle],
 * `BackgroundUploadURLBase`) into the edge upload provider. When no event has been joined yet (the
 * extension woke before setup), the cycle is skipped as a clean success — no job, no ledger write,
 * no crash.
 *
 * The ledger writer and platform are process-lifetime singletons (the extension is the single
 * `LedgerWriter`); only the engine, which depends on config, is built per cycle.
 */
object UploadExtensionRoot {

    init {
        // Route kermit through a public NSLog writer AND a file writer. NSLog turns out to be
        // redacted as `<private>` on current iOS (dynamic format strings are private), so the file
        // writer (Documents/debug.log, pulled via `pymobiledevice3 apps pull`) is the reliable
        // channel for reading the extension's logs on device.
        Logger.setLogWriters(PublicNSLogWriter(), FileLogWriter())
    }

    private val log = Logger.withTag("UploadExtension")

    private val ledgerBackend: LedgerBackend by lazy { iosLedgerBackend() }
    private val ledger: LedgerWriter by lazy { LedgerWriter(ledgerBackend) }
    private val platform: IosUploadJobPlatform by lazy {
        IosUploadJobPlatform(log, PhotoLibraryResourceEnumerator())
    }
    private val discoveryStore: IosDiscoveryStore by lazy { IosDiscoveryStore() }
    private val configSource: KeychainConfigStore by lazy { KeychainConfigStore() }

    // Re-join reconciliation (capability `event-rejoin-reconciliation`), now extension-owned. Seeds
    // already-stored photos as COMPLETED before the producer runs so they are not re-uploaded; gated by
    // a persisted `joinedEventId` marker so a settled join performs no fetch. Fetches the event's
    // complete-asset listing over the Darwin HTTPS client; the host is the same compile-time
    // `BackgroundUploadURLBase` baked into the extension bundle.
    private val reconciler: ExtensionReconciler by lazy {
        ExtensionReconciler(
            files = HttpEventFilesSource(darwinHttpClient(), uploadHostFromBundle() ?: ""),
            ledger = ledgerBackend,
            marker = IosJoinedEventMarker(),
            clearDiscoveryCursor = { discoveryStore.clearToken() },
            log = log,
        )
    }

    // The per-asset manifest side channel (capability `asset-manifest`): synthesizes + enqueues each
    // asset's manifest on a background URLSession, independent of the engine/ledger. Process-lifetime
    // singletons (the session must outlive a single cycle to be re-adoptable by the app).
    private val manifestStore: IosManifestStore by lazy { IosManifestStore() }
    private val manifestSession: ManifestUploadSession by lazy { ManifestUploadSession() }
    private val manifestProducer: IosManifestProducer by lazy {
        IosManifestProducer(manifestStore, manifestSession, log)
    }

    /**
     * Run one adjudicate→discover cycle and return its [CycleResult] — `COMPLETED` (drained, cursor
     * advanced), `PROCESSING` (the in-flight cap was hit; call me again, cursor un-advanced), or
     * `FAILED`. The Swift shell maps it to the system's terminal/processing result. Blocks the
     * extension's worker until done — appropriate for the synchronous `process()` contract. The
     * engine is the sole ledger writer; the cycle reads the same ledger to reconstruct lifecycle jobs.
     */
    fun process(): CycleResult = runBlocking {
        // Re-read the Keychain each cycle: the extension process outlives a single invocation, and a
        // new event joined by the app (another process) does not notify this StateFlow — without the
        // refresh the extension keeps uploading to the event it read at construction (a stale,
        // previously-joined event). This is what makes "config is sourced fresh each cycle" true.
        configSource.reload()
        val payload = configSource.config.value
        val host = uploadHostFromBundle()

        // Re-join reconciliation runs HERE, before any upload job is created (capability
        // `event-rejoin-reconciliation`): on a (re)join it seeds already-stored photos as COMPLETED so
        // the producer does not re-upload them, resets the private ledger on an event switch/leave, and
        // — if the listing fetch fails — defers this cycle (uploads nothing, leaves the marker unset to
        // retry). A settled join (marker matches the configured event) does no fetch and returns true.
        val mayUpload = runCatching { reconciler.reconcile(payload?.eventId) }
            .getOrElse { log.e(it) { "reconcile failed — deferring uploads this cycle" }; false }

        val config = buildUploadConfig(payload?.eventId, host)
        if (config == null || !mayUpload) {
            // Not joined yet (no event id), a missing baked host, a leave reset, or a deferred reconcile
            // — nothing to upload. A clean no-op completion, never a failure; the run re-tries next cycle.
            log.i {
                "skipping cycle — eventId present=${payload != null}, " +
                    "host present=${!host.isNullOrEmpty()}, mayUpload=$mayUpload"
            }
            return@runBlocking CycleResult.COMPLETED
        }
        log.i { "process: config present and reconciled — running cycle" }
        // Side channel: ensure each asset's manifest is generated + enqueued on the background
        // URLSession. Independent of the engine/ledger and best-effort — a manifest failure must never
        // fail the upload cycle (completeness is read at the list endpoint, not from the manifest job).
        runCatching { manifestProducer.ensureManifests(config.eventId, config.host) }
            .onFailure { log.w(it) { "manifest side channel failed this cycle" } }
        val engine = SyncEngine(
            EdgeUploadRequestProvider(config.host, config.eventId),
            ledger,
        )
        val cycle = UploadCycle(engine, ledger, platform, discoveryStore, log)
        val result = runCatching { cycle.run() }
            .onSuccess { log.i { "process: cycle finished — $it" } }
            .getOrElse {
                log.e(it) { "process cycle failed" }
                CycleResult.FAILED
            }
        // The ledger is the extension's private upload memory — the app no longer watches it across
        // processes (status derives from storage truth), so there is no cross-process ding to post.
        // The OS invokes the extension lazily (on library changes), not when an upload quietly
        // finishes — so a drained cycle that returns COMPLETED leaves already-succeeded jobs
        // un-acknowledged until the next change. While the ledger still has pending (in-flight)
        // rows, return PROCESSING to request another invocation so their completions are recorded
        // promptly; report COMPLETED only once everything is backed up (pending == 0), so the system
        // then rests. (The OS throttles re-invocation, so this polls at its cadence, not in a loop.)
        if (result == CycleResult.COMPLETED) {
            val pending = ledgerBackend.aggregates().pending
            if (pending > 0) {
                log.i { "process: $pending pending — requesting re-invocation" }
                return@runBlocking CycleResult.PROCESSING
            }
        }
        result
    }
}
