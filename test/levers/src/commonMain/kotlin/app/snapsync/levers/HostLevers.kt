package app.snapsync.levers

import kotlinx.rpc.annotations.Rpc
import kotlinx.rpc.descriptor.serviceDescriptorOf
import kotlinx.rpc.internal.utils.ExperimentalRpcApi

// The control channel's verbs whose subject is the HOST — its process, its launch, its platform's own library, the
// contracts it runs — rather than a mocked system (`docs/testing.md`, "Typed services"). Each is a host's own service
// ([LeverNeed.HOST]): every host serves it and says, method by method, what it honours and why it refuses the rest.

/** **The host's process and what it knows**: the app's state as the screen shows it, its logs, its life and death. */
@Rpc
interface HostLevers {
    /** The app's state: the screen it shows, and the facts beside it no screen carries ([RigState]). */
    suspend fun state(): RigState

    /**
     * The tail of a process's device log — at most [bytes] (omitted: about 200 KB) of [process]'s (omitted: the app's).
     * **An agent's read, never a test's**: log text is no outcome a person observes (`docs/testing.md`, "The rules").
     */
    suspend fun logs(process: DeviceLog? = null, bytes: Int? = null): String

    /**
     * Process death and a launch over the same device: a cold foreground launch whose screen is built — or, [scene]
     * `false`, a cold BACKGROUND launch, the operating system starting the process for a wake with no screen, which
     * nothing that reads the screen may be asked of before a foreground brings one up.
     */
    suspend fun relaunch(scene: Boolean? = null)

    /**
     * The app deleted and installed again: its files, databases and user defaults are gone, the keychain, the photo
     * library (but its grant) and the backend keep theirs — then a cold foreground launch, as [relaunch] makes one.
     */
    suspend fun reinstall()

    /**
     * [event]'s whole invite, as the device that created it shares it — its key inside, for an encrypted event. What a
     * test opens to play a member who was sent the invite; a phone's invite leaves only through its share sheet.
     */
    suspend fun invite(@JoinedEvent event: String? = null): String

    /**
     * What the app's files take on this device's real disk, per container: bytes and files per directory down to
     * [depth] levels (omitted: 2), and the [largest] files (omitted: 20).
     */
    suspend fun disk(depth: Int? = null, largest: Int? = null): DiskCensus

    /**
     * A process-metric report the operating system would deliver — [fields], its open key/value bag — driven through
     * the app's own handler, so what it does is what a real report does. Answers what crossed a threshold.
     */
    suspend fun processMetrics(fields: Map<String, String>): MetricReport

    @OptIn(ExperimentalRpcApi::class)
    companion object : LeverService<HostLevers>(
        HostLevers::class,
        "host",
        setOf(LeverNeed.HOST),
        { serviceDescriptorOf() },
        reads = setOf("state", "logs", "disk"),
    )
}

/**
 * **This device's photo library, as a person's camera roll** — the library the app reads, whether it is the
 * platform's real one or the launch's mock: seeded, emptied, added to and read through the app's own policy.
 */
@Rpc
interface GalleryLevers {
    /**
     * [n] photos of [kind] added to the library. A kind this host's library cannot produce is refused, never seeded as
     * some other kind.
     */
    suspend fun seed(n: Int, kind: SeedKind): SeedOutcome

    /**
     * **Irreversible**: the library emptied of [scope] — or, with [limit] and [offset], the window of the matched
     * assets they name. The platform asks a person to confirm; the call waits for the answer.
     */
    suspend fun wipe(scope: WipeScope, limit: Long? = null, offset: Long? = null): WipeOutcome

    /** ONE photo that moves, added as the phone's own camera took it, from the camera's real [photo] files. Answers its id. */
    suspend fun addMoving(photo: MovingPhoto): String

    /**
     * The library through the app's own candidate seam and selection policy: its census and, under a [cutoff], every
     * asset with its verdict — and its resources, with [resources]. [downloadOnly] reads it as a member who shares
     * nothing does.
     */
    suspend fun read(cutoff: String? = null, resources: Boolean? = null, downloadOnly: Boolean? = null): GalleryView

