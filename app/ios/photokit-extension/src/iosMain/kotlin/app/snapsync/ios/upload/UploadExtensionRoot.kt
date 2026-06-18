package app.snapsync.ios.upload

import app.snapsync.engine.LedgerWriter
import app.snapsync.engine.SyncEngine
import app.snapsync.engine.iosLedgerBackend
import co.touchlab.kermit.Logger
import kotlinx.coroutines.runBlocking

/**
 * The extension process's composition root — the single site that assembles the App-Group ledger
 * writer, the engine, the dummy provider, the PhotoKit platform adapter, and the [UploadCycle]. The
 * Swift `@main` principal class calls [process] from its `process()` callback.
 *
 * Swapping [DummyUploadRequestProvider] for the real `S3UploadRequestProvider` here is the entire
 * step from this bring-up slice to real uploads.
 */
object UploadExtensionRoot {

    private val log = Logger.withTag("UploadExtension")

    private val cycle: UploadCycle by lazy {
        val engine = SyncEngine(DummyUploadRequestProvider(log), LedgerWriter(iosLedgerBackend()))
        val platform = IosUploadJobPlatform(DiscoveryStore(), log)
        UploadCycle(engine, platform, log)
    }

    /**
     * Run one discovery/drain cycle. Returns `true` when it completed (the Swift shell maps this to
     * a terminal `PHBackgroundResourceUploadProcessingResult`). Blocks the extension's worker until
     * done — appropriate for the synchronous `process()` contract.
     */
    fun process(): Boolean = runBlocking {
        runCatching { cycle.run() }
            .onFailure { log.e(it) { "process cycle failed" } }
            .isSuccess
    }
}
