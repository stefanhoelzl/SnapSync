package app.snapsync.rig

import app.snapsync.compose.AppCore
import app.snapsync.contracts.CONTRACT_REFUSED
import app.snapsync.contracts.CONTRACT_TIMEOUT
import app.snapsync.contracts.InAppContract
import app.snapsync.contracts.currentHost
import app.snapsync.levers.AdaptersLevers
import app.snapsync.levers.AdaptersWritten
import app.snapsync.levers.ContractLevers
import app.snapsync.levers.CurrentAdapters
import app.snapsync.levers.DeviceLog
import app.snapsync.levers.DiskCensus
import app.snapsync.levers.ExtensionRecordState
import app.snapsync.levers.FinishedJob
import app.snapsync.levers.GalleryLevers
import app.snapsync.levers.GalleryView
import app.snapsync.levers.HostLevers
import app.snapsync.levers.JobCycle
import app.snapsync.levers.LeverRefused
import app.snapsync.levers.LeverService
import app.snapsync.levers.MetricReport
import app.snapsync.levers.MovingPhoto
import app.snapsync.levers.PerformedJob
import app.snapsync.levers.PlayedJob
import app.snapsync.levers.RigState
import app.snapsync.levers.SeedKind
import app.snapsync.levers.SeedOutcome
import app.snapsync.levers.UploadJobLevers
import app.snapsync.levers.WipeOutcome
import app.snapsync.levers.WipeScope
import app.snapsync.presentation.StatusContainerHost
import app.snapsync.services.logs.LogTailService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The host's own services (`HostLevers.kt`), as each host serves them: the host names, verb by verb, what it does — or
// why it refuses — and these build the service from that, so the three hosts differ only in what they say, never in how
// a verb is served. A refused verb is never invoked: the dispatch answers its reason (`LeverDispatch.kt`).

/** One verb of a host's own service: honoured, by [Honoured.run], or refused with [Refused.why]. */
sealed interface Verb<out F> {
    class Honoured<F>(val run: F) : Verb<F>

    class Refused(val why: String) : Verb<Nothing>
}

/** The verb, honoured by [run]. */
fun <F> honoured(run: F): Verb<F> = Verb.Honoured(run)

/** The verb, refused with [why]. */
fun refusing(why: String): Verb<Nothing> = Verb.Refused(why)

private fun <F> Verb<F>.run(): F = (this as Verb.Honoured<F>).run

/** The refusals among [verbs], by method name. */
private fun refusalsOf(vararg verbs: Pair<String, Verb<*>>): Map<String, String> =
    verbs.mapNotNull { (method, verb) -> (verb as? Verb.Refused)?.let { method to it.why } }.toMap()

/**
 * Every method of [service] refused with [uncomposed] — a launch that composed nothing — but [exempt]: there is no app
 * to reach, and only what reads or rewrites the launch itself still answers.
 */
private fun <T : Any> uncomposedRefusals(service: LeverService<T>, uncomposed: String?, vararg exempt: String) =
    uncomposed?.let { why ->
        service.refusingAll(
            "this launch composed nothing — its adapter choice was refused: $why. Choose a coherent one (adapters.choose) " +
                "or clear it (adapters.clear); either exits the app for its next start",
        ) - exempt.toSet()
    }.orEmpty()

/**
 * The host's process ([HostLevers]): its state and its logs, read the same way on every host, and the verbs that
 * differ by host — each as [Verb].
 */
