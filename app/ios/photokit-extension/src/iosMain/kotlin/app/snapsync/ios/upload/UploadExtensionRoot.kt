package app.snapsync.ios.upload

import app.snapsync.config.KeychainConfigStore
import app.snapsync.engine.LedgerWriter
import app.snapsync.engine.SyncEngine
import app.snapsync.engine.iosLedgerBackend
import app.snapsync.s3.S3UploadRequestProvider
import co.touchlab.kermit.Logger
import kotlinx.coroutines.runBlocking

/**
 * The extension process's composition root — the single site that assembles the App-Group ledger
 * writer, the engine, the real S3 upload provider, the PhotoKit platform adapter, and the
 * [UploadCycle]. The Swift `@main` principal class calls [process] from its `process()` callback.
 *
 * Config is sourced fresh each cycle: the runtime payload (bucket/region/creds) from the shared
 * Keychain ([KeychainConfigStore]) combined with the compile-time upload host
 * ([uploadHostFromBundle], `BackgroundUploadURLBase`) into the provider's [S3Config]. When no config
 * has been provisioned yet (the extension woke before setup), the cycle is skipped as a clean
 * success — no job, no ledger write, no crash.
 *
 * The ledger writer and platform are process-lifetime singletons (the extension is the single
 * `LedgerWriter`); only the engine, which depends on config, is built per cycle.
 */
object UploadExtensionRoot {

    init {
        // Route kermit through a public NSLog writer so the extension's logs show in
        // `idevicesyslog` un-redacted (the default os_log path drops `.info` and redacts dynamic
        // content as `<private>`).
        Logger.setLogWriters(PublicNSLogWriter())
    }

    private val log = Logger.withTag("UploadExtension")

    private val ledger: LedgerWriter by lazy { LedgerWriter(iosLedgerBackend()) }
    private val platform: IosUploadJobPlatform by lazy { IosUploadJobPlatform(log) }
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
        val config = buildS3Config(payload, host)
        if (config == null) {
            // Setup not done (no payload) or a missing baked host — nothing to upload. A clean
            // no-op completion, never a failure; the run re-tries once config is present.
            log.i { "skipping cycle — payload present=${payload != null}, host present=${!host.isNullOrEmpty()}" }
            return@runBlocking CycleResult.COMPLETED
        }
        log.i { "process: config present — running cycle" }
        val engine = SyncEngine(S3UploadRequestProvider(config), ledger)
        val cycle = UploadCycle(engine, ledger, platform, discoveryStore, log)
        runCatching { cycle.run() }
            .onSuccess { log.i { "process: cycle finished — $it" } }
            .getOrElse {
                log.e(it) { "process cycle failed" }
                CycleResult.FAILED
            }
    }
}
