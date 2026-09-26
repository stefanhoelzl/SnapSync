package app.snapsync.ports

import app.snapsync.model.PlatformEntry
import app.snapsync.model.CycleResult

/**
 * The app process's **inbound** port: what the operating system tells the app (`docs/architecture.md`, "OS
 * entry points cross an inbound port").
 *
 * Every other port here is outbound — the core calls it and an adapter answers. This one runs the other way: the
 * **core implements it** (`compose/`'s `platformEntries`) and the shell drives it. The composition root implements
 * it by Kotlin delegation, so the forwarding from the operating system's callback to the core is written by the
 * compiler and a crossed wire has nowhere to sit. The transcription that used to live in the untested shell — what own
 * work an entry runs, how its completion is held, when it hands the rest to the tail, how a transfer channel is
 * routed — is the implementation's, and `:test:contracts`' `PlatformEntriesContract` specifies it. A scheduled background task
 * is not an entry: it arrives through the `Wake` event port.
 *
 * Members are named for what the operating system is saying, never for the API that says it, and carry only
 * platform-independent values — an Android shell would drive the same port.
 */
interface PlatformEntries {
    /** The app became active (the foreground trigger). */
    @PlatformEntry
    fun onForeground()

    /** The app is leaving the active state, including a transient interruption. */
    @PlatformEntry
    fun onBackground()

    /** An opened link, as the raw string the operating system delivered — fragment included, unparsed. */
    @PlatformEntry
    fun onOpenUrl(url: String)

    /** The push service issued (or rotated) this device's token, as lowercase hex. */
    @PlatformEntry
    fun onPushToken(hex: String)

    /**
     * A silent push arrived, its [payload] forwarded **whole**. [completion] is released once the push's own work —
     * the download arm's union read and enqueue — has finished, or at once when the operating system says the
     * process's background time is up; always, including for a payload no receiver can use. The rest of the push's
     * work runs afterwards in the process's tail, for the active event only.
     */
    @PlatformEntry
    fun onSilentPush(payload: Map<Any?, *>, completion: () -> Unit)

    /**
     * The operating system is handing back finished background transfers for [channel]. [completion] is
     * released once that channel's session reports its events drained and the wake's own work — recording what they
     * delivered — is done, or at once when the process's background time is up. The tail follows the release.
     */
    @PlatformEntry
    fun onBackgroundTransfers(channel: String, completion: () -> Unit)
}

/**
 * The upload extension process's **inbound** port — see [PlatformEntries] for what an inbound port is. Separate
 * from it because the two processes' entry sets are disjoint.
 */
interface ExtensionEntries {
    /** Run one upload cycle and report how it ended. Never throws: a failure is [CycleResult.FAILED]. */
    @PlatformEntry
    suspend fun process(): CycleResult

    /**
     * The operating system's `notifyTermination`: the end of an invocation, NOT a kill. Measured on an SE2 (iOS
     * 26.6, 2026-09-23): it arrives about 55 ms after every normal return of [process], and never before the kill
     * that ends a call running past its ~60 s budget — that kill sends nothing at all (capability
     * `background-upload`, "How the operating system invokes the extension is recorded as measured").
     */
    @PlatformEntry
    fun onTerminate()
}
