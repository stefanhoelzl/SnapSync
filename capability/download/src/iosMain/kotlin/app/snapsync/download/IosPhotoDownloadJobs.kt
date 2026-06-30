package app.snapsync.download

import app.snapsync.downloadstore.AssetRef
import app.snapsync.downloadstore.PendingDownload
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDownloadDelegateProtocol
import platform.Foundation.NSURLSessionDownloadTask
import platform.Foundation.NSURLSessionTask
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/** Background-session identifier — stable so an app relaunch reconnects to the same transfers. */
private const val DOWNLOAD_SESSION_ID = "app.snapsync.download.bg"

/** Bounded in-flight window (Apple: keep background tasks in the low hundreds; we stay well under). */
private const val MAX_IN_FLIGHT = 24

/** taskDescription field separator — a newline cannot occur in device ids / sanitized keys / filenames. */
private const val SEP = "\n"

/**
 * The iOS [PhotoDownloadJobs] (capability `photo-download`): a **background** `URLSession`
 * (discretionary, Wi-Fi-only) that downloads foreign resources while suspended and relaunches the app
 * on completion. Each finished download is moved to durable App-Group staging and reported via
 * [onStaged]; a bounded in-flight window refills as tasks complete.
 *
 * The Obj-C `URLSession` delegate is a separate nested class ([Delegate]) — Kotlin/Native forbids one
 * class mixing an Obj-C supertype with the Kotlin [PhotoDownloadJobs] interface — that forwards each
 * callback back here.
 */
@OptIn(ExperimentalForeignApi::class)
class IosPhotoDownloadJobs(
    private val scope: CoroutineScope,
    private val stagingRoot: String,
    private val log: Logger = Logger.withTag("PhotoDownloadJobs"),
) : PhotoDownloadJobs {

    /** Set by the composition root after the controller exists: deliver a staged resource. */
    var onStaged: ((AssetRef, resourceKey: String, stagedPath: String) -> Unit)? = null

    /** Stored when the OS relaunches the app for background events; invoked when they drain. */
    private var backgroundCompletion: (() -> Unit)? = null

    private val queued = ArrayDeque<PendingDownload>()
    private var inFlight = 0

    private val delegate = Delegate(this)
    private val session: NSURLSession by lazy {
        val config = NSURLSessionConfiguration.backgroundSessionConfigurationWithIdentifier(DOWNLOAD_SESSION_ID)
        config.discretionary = true
        config.allowsCellularAccess = false
        config.sessionSendsLaunchEvents = true
        NSURLSession.sessionWithConfiguration(config, delegate, null as NSOperationQueue?)
    }

    override suspend fun enqueue(downloads: List<PendingDownload>) {
        downloads.forEach { queued.addLast(it) }
        pump()
    }

    override suspend fun cancelAll() {
        queued.clear()
        session.invalidateAndCancel()
    }

    /**
     * Called from the Swift host's `handleEventsForBackgroundURLSession`: realize the session so the
     * delegate receives pending events, and store the completion handler to call once they drain.
     */
    fun adoptBackgroundEvents(completion: () -> Unit) {
        backgroundCompletion = completion
        session // touch lazy → session exists with the delegate so events are delivered
    }

    private fun pump() {
        while (inFlight < MAX_IN_FLIGHT) {
            val next = queued.removeFirstOrNull() ?: break
            val url = NSURL.URLWithString(next.resource.url) ?: continue
            val task = session.downloadTaskWithURL(url)
            task.taskDescription =
                listOf(next.ref.sourceDeviceId, next.ref.sourceAssetId, next.resource.resourceKey).joinToString(SEP)
            inFlight++
            task.resume()
        }
    }

    private fun onFinished(downloadTask: NSURLSessionDownloadTask, location: NSURL) {
        val parts = downloadTask.taskDescription?.split(SEP) ?: return
        if (parts.size != 3) return
        val ref = AssetRef(parts[0], parts[1])
        val resourceKey = parts[2]
        val stagedPath = moveToStaging(ref, resourceKey, location) ?: return
        onStaged?.invoke(ref, resourceKey, stagedPath)
    }

    private fun onComplete(error: NSError?) {
        inFlight = (inFlight - 1).coerceAtLeast(0)
        if (error != null) log.w { "download task failed (will retry): ${error.localizedDescription}" }
        scope.launch { pump() }
    }

    private fun onEventsDrained() {
        val completion = backgroundCompletion ?: return
        backgroundCompletion = null
        dispatch_async(dispatch_get_main_queue()) { completion() }
    }

    /** Move the finished temp file into durable App-Group staging; returns the staged path or null. */
    private fun moveToStaging(ref: AssetRef, resourceKey: String, tempUrl: NSURL): String? {
        val fm = NSFileManager.defaultManager
        val dir = "$stagingRoot/${ref.sourceDeviceId.replace('/', '_')}"
        fm.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        val dest = "$dir/${resourceKey.replace('/', '_')}"
        fm.removeItemAtPath(dest, error = null) // last-write-wins on re-download
        if (!fm.moveItemAtURL(tempUrl, NSURL.fileURLWithPath(dest), error = null)) {
            log.w { "failed to stage $resourceKey" }
            return null
        }
        return dest
    }

    /**
     * The Obj-C download delegate — a NON-inner nested class (the proven codegen-safe shape for an
     * Obj-C protocol implementer) holding a back-reference to the enclosing [jobs] it forwards to.
     */
    private class Delegate(
        private val jobs: IosPhotoDownloadJobs,
    ) : NSObject(), NSURLSessionDownloadDelegateProtocol {
        override fun URLSession(
            session: NSURLSession,
            downloadTask: NSURLSessionDownloadTask,
            didFinishDownloadingToURL: NSURL,
        ) = jobs.onFinished(downloadTask, didFinishDownloadingToURL)

        override fun URLSession(
            session: NSURLSession,
            task: NSURLSessionTask,
            didCompleteWithError: NSError?,
        ) = jobs.onComplete(didCompleteWithError)

        override fun URLSessionDidFinishEventsForBackgroundURLSession(session: NSURLSession) =
            jobs.onEventsDrained()
    }
}
