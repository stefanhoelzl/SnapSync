package app.snapsync.compose

import app.snapsync.feature.upload.TailTrigger
import app.snapsync.model.pushEventId
import app.snapsync.model.runCatchingCancellable
import app.snapsync.ports.OsCompletions
import app.snapsync.ports.PlatformEntries
import app.snapsync.ports.invocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * What the core's [PlatformEntries] needs from the process it runs in and cannot name itself (spec
 * `module-architecture`, "OS entry points cross an inbound port").
 *
 * The hooks call back **into** this process — the presentation container, the root's lazily assembled host, the
 * push-token source the root holds — never out of it, so they are coordination rather than I/O and function types
 * are the honest shape. They are not [AppPorts] fields because the host is built *from* the [AppCore] these entries
 * belong to: the entries can only exist after it.
 *
 * The identifiers are the adapters' own constants, handed in as data so the routing below compares strings and no
 * platform constant enters `:domain`.
 */
class EntryHooks(
    /** Record that the app became active — the root's scene rule reads it (capability `ios-app-shell`). */
    val markActive: () -> Unit,
    /** The container's link intent: decodes, and opens the join gate or flashes the invalid-link error. */
    val openUrl: (url: String) -> Unit,
    /**
     * Touch the root's lazily assembled host, so its collectors run before a foreground entry's work lands. Foreground
     * only: a background wake assembles no host, so a cold background start installs no permission-grant subscription
     * (capability `ios-app-shell`, "iOS live composition root").
     */
    val assembleHost: () -> Unit,
    /** Hand a push token to the source the registration collector observes. */
    val deliverPushToken: (hex: String) -> Unit,
    /** The background task that is the app-driven uploader's heartbeat — the only background task there is. */
    val uploadHeartbeatTaskId: String,
    /** The transfer channel whose handbacks belong to the app's uploader; every other channel is the downloads'. */
    val uploadTransferChannel: String,
)

/**
 * The core's implementation of the app process's inbound port (spec `module-architecture`, "OS entry points cross
 * an inbound port"). The composition root implements [PlatformEntries] by delegating to this, so the forwarding from
 * each operating-system callback is written by the compiler.
 *
 * [core] is a provider rather than the graph itself so the root can delegate from its own initializer without
 * assembling anything: each entry resolves the graph when the operating system first calls it, exactly as the
 * root's lazies always have.
 */
fun platformEntries(core: () -> AppCore, hooks: EntryHooks): PlatformEntries = AppEntries(core, hooks)

/**
 * The core's implementation of the app process's inbound port: **each wake does its own work, then hands the rest to
 * the one opportunistic tail** (capability `ios-app-shell`; decision record `changes/own-work-per-wake`, D1, D3, D5).
 *
 * | wake | own work, after the prelude | handler released | then |
 * |---|---|---|---|
 * | silent push | union read, plan, enqueue (`SilentPush`) | after that own work | the tail, active event only |
 * | download-session relaunch | staging the delivered files | at the drain report | the tail |
 * | upload-session relaunch | recording the terminals | at the drain report | the tail |
 * | heartbeat `BGTask` | none — the task is a grant of time | after its tail | — |
 * | foreground | the `Foreground` flow | (no handler) | the tail |
 *
 * The prelude is the membership re-read and the attestation refresh. A push and a transfer wake take the process's
 * **background time** no later than their handler is handed over ([Wake]), and hold it across the own work, the
 * handler's release and the tail; its expiry — Apple's only "time is up" for those wakes — stops the tail, releases
 * the handler and ends the hold at once. A `BGTask` holds its own completion until its tail ends; its forwarded
 * expiry ([onBackgroundTaskTimeUp]) stops the tail and completes the task at once. No clock of the app's own bounds
 * anything here.
 *
 * **The tail is requested here, after a flow returns — never from inside one** (spec `module-architecture`, "A
 * trigger flow never outlives its own run"). Nothing here decides upload behaviour: the tail's units decide at the
 * upload cycle's own entry gate. The two comparisons below route by the identifier the operating system delivered;
 * they choose a handler, not whether work happens. `PlatformEntriesContract` specifies all of it, bound over the
 * world on the JVM and in the simulator.
 */
