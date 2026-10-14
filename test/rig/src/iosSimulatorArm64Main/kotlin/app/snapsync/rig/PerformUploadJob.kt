@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.snapsync.rig

import app.snapsync.gallery.PhotoKitResource
import app.snapsync.ios.upload.SimulatorUploadJobs
import app.snapsync.levers.PerformedJob
import app.snapsync.levers.PlayedJob
import co.touchlab.kermit.Logger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSUUID
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.Foundation.uploadTaskWithRequest
import platform.Photos.PHAssetResource
import platform.Photos.PHAssetResourceManager
import kotlin.coroutines.resume

/**
 * `upload-jobs.perform` — **the OS moving the bytes**, on a host where it will not.
 *
 * The substituted upload-job queue accepts a job and reports what the caller says became of it; this verb
 * is the other half — actually performing one, for real, against the destination and headers the cycle
 * composed. Together they are the operator playing the system's own upload scheduler.
 *
 * **Why the transfer is real rather than declared.** Declaring a job succeeded without moving bytes is what
 * the JVM host's mocked job queue already does, in memory, faster, and on JVM too — it would add nothing here. A real
 * transfer is the entire marginal value of this host: it is the only thing anywhere that demonstrates a URL
 * the edge-URL builder composed is one the backend accepts, carrying the bytes PhotoKit yields, under a
 * real attestation token. That builder is pinned only by `commonMain` tests on string composition.
 *
 * Failure, by contrast, is worth forging: `fail` skips the PUT entirely so a retry chain is deterministic
 * without having to break the backend to get one.
 */

/**
 * The verb: [job]'s resource staged from PhotoKit and sent with the job's own URL and headers — or, with [fail], no
 * request at all. Deliberately not "PUT and then lie about the result": the point of forging is to reach the engine's
 * retry chain without a backend that has to misbehave.
 */
internal suspend fun performUploadJob(
    job: PlayedJob,
    fail: String?,
    log: Logger = Logger.withTag(
        "rig",
    ),
): PerformedJob {
    if (fail != null) {
        log.i { "perform ${job.destination}: forced failure ($fail) — no request was sent" }
        return PerformedJob(job.destination, performed = false, forcedFailure = fail)
    }
    val resource = resourceOf(job.destination) ?: throw IllegalArgumentException(
        "no job this cycle created was made towards '${job.destination}' with a PhotoKit resource — the OS uploads only " +
            "a job it holds, and the substitute holds this cycle's",
    )
    val staged = stageResource(resource)
        ?: error("could not read the bytes for '${job.destination}' from PhotoKit")
    return try {
        put(job, staged, log)
    } finally {
        NSFileManager.defaultManager.removeItemAtURL(staged, error = null)
    }
}

/**
 * Perform the PUT with the request's own URL and headers, **verbatim**.
 *
 * Verbatim is the whole point: any normalisation here would be this verb testing its own idea of the
 * request rather than the one the cycle composed, which is the single thing this host can prove and
 * nothing else can.
 *
 * A **default** `NSURLSession`, necessarily — a background session transfers nothing on a simulator
 * (`nsurlsessiond` rejects the client's bundle identifier as `(null)`). So these transfers die with the
 * process, where the OS's own genuinely survive. That is a divergence from the system being impersonated,
 * and it is stated in the `ios-simulator` skill rather than papered over.
 */
private suspend fun put(request: PlayedJob, file: NSURL, log: Logger): PerformedJob {
    val url = NSURL.URLWithString(request.destination)
        ?: return PerformedJob(request.destination, performed = false, error = "the destination is not a URL this platform accepts")
    val http = NSMutableURLRequest.requestWithURL(url)
    http.setHTTPMethod("PUT")
    request.headers.forEach { (name, value) -> http.setValue(value, forHTTPHeaderField = name) }

    val session = NSURLSession.sessionWithConfiguration(NSURLSessionConfiguration.defaultSessionConfiguration)
    val result: Pair<NSHTTPURLResponse?, NSError?> = suspendCancellableCoroutine { cont ->
        val task = session.uploadTaskWithRequest(http, fromFile = file) { _, response, error ->
            cont.resume((response as? NSHTTPURLResponse) to error)
        }
        task.resume()
    }
    val (response, error) = result
    val status = response?.statusCode?.toInt()
    log.i { "perform ${request.destination}: status=${status ?: "none"} error=${error?.localizedDescription ?: "none"}" }
    return PerformedJob(request.destination, performed = true, status = status, error = error?.localizedDescription)
}

/**
 * Stage the resource's bytes to a file, the way the app-driven tier already does on this very host.
 *
 * `writeDataForAssetResource(_:toFile:)` rather than an in-memory read: it is the technique
 * `IosUrlSessionUploadPlatform.stageResource` uses and the one measured to work on a simulator. The
 * *adapter* is deliberately not reused — that class IS the app-driven tier's mechanism, and routing the
 * OS-driven tier's test transport through it would launder the thing under test through its alternative.
 */
private suspend fun stageResource(resource: PHAssetResource): NSURL? {
    val dir = NSURL.fileURLWithPath(NSTemporaryDirectory())
    val file = dir.URLByAppendingPathComponent("rig-perform-${NSUUID().UUIDString}") ?: return null
    NSFileManager.defaultManager.removeItemAtURL(file, error = null)
    val error: NSError? = suspendCancellableCoroutine { cont ->
        PHAssetResourceManager.defaultManager().writeDataForAssetResource(
            resource,
            toFile = file,
            options = null,
        ) { err -> cont.resume(err) }
    }
    return if (error == null) file else null
}

/**
 * The resource the job made towards [destination] sends — the one the OS holds on a job, as the substitute holds this
 * cycle's: the latest job made or re-pointed there.
 */
private suspend fun resourceOf(destination: String): PHAssetResource? =
    (SimulatorUploadJobs.createdThisCycle().lastOrNull { it.destination == destination }?.resource as? PhotoKitResource)
        ?.resource
