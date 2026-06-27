package app.snapsync.ios.upload

import app.snapsync.config.KeychainConfigStore
import app.snapsync.engine.LedgerBackend
import app.snapsync.engine.LedgerWriter
import app.snapsync.engine.SyncEngine
import app.snapsync.engine.iosLedgerBackend
import app.snapsync.engine.postLedgerChangedNotification
import app.snapsync.uploadurl.EdgeUploadRequestProvider
import app.snapsync.gallery.PhotoLibraryResourceEnumerator
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

    /**
     * Run one adjudicate→discover cycle and return its [CycleResult] — `COMPLETED` (drained, cursor
     * advanced), `PROCESSING` (the in-flight cap was hit; call me again, cursor un-advanced), or
     * `FAILED`. The Swift shell maps it to the system's terminal/processing result. Blocks the
     * extension's worker until done — appropriate for the synchronous `process()` contract. The
     * engine is the sole ledger writer; the cycle reads the same ledger to reconstruct lifecycle jobs.
     */
    fun process(): CycleResult = runBlocking {
        val payload = configSource.config.value
        val host = uploadHostFromBundle()
        val config = buildUploadConfig(payload?.eventId, host)
        if (config == null) {
            // Not joined yet (no event id) or a missing baked host — nothing to upload. A clean
            // no-op completion, never a failure; the run re-tries once config is present.
            log.i {
                "skipping cycle — eventId present=${payload != null}, host present=${!host.isNullOrEmpty()}"
            }
            return@runBlocking CycleResult.COMPLETED
        }
        log.i { "process: config present — running cycle" }
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
        // One coalesced cross-process ding per cycle (not per ledger put): the app re-reads the
        // ledger once for the whole batch this cycle wrote. Harmless if the cycle wrote nothing.
        postLedgerChangedNotification()
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
