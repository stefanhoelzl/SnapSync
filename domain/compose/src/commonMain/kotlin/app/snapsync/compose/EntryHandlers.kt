package app.snapsync.compose

import app.snapsync.feature.upload.TailTrigger
import app.snapsync.model.ApnsPushToken
import app.snapsync.model.PlatformError
import app.snapsync.model.PushMessage
import app.snapsync.model.PushToken
import app.snapsync.model.pushEventId
import app.snapsync.model.runCatchingCancellable
import app.snapsync.ports.Completion
import app.snapsync.ports.DevHandlers
import app.snapsync.ports.EntryContext
import app.snapsync.ports.LifecycleHandlers
import app.snapsync.ports.PushHandlers
import app.snapsync.ports.invocation
import app.snapsync.services.wake.OsCompletions
import kotlinx.coroutines.launch

/**
 * The app's foreground and background work — the `Lifecycle` port's handlers (`docs/architecture.md`, "Events
 * arrive through `listen`"): **each wake does its own work, then hands the rest to the one opportunistic tail**
 * (capability `sync-status`; decision record `changes/own-work-per-wake`, D1, D3, D5).
 *
 * `onForeground` is **host-first**: [assembleHost] — the host zone's, touching the lazily assembled status host — runs
 * on the composition lane before the `Foreground` flow, so the host's collectors are installed before the flow's work
 * lands. Its hold on the process's background time is taken at once, so a tail the member leaves behind by switching
 * away stops on the platform's signal rather than being frozen mid-unit. It then asks the push service for the token
 * again: asking is the only way to learn a rotated one (capability `receiving-photos`, "Registration timing").
 *
 * `onBackground` assembles nothing. **The tail is requested here, after a flow returns — never from inside one**
 * (law "A trigger flow never outlives its own run").
 */
fun lifecycleHandlers(core: AppCore, assembleHost: () -> Unit): LifecycleHandlers {
    val ports = core.ports
    val entry: EntryContext = core.process.entryContext
    val log = ports.log
    return LifecycleHandlers(
        onForeground = {
            log.invocation(entry, "onForeground", params = core.foregroundParams()) {
                core.tail.foregrounded(true)
                val wake = core.tail.hold("onForeground")
                // Launched, because the flow is `suspend` (law "A trigger flow never outlives its own run"). The
                // entry line above reports the dispatch; the flow's own lines report its work.
                core.scope.launch {
                    assembleHost()
                    runCatchingCancellable { core.foregroundFlow.run() }
                        .onFailure { log.w(it) { "the foreground flow failed; its tail still runs" } }
                    wake.thenTail(TailTrigger.FOREGROUND)
                }
                ports.pushNotifications.register()
            }
        },
        onBackground = {
            log.invocation(entry, "onBackground") {
                core.tail.foregrounded(false)
                core.scope.launch {
                    core.backgroundFlow.run()
                    log.i { "=== app entering background ===" }
                }
            }
        },
    )
}

/**
 * The push service's handlers (capability `receiving-photos`). **None assembles the host**: a token or a silent push
 * arriving in a background wake installs no permission-grant subscription — everything the push's own work needs is
 * built by the composed graph.
 *
 * A token reaches the source the push registration observes, paired with this build's APNs environment there. A
 * silent push takes the process's background time no later than its completion is handed over, and holds it across
 * the own work (`SilentPush`: the download arm's union read and enqueue), the completion's release and the tail; its
 * expiry — the only "time is up" a push gets — stops the tail, releases the completion and ends the hold at once. The
 * tail runs only for the active event, read from the membership the flow just re-read (`PushTailGuard`).
 */
fun pushHandlers(core: AppCore): PushHandlers {
    val ports = core.ports
    val entry: EntryContext = core.process.entryContext
    val log = ports.log
    return PushHandlers(
        onToken = { token: PushToken ->
            log.invocation(entry, "onPushToken", params = "hex=${token.hex.take(TOKEN_PREFIX)}…") {
                ports.push.tokens.deliver(token.hex)
            }
        },
        onTokenFailure = { error: PlatformError? ->
            log.invocation(entry, "onPushTokenFailure", params = "error=${error?.description}") {
                log.w { "APNs registration failed — no silent pushes will arrive: ${error?.description}" }
            }
        },
        onMessage = { message: PushMessage, completion: Completion ->
            log.invocation(entry, "onSilentPush") { core.silentPush(message, completion) }
        },
    )
}

/** The development controls' one command: void this device's durable sync state (the channel's reset). */
fun devHandlers(core: AppCore): DevHandlers = DevHandlers(onReset = { core.resetDeviceState.reset() })

private fun AppCore.silentPush(message: PushMessage, completion: Completion) {
    val wake = tail.hold("onSilentPush")
    val completions = OsCompletions("onSilentPush", log = ports.log)
    wake.guard(completions.adopt(completion))
    scope.launch {
        completions.releaseAfter {
            ports.log.invocation(
                process.entryContext,
                "onSilentPush.run",
                params = "protectedData=${ports.processInfo.protectedDataAvailable()}",
            ) {
                silentPushFlow.run(message.payload)
            }
        }
        // Only for the active event, read from the membership the flow just re-read: a push for another event, a left
        // one, none, or an unreadable membership wakes no tail (capability `receiving-photos`).
        val joinsTail = pushEventId(message.payload)?.let(pushTailGuard::joinsTail) == true
        if (joinsTail) wake.thenTail(TailTrigger.SILENT_PUSH) else wake.end()
    }
}

/** How much of a push token the entry line shows — enough to tell two apart, not the credential. */
private const val TOKEN_PREFIX = 12

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
