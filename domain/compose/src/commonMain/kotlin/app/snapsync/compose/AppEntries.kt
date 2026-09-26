package app.snapsync.compose

import app.snapsync.ports.EntryContext
import app.snapsync.feature.upload.TailTrigger
import app.snapsync.model.pushEventId
import app.snapsync.model.runCatchingCancellable
import app.snapsync.services.wake.OsCompletions
import app.snapsync.ports.PlatformEntries
import app.snapsync.ports.invocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * What the core's [PlatformEntries] needs from the process it runs in and cannot name itself (spec
 * `docs/architecture.md`, "OS entry points cross an inbound port").
 *
 * The hooks call back **into** this process — the presentation container, the root's lazily assembled host, the
 * push-token source the root holds — never out of it, so they are coordination rather than I/O and function types
 * are the honest shape. They are not [AppPorts] fields because the host is built *from* the [AppCore] these entries
 * belong to: the entries can only exist after it.
 *
 * The identifier is the upload adapter's own constant, handed in as data so the routing below compares strings and no
 * platform constant enters `:domain`.
 */
class EntryHooks(
    /** Record that the app became active — the root's scene rule reads it (capability `sync-status`). */
    val markActive: () -> Unit,
    /** The container's link intent: decodes, and opens the join gate or flashes the invalid-link error. */
    val openUrl: (url: String) -> Unit,
    /**
     * Touch the root's lazily assembled host, so its collectors run before a foreground entry's work lands. Foreground
     * only: a background wake assembles no host, so a cold background start installs no permission-grant subscription
     * (capability `sync-status`, "iOS live composition root").
     */
    val assembleHost: () -> Unit,
    /** Hand a push token to the source the registration collector observes. */
    val deliverPushToken: (hex: String) -> Unit,
    /** The transfer channel whose handbacks belong to the app's uploader; every other channel is the downloads'. */
    val uploadTransferChannel: String,
)

/**
 * The core's implementation of the app process's inbound port (`docs/architecture.md`, "OS entry points cross
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
 * the one opportunistic tail** (capability `sync-status`; decision record `changes/own-work-per-wake`, D1, D3, D5).
 *
 * | wake | own work, after the prelude | handler released | then |
 * |---|---|---|---|
 * | silent push | union read, plan, enqueue (`SilentPush`) | after that own work | the tail, active event only |
 * | download-session relaunch | staging the delivered files | at the drain report | the tail |
 * | upload-session relaunch | recording the terminals | at the drain report | the tail |
 * | foreground | the `Foreground` flow | (no handler) | the tail |
 *
 * The prelude is the membership re-read and the attestation refresh. A push and a transfer wake take the process's
 * **background time** no later than their handler is handed over ([WakeHold]), and hold it across the own work, the
 * handler's release and the tail; its expiry — Apple's only "time is up" for those wakes — stops the tail, releases
 * the handler and ends the hold at once. The heartbeat is not an entry here: it arrives through the `Wake` event port,
 * whose handler ([wakeHandlers]) holds its completion until its tail ends. No clock of the app's own bounds anything
 * here.
 *
 * **The tail is requested here, after a flow returns — never from inside one** (`docs/architecture.md`, "A
 * trigger flow never outlives its own run"). Nothing here decides upload behaviour: the tail's units decide at the
 * upload cycle's own entry gate. The one comparison below routes by the identifier the operating system delivered;
 * it chooses a handler, not whether work happens. `PlatformEntriesContract` specifies all of it, bound over the
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
    private val entry: EntryContext get() = app.process.entryContext
    private val scope: CoroutineScope get() = app.scope
    private val log get() = ports.log

    private fun wake(label: String) = app.tail.hold(label)

    override fun onForeground() = log.invocation(entry, "onForeground", params = app.foregroundParams()) {
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

    override fun onBackground() = log.invocation(entry, "onBackground") {
        app.tail.foregrounded(false)
        scope.launch {
            app.backgroundFlow.run()
            log.i { "=== app entering background ===" }
        }
        Unit
    }

    override fun onOpenUrl(url: String) =
        log.invocation(entry, "onOpenUrl", params = "url=$url") { hooks.openUrl(url) }

    override fun onPushToken(hex: String) =
        // No host assembly: the registration collector is installed as the graph is composed — which reaching
        // `ports` above has done — so a token delivered in a background wake installs no permission collector.
        log.invocation(entry, "onPushToken", params = "hex=${hex.take(TOKEN_PREFIX)}…") {
            hooks.deliverPushToken(hex)
        }

    override fun onSilentPush(payload: Map<Any?, *>, completion: () -> Unit) =
        log.invocation(entry, "onSilentPush") {
            // No host assembly (decision record `changes/own-work-per-wake`): everything the push's own work needs is
            // built by the composed graph, and a cold background start installs no permission-grant subscription.
            val wake = wake("onSilentPush")
            val completions = OsCompletions("onSilentPush", log = log)
            wake.guard(completions.adopt(completionOf(completion)))
            scope.launch {
                completions.releaseAfter {
                    log.invocation(
                        entry,
                        "onSilentPush.run",
                        params = "protectedData=${ports.processInfo.protectedDataAvailable()}",
                    ) {
                        app.silentPushFlow.run(payload)
                    }
                }
                // Only for the active event, read from the membership the flow just re-read: a push for another
                // event, a left one, none, or an unreadable membership wakes no tail (capability `receiving-photos`).
                val joinsTail = pushEventId(payload)?.let(app.pushTailGuard::joinsTail) == true
                if (joinsTail) wake.thenTail(TailTrigger.SILENT_PUSH) else wake.end()
            }
            Unit
        }

    override fun onBackgroundTransfers(channel: String, completion: () -> Unit) {
        log.invocation(entry, "onBackgroundTransfers", params = "channel=$channel") {
            // The background time first: no later than the handover, so the wait for the session's drain report is
            // covered too, and a report that never comes ends in Apple's expiry rather than a handler held forever.
            val wake = wake("onBackgroundTransfers($channel)")
            // Routed synchronously: the handler must be adopted before its session can report its events drained.
            val (handover, trigger) = when (channel) {
                hooks.uploadTransferChannel -> app.tail.uploadCompletions.adopt(completionOf(completion)).also {
                    ports.appDrivenUpload().reattach()
                } to TailTrigger.UPLOAD_SESSION_EVENTS
                else -> app.downloadJobs.adoptBackgroundEvents(completionOf(completion)) to
                    TailTrigger.DOWNLOAD_SESSION_EVENTS
            }
            wake.guard(handover)
            scope.launch {
                // The protected-storage state for this wake (capability `sync-status`), recorded one dispatch
                // later: the read may have to hop threads, and the routing above must not wait for it.
                val protectedData = ports.processInfo.protectedDataAvailable()
                log.i { "onBackgroundTransfers(channel=$channel): protectedData=$protectedData" }
                app.prelude()
                // The wake's own work is the session's: its deliveries, recorded as they arrive, and its drain report,
                // which releases the handler. The rest is the tail's.
                handover.awaitRelease()
                wake.thenTail(trigger)
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
internal suspend fun AppCore.prelude() {
    runCatchingCancellable { ports.configRefresh.refresh() }
        .onFailure { ports.log.w(it) { "prelude: the membership re-read failed" } }
    runCatchingCancellable { attestation.refresh() }
        .onFailure { ports.log.w(it) { "prelude: the attestation refresh failed" } }
}
