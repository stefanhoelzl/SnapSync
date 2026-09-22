package app.snapsync.ports

import app.snapsync.model.PlatformEntry

/**
 * The app process's **inbound** port: what the operating system tells the app (spec `module-architecture`, "OS
 * entry points cross an inbound port").
 *
 * Every other port here is outbound — the core calls it and an adapter answers. This one runs the other way: the
 * **core implements it** (`compose/`'s `platformEntries`) and the shell drives it. The composition root implements
 * it by Kotlin delegation, so the forwarding from the operating system's callback to the core is written by the
 * compiler and a crossed wire has nowhere to sit. The transcription that used to live in the untested shell — which
 * flow an entry runs, which receipt holds its completion, how a background task or transfer channel is routed — is
 * the implementation's, and `:test:contracts`' `PlatformEntriesContract` specifies it.
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
     * A silent push arrived, its [payload] forwarded **whole**. [completion] is released once the fan-out has
     * finished or its deadline expired — always, including for a payload no receiver can use.
     */
    @PlatformEntry
    fun onSilentPush(payload: Map<Any?, *>, completion: () -> Unit)

    /**
     * The operating system launched the background task registered as [identifier] — the identifier it
     * delivered, never one the shell chose. [completion] is released after the task's work or its deadline; an
     * identifier the core does not know is released at once and logged.
     */
    @PlatformEntry
    fun onBackgroundTask(identifier: String, completion: () -> Unit)

    /**
     * The operating system is handing back finished background transfers for [channel]. [completion] is
     * released once that channel's owner has absorbed them, or its deadline expired.
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

    /** The operating system is terminating the cycle. */
    @PlatformEntry
    fun onTerminate()
}
