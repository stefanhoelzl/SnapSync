package app.snapsync.levers

import kotlinx.serialization.Serializable

// What the host's own services answer with (`HostLevers.kt`).

/** Whose device log [HostLevers.logs] reads: the app's, or the upload extension's. */
@Serializable
enum class DeviceLog { APP, EXTENSION }

/**
 * What the app's files take on the real disk, per container: the App Group ([shared], which iOS attributes to the app as
 * "Documents & Data") and the app's own sandbox ([app]). How a device whose storage grew is measured without a Mac — the
 * App Group is not pullable over USB, and a TestFlight build's sandbox is not either.
 */
@Serializable
data class DiskCensus(val shared: ContainerCensus, val app: ContainerCensus)

/** One container's files: its [root], or the [error] that kept it from being read. */
@Serializable
data class ContainerCensus(
    val root: String? = null,
    val error: String? = null,
    val bytes: Long = 0,
    val files: Int = 0,
    /** Every directory down to the asked depth, largest first. */
    val dirs: List<DiskTally> = emptyList(),
    /** The largest files, largest first. */
    val largest: List<DiskTally> = emptyList(),
)

/** A directory's or a file's [bytes] — and, for a directory, the [files] under it. */
@Serializable
data class DiskTally(val path: String, val bytes: Long, val files: Int? = null)

/** What a process-metric report did: how many [fields] it carried, and the [reasons] it crossed a threshold for. */
@Serializable
data class MetricReport(val fields: Int, val reasons: List<String>) {
    val crossed: Boolean get() = reasons.isNotEmpty()
}

/**
 * The launch-time adapter choice as it stands: this launch's, in one line ([launch]), the systems it mocks, the file
 * behind it and the folder it lives in — as a platform path, which is how a simulator script finds where to write a
 * choice before a launch (`simctl` does not list an ad-hoc-signed app's App Group) — every system there is, and this
 * host's own facts: why its choice was refused, or which systems it can make real.
 */
@Serializable
data class CurrentAdapters(
    val launch: String,
    val mocked: List<String>,
    val file: String?,
    val folder: String?,
    val systems: List<String>,
    /** The iOS app host's: why this launch's choice was refused — empty for one that composed. */
    val refusedBecause: List<String>? = null,
    /** The Android app host's: the systems it has a real adapter for. */
    val realAdapters: List<String>? = null,
)

/** A choice written: what the next start composes over, and what the caller does next. The app is exiting. */
@Serializable
data class AdaptersWritten(val written: String, val next: String)

/**
 * One upload job the operating system has finished with, as the caller playing it states it — and in which fetch set it
 * is presented ([action]), which the real queue decides and a played one must be told.
 */
@Serializable
data class FinishedJob(
    /** The destination the job was created with — a [PlayedJob.destination] from an earlier cycle. */
    val destination: String,
    val action: JobAction,
    val state: JobState,
    /** What the OS says of the job's failure — `network`, `http:503`, anything; absent for a success or none said. */
    val error: String? = null,
    /** The job's `Content-Type` — [PlayedJob.contentType] — as the OS keeps it on the job's request. */
    val contentType: String? = null,
)

/** The two fetch sets the OS presents a finished job in (`PHAssetResourceUploadJobAction`). */
@Serializable
enum class JobAction { RETRY, ACKNOWLEDGE }

/** A job's state, in the platform's own five words (`PHAssetResourceUploadJobState`). */
@Serializable
enum class JobState { SUCCEEDED, FAILED, CANCELLED, PENDING, REGISTERED, UNKNOWN }

/** One `process()`: its raw processing result, that result's name, the cap it ran under, and the jobs it created. */
@Serializable
data class JobCycle(val raw: Int, val result: String, val jobLimit: Int, val created: List<PlayedJob>)

/** A job a cycle created, its destination and headers verbatim — the exact request the OS would send. */
@Serializable
data class PlayedJob(
    val destination: String,
    val contentType: String,
    val isRetry: Boolean,
    val headers: Map<String, String>,
)

/**
 * What performing a job did: whether a request was [performed] at all (a forged failure sends none, [forcedFailure]),
 * and the backend's [status] or the transport's [error].
 */
@Serializable
data class PerformedJob(
    val destination: String,
    val performed: Boolean,
    val status: Int? = null,
    val error: String? = null,
    val forcedFailure: String? = null,
) {
    val succeeded: Boolean get() = status != null && status in 200..299
}

/** The extension's registration record as the played OS holds it, and the failure armed for the next change. */
@Serializable
data class ExtensionRecordState(val registered: Boolean, val failNextWith: Long?)