fun hostService(
    hooks: RigHooks,
    core: () -> AppCore,
    host: () -> StatusContainerHost,
    relaunch: Verb<suspend (scene: Boolean) -> Unit>,
    reinstall: Verb<suspend () -> Unit>,
    invite: Verb<suspend (event: String) -> String>,
    disk: Verb<suspend (depth: Int, largest: Int) -> DiskCensus>,
    processMetrics: Verb<suspend (fields: Map<String, String>) -> MetricReport>,
): HostService<HostLevers> = HostService(
    HostLevers,
    object : HostLevers {
        override suspend fun state(): RigState = readState(core(), host(), hooks)

        override suspend fun logs(process: DeviceLog?, bytes: Int?): String {
            val which = process ?: DeviceLog.APP
            // "No such log" and "could not read it" are one answer from the tail, and neither is "empty": a stated failure.
            return hooks.deviceLog(which.tail, bytes ?: DEFAULT_LOG_BYTES)
                ?: error("no log for the ${which.name.lowercase()} process: it does not exist on this device, or it could not be read")
        }

        override suspend fun relaunch(scene: Boolean?) = relaunch.run()(scene ?: true)

        override suspend fun reinstall() = reinstall.run()()

        override suspend fun invite(event: String?): String = invite.run()(checkNotNull(event))

        override suspend fun disk(depth: Int?, largest: Int?): DiskCensus =
            disk.run()(
                (depth ?: DEFAULT_DEPTH).coerceIn(1, MAX_DEPTH),
                (largest ?: DEFAULT_LARGEST).coerceIn(0, MAX_LARGEST),
            )

        override suspend fun processMetrics(fields: Map<String, String>): MetricReport = processMetrics.run()(fields)
    },
    refusalsOf(
        "relaunch" to relaunch,
        "reinstall" to reinstall,
        "invite" to invite,
        "disk" to disk,
        "processMetrics" to processMetrics,
    ) + uncomposedRefusals(HostLevers, hooks.uncomposed, "logs", "disk"),
)

/** The photo library a person's camera fills ([GalleryLevers]), each verb as this host's library takes it. */
fun galleryService(
    seed: Verb<suspend (n: Int, kind: SeedKind) -> SeedOutcome>,
    wipe: Verb<suspend (scope: WipeScope, limit: Long?, offset: Long?) -> WipeOutcome>,
    addMoving: Verb<suspend (MovingPhoto) -> String>,
    read: Verb<suspend (cutoff: String?, resources: Boolean, includesUpload: Boolean) -> GalleryView>,
    uncomposed: String? = null,
): HostService<GalleryLevers> = HostService(
    GalleryLevers,
    object : GalleryLevers {
        // Off every lane the app runs on: a photo-library change block waits on the system's own confirmation, which can
        // sit unanswered for minutes.
        override suspend fun seed(n: Int, kind: SeedKind): SeedOutcome {
            require(n > 0) { "n must be a positive integer, was $n" }
            return withContext(Dispatchers.Default) { seed.run()(n, kind) }
        }

        override suspend fun wipe(scope: WipeScope, limit: Long?, offset: Long?): WipeOutcome {
            require((limit ?: 0) >= 0 && (offset ?: 0) >= 0) { "limit and offset are never negative" }
            return withContext(Dispatchers.Default) { wipe.run()(scope, limit, offset) }
        }

        override suspend fun addMoving(photo: MovingPhoto): String {
            require(photo.files.isNotEmpty()) { "a moving photo has at least one file" }
            return withContext(Dispatchers.Default) { addMoving.run()(photo) }
        }

        override suspend fun read(cutoff: String?, resources: Boolean?, downloadOnly: Boolean?): GalleryView =
            // A member who shares nothing reads the library through the deny-everything policy.
            read.run()(cutoff, resources ?: false, downloadOnly != true)
    },
    refusalsOf("seed" to seed, "wipe" to wipe, "addMoving" to addMoving, "read" to read) +
        uncomposedRefusals(GalleryLevers, uncomposed, "seed", "wipe", "addMoving"),
)

/**
 * The launch-time adapters ([AdaptersLevers]) refused whole, with [why] — a host with no launch-time adapters: the JVM
 * root's caller chooses real or mock per port as it composes. An app host serves them (`adaptersService`).
 */
