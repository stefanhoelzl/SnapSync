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
    private val platform: IosUploadJobPlatform by lazy { IosUploadJobPlatform(DiscoveryStore(), log) }
    private val configSource: KeychainConfigStore by lazy { KeychainConfigStore() }

    /**
     * Run one discovery/drain cycle. Returns `true` when it completed (the Swift shell maps this to
     * a terminal `PHBackgroundResourceUploadProcessingResult`). Blocks the extension's worker until
     * done — appropriate for the synchronous `process()` contract.
     */
    fun process(): Boolean = runBlocking {
        val payload = configSource.config.value
        val host = uploadHostFromBundle()
        val config = buildS3Config(payload, host)
        if (config == null) {
            // Setup not done (no payload) or a missing baked host — nothing to upload. A clean
            // no-op, never a failure; the run re-tries once config is present.
            log.i { "skipping cycle — payload present=${payload != null}, host present=${!host.isNullOrEmpty()}" }
            return@runBlocking true
        }
        val engine = SyncEngine(S3UploadRequestProvider(config), ledger)
        val cycle = UploadCycle(engine, platform, log)
        runCatching { cycle.run() }
            .onFailure { log.e(it) { "process cycle failed" } }
            .isSuccess
    }
}
