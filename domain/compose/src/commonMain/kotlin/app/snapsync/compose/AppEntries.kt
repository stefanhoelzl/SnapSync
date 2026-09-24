package app.snapsync.compose

import app.snapsync.ports.OsReceipt
import app.snapsync.ports.PlatformEntries
import app.snapsync.ports.ReceiptDeadlines
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
    /** Touch the root's lazily assembled host, so its collectors run before a background wake's work lands. */
    val assembleHost: () -> Unit,
    /** Hand a push token to the source the registration collector observes. */
    val deliverPushToken: (hex: String) -> Unit,
    /** The background task that drains staged-but-unimported downloads. */
    val downloadBackstopTaskId: String,
    /** The background task that tops up the app-driven upload queue. */
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
 * The core's implementation of the app process's inbound port — the transcription the shell used to hold.
 *
 * Every body is what `SnapSyncRoot`'s private `LiveShell` did, moved rather than rewritten: which flow an entry runs,
 * which [OsReceipt] holds its completion and for how long, and which handler a background task or transfer channel
 * belongs to. Here it is covered (`PlatformEntriesContract`, bound over the world on JVM and the simulator); in the
 * shell it was not, because the shell is untested by rule and a forwarding that names the wrong collaborator decides
 * nothing any gate can see.
 *
 * Nothing here decides upload behaviour: every upload-driving entry delegates to the app's uploader unconditionally,
 * and its cycle decides at its own entry gate (capability `ios-app-shell`, "OS entry points delegate upload
 * triggers to the app's uploader"). The two comparisons below route by the identifier the operating system
 * delivered; they choose a handler, not whether work happens.
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

    override fun onForeground() = log.invocation(ports.logScope, "onForeground", params = foregroundParams()) {
        hooks.markActive()
        // Launched, because the flow is `suspend` (law "A trigger flow never outlives its own run"). The entry line
        // above reports the dispatch; the flow's own lines report its work.
        scope.launch {
            hooks.assembleHost()
            app.foregroundFlow.run()
        }
        Unit
    }

    override fun onBackground() = log.invocation(ports.logScope, "onBackground") {
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
            // The host first, so the download stack is assembled on a background launch.
            hooks.assembleHost()
            scope.launch {
                OsReceipt(
                    entryPoint = "onSilentPush",
                    deadline = ReceiptDeadlines.SILENT_PUSH,
                    release = completion,
                ).heldFor {
                    log.invocation(
                        ports.logScope,
                        "onSilentPush.run",
                        params = "protectedData=${ports.protectedStorage.readable()}",
                    ) {
                        app.silentPushFlow.run(payload)
                    }
                }
            }
            Unit
        }

    override fun onBackgroundTask(identifier: String, completion: () -> Unit) =
        log.invocation(ports.logScope, "onBackgroundTask", params = "identifier=$identifier") {
            when (identifier) {
                hooks.downloadBackstopTaskId -> runDownloadBackstop(completion)
                hooks.uploadHeartbeatTaskId -> runUploadHeartbeat(completion)
                // Registered in the shell but unknown here: complete it, and say so — a task held forever costs
                // the app its future background time.
                else -> {
                    log.w { "unknown background task '$identifier' — completed without work" }
                    completion()
                }
            }
        }

    override fun onBackgroundTransfers(channel: String, completion: () -> Unit) {
        log.invocation(ports.logScope, "onBackgroundTransfers", params = "channel=$channel") {
            // Routed synchronously: the handler must be adopted before its session can report its events drained.
            when (channel) {
                hooks.uploadTransferChannel -> ports.appDrivenUpload().onBackgroundTransfers(completion)
                else -> app.downloadJobs.adoptBackgroundEvents(completion)
            }
        }
        // The protected-storage state for this wake (capability `ios-app-shell`), recorded one dispatch later: the
        // read may have to hop threads, and the routing above must not wait for it.
        scope.launch {
            val readable = ports.protectedStorage.readable()
            log.i { "onBackgroundTransfers(channel=$channel): protectedData=$readable" }
        }
    }

    /** The download import-tail backstop (capability `photo-download`, 5.4); re-queued however it ends. */
    private fun runDownloadBackstop(completion: () -> Unit) {
        scope.launch {
            try {
                OsReceipt(
                    entryPoint = "runDownloadBackstop",
                    deadline = ReceiptDeadlines.BACKGROUND_TASK,
                    release = completion,
                ).heldFor {
                    log.invocation(
                        ports.logScope,
                        "runDownloadBackstop.run",
                        params = "protectedData=${ports.protectedStorage.readable()}",
                    ) {
                        app.downloadBackstopFlow.run()
                    }
                }
            } finally {
                ports.backstopScheduler.scheduleNext()
            }
        }
    }

    /** The app-driven uploader's heartbeat. */
    private fun runUploadHeartbeat(completion: () -> Unit) {
        scope.launch {
            OsReceipt(
                entryPoint = "runUploadHeartbeat",
                deadline = ReceiptDeadlines.BACKGROUND_TASK,
                release = completion,
            ).heldFor { ports.appDrivenUpload().onBackgroundTask() }
        }
    }

    /** The `onForeground` invocation params: the app uploader's admission and the registration fact. */
    private fun foregroundParams(): String =
        "app=${app.appUploadAdmission().name} extensionRegistrable=${app.extensionRegistrableNow()}" +
            " osSupported=${ports.osSupportsOsDrivenUpload}"

    private companion object {
        /** How much of a push token the entry line shows — enough to tell two apart, not the credential. */
        const val TOKEN_PREFIX = 12
    }
}