internal class AppEntries(
    private val core: () -> AppCore,
    private val hooks: EntryHooks,
) : PlatformEntries {

    // Resolved per call, never at construction: the root delegates to this from its own initializer, and a cold
    // background launch must assemble no more of the graph than its wake needs (the `AppCore` contract).
    private val app: AppCore get() = core()
    private val ports: AppPorts get() = app.ports
    private val scope: CoroutineScope get() = app.scope
    private val log get() = ports.log

    /** The operating system's expiry signal for each background task this core is running (see [TaskExpiries]). */
    private val taskExpiries = TaskExpiries()

    private fun wake(label: String) = Wake(label, ports.backgroundTime, app.tail.runner, log)

    override fun onForeground() = log.invocation(ports.logScope, "onForeground", params = app.foregroundParams()) {
        hooks.markActive()
        app.tail.foregrounded(true)
        // Held like a background wake's, so a tail the member leaves behind by switching away stops on Apple's signal
        // rather than being frozen mid-unit.
        val wake = wake("onForeground")
        // Launched, because the flow is `suspend` (law "A trigger flow never outlives its own run"). The entry line
        // above reports the dispatch; the flow's own lines report its work.
        scope.launch {
            hooks.assembleHost()
            runCatchingCancellable { app.foregroundFlow.run() }
                .onFailure { log.w(it) { "the foreground flow failed; its tail still runs" } }
            wake.thenTail(TailTrigger.FOREGROUND)
        }
        Unit
    }

    override fun onBackground() = log.invocation(ports.logScope, "onBackground") {
        app.tail.foregrounded(false)
        scope.launch {
            app.backgroundFlow.run()
            log.i { "=== app entering background ===" }
        }
        Unit
    }

    override fun onOpenUrl(url: String) =
        log.invocation(ports.logScope, "onOpenUrl", params = "url=$url") { hooks.openUrl(url) }

    override fun onPushToken(hex: String) =
        // No host assembly: the registration collector is installed as the graph is composed — which reaching
        // `ports` above has done — so a token delivered in a background wake installs no permission collector.
        log.invocation(ports.logScope, "onPushToken", params = "hex=${hex.take(TOKEN_PREFIX)}…") {
            hooks.deliverPushToken(hex)
        }

    override fun onSilentPush(payload: Map<Any?, *>, completion: () -> Unit) =
        log.invocation(ports.logScope, "onSilentPush") {
            // No host assembly (decision record `changes/own-work-per-wake`): everything the push's own work needs is
            // built by the composed graph, and a cold background start installs no permission-grant subscription.
            val wake = wake("onSilentPush")
            val completions = OsCompletions("onSilentPush", log = log)
            wake.guard(completions.adopt(completion))
            scope.launch {
                completions.releaseAfter {
                    log.invocation(
                        ports.logScope,
                        "onSilentPush.run",
                        params = "protectedData=${ports.protectedStorage.readable()}",
                    ) {
                        app.silentPushFlow.run(payload)
                    }
                }
                // Only for the active event, read from the membership the flow just re-read: a push for another
                // event, a left one, none, or an unreadable membership wakes no tail (capability `push-registration`).
                val joinsTail = pushEventId(payload)?.let(app.pushTailGuard::joinsTail) == true
                if (joinsTail) wake.thenTail(TailTrigger.SILENT_PUSH) else wake.end()
            }
            Unit
        }

    override fun onBackgroundTask(identifier: String, completion: () -> Unit) =
        log.invocation(ports.logScope, "onBackgroundTask", params = "identifier=$identifier") {
            when (identifier) {
                hooks.uploadHeartbeatTaskId -> runHeartbeat(identifier, completion)
                // Registered in the shell but unknown here: complete it, and say so — a task held forever costs the
                // app its future background time.
                else -> {
                    log.w { "unknown background task '$identifier' — completed without work" }
                    completion()
                }
            }
        }

    override fun onBackgroundTaskTimeUp(identifier: String) =
        log.invocation(ports.logScope, "onBackgroundTaskTimeUp", params = "identifier=$identifier") {
            // Answered here and nowhere else: the shell forwards the OS's expiration handler and completes nothing
            // (capability `ios-app-shell`, "Background tasks are forwarded by the identifier the OS delivered").
            if (!taskExpiries.expire(identifier)) {
                log.w { "time is up for background task '$identifier', which the core is not running — ignored" }
            }
        }

    override fun onBackgroundTransfers(channel: String, completion: () -> Unit) {
        log.invocation(ports.logScope, "onBackgroundTransfers", params = "channel=$channel") {
            // The background time first: no later than the handover, so the wait for the session's drain report is
            // covered too, and a report that never comes ends in Apple's expiry rather than a handler held forever.
            val wake = wake("onBackgroundTransfers($channel)")
            // Routed synchronously: the handler must be adopted before its session can report its events drained.
            val (handover, trigger) = when (channel) {
                hooks.uploadTransferChannel -> app.tail.uploadCompletions.adopt(completion).also {
                    ports.appDrivenUpload().reattach()
                } to TailTrigger.UPLOAD_SESSION_EVENTS
                else -> app.downloadJobs.adoptBackgroundEvents(completion) to TailTrigger.DOWNLOAD_SESSION_EVENTS
            }
            wake.guard(handover)
            scope.launch {
                // The protected-storage state for this wake (capability `ios-app-shell`), recorded one dispatch
                // later: the read may have to hop threads, and the routing above must not wait for it.
                log.i { "onBackgroundTransfers(channel=$channel): protectedData=${ports.protectedStorage.readable()}" }
                app.prelude()
                // The wake's own work is the session's: its deliveries, recorded as they arrive, and its drain report,
                // which releases the handler. The rest is the tail's.
                handover.awaitRelease()
                wake.thenTail(trigger)
            }
        }
    }

    /**
     * The upload heartbeat `BGTask`: it has no own work beyond the prelude — the task is a grant of time, and its work
     * is the tail — so its completion is held until that tail ends, or released at once on its forwarded expiry,
     * which also stops the tail. The heartbeat re-arm is the tail runner's.
     */
    private fun runHeartbeat(identifier: String, completion: () -> Unit) {
        val completions = OsCompletions("runUploadHeartbeat", log = log)
        val handover = completions.adopt(completion)
        // Opened before the launch, so an expiry the OS fires before the coroutine first runs is not lost.
        val open = taskExpiries.open(identifier) {
            val reason = "BGTask '$identifier' expirationHandler (runUploadHeartbeat)"
            app.tail.runner.stop(reason)
            handover.releaseOnExpiry(reason)
        }
        scope.launch {
            try {
                completions.releaseAfter {
                    log.invocation(ports.logScope, "runUploadHeartbeat") {
                        app.prelude()
                        // A task whose time is already up requests no tail: a stop while none runs is a no-op. A tail
                        // that fails is contained — the task is still completed, and the next wake retries.
                        if (!handover.isReleased) {
                            runCatchingCancellable { app.tail.runner.request(TailTrigger.HEARTBEAT) }
                                .onFailure { log.w(it) { "runUploadHeartbeat: its tail failed" } }
                        }
                    }
                }
            } finally {
                taskExpiries.close(identifier, open)
            }
        }
    }

    private companion object {
        /** How much of a push token the entry line shows — enough to tell two apart, not the credential. */
        const val TOKEN_PREFIX = 12
    }
}

/** The `onForeground` invocation params: the app uploader's admission and the registration fact. */
private fun AppCore.foregroundParams(): String =
    "app=${appUploadAdmission().name} extensionRegistrable=${extensionRegistrableNow()}" +
        " osSupported=${ports.osSupportsOsDrivenUpload}"

/**
 * The shared prelude of a wake with no flow of its own: re-read the membership (cross-process writes and a
 * pre-first-unlock seed never notify this process's StateFlow), then renew a stale attestation token. Each is
 * best-effort — a failed prelude must not rob the wake of its tail, whose units read the membership themselves.
 */
private suspend fun AppCore.prelude() {
    runCatchingCancellable { ports.configRefresh.refresh() }
        .onFailure { ports.log.w(it) { "prelude: the membership re-read failed" } }
    runCatchingCancellable { attestation.refresh() }
        .onFailure { ports.log.w(it) { "prelude: the attestation refresh failed" } }
}