    @OptIn(ExperimentalRpcApi::class)
    companion object : LeverService<GalleryLevers>(
        GalleryLevers::class,
        "gallery",
        setOf(LeverNeed.HOST),
        { serviceDescriptorOf() },
        reads = setOf("read"),
    )
}

/**
 * **The launch-time adapters** (`docs/testing.md`, "Launch-time adapters"): which systems this launch runs over as
 * mocks, and the choice its next start reads. An app host's alone: the JVM root's caller chooses real or mock per port
 * as it composes.
 */
@Rpc
interface AdaptersLevers {
    /** The choice this launch runs, the file behind it and the folder it lives in, and this host's own facts. */
    suspend fun current(): CurrentAdapters

    /**
     * The next launch's [choice] — the adapters file's text, one `system=mock|real` per line — written, the mocked
     * systems' state saved, and the app EXITS once the answer has left; its next start composes over it. Refused while
     * this device is a member of an event, or for a choice this host cannot compose.
     */
    suspend fun choose(choice: String): AdaptersWritten

    /** The adapter choice deleted — with every mocked system's state, where the host keeps any — and the app exits. */
    suspend fun clear(): String

    @OptIn(ExperimentalRpcApi::class)
    companion object : LeverService<AdaptersLevers>(
        AdaptersLevers::class,
        "adapters",
        setOf(LeverNeed.HOST),
        { serviceDescriptorOf() },
        reads = setOf("current"),
    )
}

/**
 * **The port contracts this build runs in-app** (`docs/architecture.md`): what a simulator app runs live on every push
 * and a phone records for the build to replay.
 */
@Rpc
interface ContractLevers {
    /**
     * The contracts registered for the host this process is, under the photo [grant] (omitted: the default one) and
     * with the [precondition] (omitted: none).
     */
    suspend fun list(grant: String? = null, precondition: String? = null): List<String>

    /**
     * Run contract [name] and answer its body: a device recording, committed unedited, or a simulator app's outcome
     * table. [host] picks the entry recorded for that host (one run inside the extension, which the app requests on its
     * behalf); [grant] the entries taken under that grant; [params] the contract's own arguments (`fixture`, …). A
     * contract that finds itself on the wrong host is refused; one that never answered fails.
     */
    suspend fun run(
        name: String,
        host: String? = null,
        grant: String? = null,
        params: Map<String, String>? = null,
    ): String

    @OptIn(ExperimentalRpcApi::class)
    companion object : LeverService<ContractLevers>(
        ContractLevers::class,
        "contract",
        setOf(LeverNeed.HOST),
        { serviceDescriptorOf() },
        reads = setOf("list"),
    )
}

/**
 * **The upload-job queue the operating system holds, played** — where the channel plays the OS for the extension's
 * jobs (the iOS simulator app, whose queue is substituted). On a phone the OS owns the queue; on a host whose queue is
 * the mock's, its own operator plays it.
 */
@Rpc
interface UploadJobLevers {
    /**
     * One `process()` of the upload extension, handed this invocation's job sets as the OS holds them: the jobs it
     * has [finished] with, and its in-flight cap [jobLimit] (omitted: none). Answers the processing result and the jobs
     * the cycle created, their destination and headers verbatim.
     */
    suspend fun process(finished: List<FinishedJob>, jobLimit: Int? = null): JobCycle

    /**
     * A job a cycle created ([job], verbatim from [JobCycle.created]) performed as the OS would: its resource's bytes
     * sent with its own URL and headers. [fail] forges a failure instead — no request is sent — so a retry chain is
     * reached without a backend that has to misbehave.
     */
    suspend fun perform(job: PlayedJob, fail: String? = null): PerformedJob

    /**
     * The extension's registration record, as a lever: planted or removed ([registered]), and the next enable or
     * disable refused once with the PhotoKit error [failNextWith] — or the failure disarmed with `0`.
     */
    suspend fun extensionRecord(registered: Boolean? = null, failNextWith: Long? = null): ExtensionRecordState

    @OptIn(ExperimentalRpcApi::class)
    companion object : LeverService<UploadJobLevers>(
        UploadJobLevers::class,
        "upload-jobs",
        setOf(LeverNeed.HOST),
        { serviceDescriptorOf() },
    )
}