fun refusedAdaptersService(why: String): HostService<AdaptersLevers> = HostService(
    AdaptersLevers,
    object : AdaptersLevers {
        override suspend fun current(): CurrentAdapters = error(why)

        override suspend fun choose(choice: String): AdaptersWritten = error(why)

        override suspend fun clear(): String = error(why)
    },
    AdaptersLevers.refusingAll(why),
)

/**
 * The port contracts this build runs in-app ([ContractLevers]) — or, with [refusal], none: a host whose bindings run
 * elsewhere refuses rather than answering an empty list, so "run every contract this host lists" never passes vacuously.
 */
fun contractService(contracts: List<InAppContract>, refusal: String?): HostService<ContractLevers> = HostService(
    ContractLevers,
    object : ContractLevers {
        override suspend fun list(grant: String?, precondition: String?): List<String> =
            contracts.filter { it.host == currentHost && it.grant?.name == grant && it.precondition == precondition }
                .map { it.name }

        override suspend fun run(name: String, host: String?, grant: String?, params: Map<String, String>?): String {
            // One name can be registered for two hosts — `LinkOpener` is recorded on a device and run live on the
            // simulator app — so this process's own entry wins; another host's entry still answers, with its refusal.
            val named = contracts.filter {
                it.name == name && (host == null || it.host.name == host) && it.grant?.name == grant
            }
            val contract = (named.firstOrNull { it.host == currentHost } ?: named.firstOrNull())
                ?: throw IllegalArgumentException("no contract '$name' is registered here — contract.list says which are")
            val arguments = params.orEmpty() + listOfNotNull(host?.let { "host" to it }, grant?.let { "grant" to it })
            // Off the main lane: it blocks until every clause has run.
            val body = withContext(Dispatchers.Default) { contract.run(arguments) }
            return when {
                // A recording on the wrong host would be committed under a host name it never ran on: refused instead.
                body.startsWith(CONTRACT_REFUSED) -> throw LeverRefused(body.removePrefix(CONTRACT_REFUSED).trim())
                // A run inside the extension that never answered: no recording, and a failure that cannot be one.
                body.startsWith(CONTRACT_TIMEOUT) -> error(body.trim())
                else -> body
            }
        }
    },
    refusal?.let { ContractLevers.refusingAll(it) }.orEmpty(),
)

/** The upload-job queue the channel plays ([UploadJobLevers]), each verb as this host's queue takes it. */
fun uploadJobService(
    process: Verb<suspend (finished: List<FinishedJob>, jobLimit: Int?) -> JobCycle>,
    perform: Verb<suspend (job: PlayedJob, fail: String?) -> PerformedJob>,
    extensionRecord: Verb<suspend (registered: Boolean?, failNextWith: Long?) -> ExtensionRecordState>,
): HostService<UploadJobLevers> = HostService(
    UploadJobLevers,
    object : UploadJobLevers {
        override suspend fun process(finished: List<FinishedJob>, jobLimit: Int?): JobCycle = process.run()(
            finished,
            jobLimit,
        )

        override suspend fun perform(job: PlayedJob, fail: String?): PerformedJob = perform.run()(job, fail)

        override suspend fun extensionRecord(registered: Boolean?, failNextWith: Long?): ExtensionRecordState =
            extensionRecord.run()(registered, failNextWith)
    },
    refusalsOf("process" to process, "perform" to perform, "extensionRecord" to extensionRecord),
)

/** The tail a [DeviceLog] names. */
private val DeviceLog.tail: LogTailService.Process
    get() = when (this) {
        DeviceLog.APP -> LogTailService.Process.APP
        DeviceLog.EXTENSION -> LogTailService.Process.EXTENSION
    }

/** ~200 KB: comfortably more than a single cycle's lines, well under the port's 10 MB roll cap. */
private const val DEFAULT_LOG_BYTES = 200_000
private const val DEFAULT_DEPTH = 2
private const val MAX_DEPTH = 6
private const val DEFAULT_LARGEST = 20
private const val MAX_LARGEST = 500
