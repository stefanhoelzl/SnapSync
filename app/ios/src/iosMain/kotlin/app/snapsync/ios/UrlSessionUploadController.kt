package app.snapsync.ios

import app.snapsync.config.KeychainConfigStore
import app.snapsync.engine.LEDGER_APP_GROUP
import app.snapsync.engine.LedgerBackend
import app.snapsync.engine.LedgerWriter
import app.snapsync.engine.SyncEngine
import app.snapsync.gallery.PhotoLibraryResourceEnumerator
import app.snapsync.ios.discovery.IosDiscovery
import app.snapsync.ios.discovery.IosDiscoveryStore
import app.snapsync.ios.urlsession.IosBackgroundScheduler
import app.snapsync.ios.urlsession.IosUrlSessionUploadPlatform
import app.snapsync.upload.BackgroundUploadPump
import app.snapsync.upload.CycleResult
import app.snapsync.upload.UploadCycle
import app.snapsync.upload.buildUploadConfig
import app.snapsync.uploadurl.EdgeUploadRequestProvider
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The app-driven (iOS 18–26.0) upload tier's composition root — the app-process analogue of
 * `UploadExtensionRoot`, but driving the shared `:capability:upload` `UploadCycle` over a background
 * `URLSession` instead of the PhotoKit OS-job queue. Assembled by [SnapSyncRoot] when
 * `backgroundUploadSupported()` is false (or the dev force flag is set). On this tier the **app is the
 * single `LedgerWriter`** — there is no extension process, so the cross-process single-writer concern
 * does not apply (`sync-ledger`: the record-writer's process placement is a platform binding).
 *
 * [BackgroundUploadPump] is the in-app reimplementation of the OS scheduler; the four triggers are
 * forwarded from [SnapSyncRoot] (foreground, `BGProcessingTask`, background-session relaunch,
 * per-completion). Config is re-read each cycle so a newly-joined event takes effect.
 */
class UrlSessionUploadController(
    private val scope: CoroutineScope,
    private val ledgerBackend: LedgerBackend,
    private val configSource: KeychainConfigStore,
    private val deviceId: String,
    private val host: String,
    private val log: Logger,
    // False on the dev/test-forced (simulator) path — the sim can't run a background NSURLSession.
    private val useBackgroundSession: Boolean = true,
) {
    companion object {
        const val SESSION_IDENTIFIER = "app.snapsync.upload.session"
        const val HEARTBEAT_TASK_IDENTIFIER = "app.snapsync.upload.heartbeat"
    }

    private val ledger = LedgerWriter(ledgerBackend)
    private val discovery = IosDiscovery(log, PhotoLibraryResourceEnumerator())
    private val discoveryStore = IosDiscoveryStore()
    private val scheduler = IosBackgroundScheduler(log, HEARTBEAT_TASK_IDENTIFIER)

    private val platform = IosUrlSessionUploadPlatform(
        log = log,
        discovery = discovery,
        appGroup = LEDGER_APP_GROUP,
        sessionIdentifier = SESSION_IDENTIFIER,
        useBackgroundSession = useBackgroundSession,
        // Precise stranded-row reconciliation: the ledger's current REQUESTED keys.
        pendingKeys = { ledgerBackend.pendingResources().map { it.key }.toSet() },
        // A slot just freed → top up (single-flight in the pump serialises it).
        onTerminal = { scope.launch { pump.onUploadCompleted() } },
    )

    private val pump = BackgroundUploadPump(
        runCycle = { runCycle() },
        scheduler = scheduler,
        log = log,
    )

    // The OS completion handler from `handleEventsForBackgroundURLSession`, held until the session
    // reports all events delivered (then invoked exactly once).
    private var backgroundEventsCompletion: (() -> Unit)? = null

    init {
        platform.onBackgroundEventsFinished = {
            backgroundEventsCompletion?.invoke()
            backgroundEventsCompletion = null
            scope.launch { pump.onSessionEvents() }
        }
    }

    /** One upload cycle: fresh config each time, then the shared [UploadCycle] over the URLSession platform. */
    private suspend fun runCycle(): CycleResult {
        log.i { "url-session runCycle: enter" }
        configSource.reload()
        val config = buildUploadConfig(configSource.config.value?.eventId, host) ?: run {
            log.i { "url-session cycle skipped — no joined event / host (eventId=${configSource.config.value?.eventId}, host=$host)" }
            return CycleResult.COMPLETED
        }
        log.i { "url-session runCycle: config ok (host=${config.host}) — invoking UploadCycle" }
        val engine = SyncEngine(EdgeUploadRequestProvider(config.host, deviceId), ledger)
        val result = UploadCycle(engine, ledger, platform, discoveryStore, log).run()
        log.i { "url-session runCycle: UploadCycle returned $result" }
        return result
    }

    // ---- lifecycle / triggers (forwarded by SnapSyncRoot) ----

    /** On a full photo grant / app start: sweep orphaned staging, run a cycle, arm the heartbeat. */
    fun start() {
        log.i { "url-session controller start()" }
        scope.launch {
            runCatching { platform.sweepStaging() }.onFailure { log.w(it) { "sweepStaging failed" } }
            pump.onForeground()
        }
    }

    /** Foreground entry — pump a cycle (completions drive the rest while open). */
    fun onForeground() {
        log.i { "url-session controller onForeground()" }
        scope.launch { pump.onForeground() }
    }

    /** The `BGProcessingTask` heartbeat handler fired — top up and re-arm. Call [done] when finished. */
    fun onBackgroundTask(done: () -> Unit) {
        scope.launch {
            try {
                pump.onBackgroundTask()
            } finally {
                done()
            }
        }
    }

    /** The OS relaunched us to finish background transfers — hold the completion, let the session drain. */
    fun onBackgroundSessionEvents(completion: () -> Unit) {
        backgroundEventsCompletion = completion
        // Touch the session so it re-attaches and begins delivering its completion callbacks (which
        // fire onBackgroundEventsFinished, invoking `completion` + pumping onSessionEvents).
        platform.reattach()
    }

    /** Disable (access revoked): cancel in-flight transfers + the scheduled heartbeat. */
    fun disable() {
        platform.cancelAll()
        scheduler.cancel()
    }

    /** Leave: cancel everything, then wipe the local ledger + discovery cursor. */
    fun leave() {
        disable()
        scope.launch {
            ledgerBackend.clear()
            discoveryStore.clearToken()
        }
    }
}
