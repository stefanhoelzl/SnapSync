package app.snapsync.download

import app.snapsync.ports.DownloadTask
import app.snapsync.ports.DownloadTransport
import app.snapsync.ports.DownloadTransportHost
import app.snapsync.ports.TransferOutcome

import app.snapsync.logging.invocation
import app.snapsync.model.PlatformEntry
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDownloadDelegateProtocol
import platform.Foundation.NSURLSessionDownloadTask
import platform.Foundation.NSURLSessionTask
import platform.darwin.NSObject

/** Background-session identifier — stable so an app relaunch reconnects to the same transfers. */
private const val DOWNLOAD_SESSION_ID = "app.snapsync.download.bg"

/**
 * The iOS [DownloadTransport] (capability `photo-download`): a **background** `URLSession` (Wi-Fi *and*
 * cellular, non-discretionary) that keeps downloading while the app is suspended and relaunches it on
 * completion. Bytes are moved into durable App-Group staging as each transfer finishes.
 *
 * This class is the ObjC edge and nothing more — the queue, the bounded window, the transfer-description
 * codec, the staging-path derivation, and the URL guard all live in [QueuedPhotoDownloadJobs], where they
 * are covered by `commonTest`.
 *
 * **It never invalidates the session.** Cancellation is per-task ([DownloadTask.cancel]); the session is
 * a process-lifetime singleton. If the *system* invalidates it, the delegate reports `onInvalidated` and
 * the owner discards this transport whole — which is why the session below can safely be a `by lazy`: an
 * invalidated session is never reachable for reuse. See [DownloadTransport].
 */
@OptIn(ExperimentalForeignApi::class)
class IosDownloadTransport(
    private val host: DownloadTransportHost,
    private val log: Logger = Logger.withTag("DownloadTransport"),
) : DownloadTransport {

    private val delegate = Delegate(this)

    /**
     * Built eagerly: the owner constructs this transport exactly when a session is wanted — on the first
     * transfer, or on a `handleEventsForBackgroundURLSession` relaunch, where the session must exist for
     * the OS to deliver the pending completions to its delegate.
     */
    private val session: NSURLSession = run {
        val config = NSURLSessionConfiguration.backgroundSessionConfigurationWithIdentifier(DOWNLOAD_SESSION_ID)
        // Transfer over Wi-Fi AND cellular, and don't defer to "discretionary" windows (which strongly
        // favor Wi-Fi + charging): downloads should make progress on mobile too.
        config.discretionary = false
        config.allowsCellularAccess = true
        config.sessionSendsLaunchEvents = true
        NSURLSession.sessionWithConfiguration(config, delegate, null as NSOperationQueue?)
    }

    override fun start(url: String, description: String): DownloadTask? {
        val nsUrl = NSURL.URLWithString(url) ?: return null
        val task = session.downloadTaskWithURL(nsUrl)
        task.taskDescription = description
        task.resume()
        return IosDownloadTask(task)
    }

    private class IosDownloadTask(private val task: NSURLSessionDownloadTask) : DownloadTask {
        override fun cancel() = task.cancel()
    }

    private fun onFinished(downloadTask: NSURLSessionDownloadTask, location: NSURL) {
        val description = downloadTask.taskDescription ?: return
        // A finished transfer is not a good transfer: URLSession delivers an HTTP error here as a
        // *successful* transfer of the error body, with a nil completion error. Ask before staging —
        // staging is what makes these bytes the store's truth, and `moveToStaging` deletes whatever is
        // already at the destination, so staging a rejected body would also destroy an earlier good file.
        if (!host.accepts(description, outcomeOf(downloadTask, location))) return
        // Asked of the owner, not remembered here: after a relaunch this process never started the
        // transfer, so the destination must be derivable from the description alone.
        val destination = host.destinationFor(description) ?: return
        if (moveToStaging(location, destination)) host.onStaged(description, destination)
    }

    /**
     * Read the transfer's facts off the response and the file on disk. This is the whole of the edge's
     * involvement in integrity — the judgement lives in `commonMain` where `commonTest` covers it.
     *
     * `expectedContentLength` is `NSURLResponseUnknownLength` (-1) when the server sent no
     * `Content-Length`; that negative value is passed through verbatim rather than normalized, because
     * "unknown" is a distinct answer from "zero" to the code that judges it.
     */
    private fun outcomeOf(task: NSURLSessionDownloadTask, location: NSURL): TransferOutcome {
        val http = task.response as? NSHTTPURLResponse
        val received = location.path
            ?.let { NSFileManager.defaultManager.attributesOfItemAtPath(it, error = null) }
            ?.get(NSFileSize) as? NSNumber
        return TransferOutcome(
            statusCode = http?.statusCode?.toInt(),
            expectedBytes = task.response?.expectedContentLength ?: -1L,
            receivedBytes = received?.longLongValue ?: 0L,
        )
    }

    private fun onComplete(task: NSURLSessionTask, error: NSError?) {
        val description = task.taskDescription ?: return
        host.onCompleted(description, error?.localizedDescription)
    }

    /** Move the finished temp file into durable App-Group staging; last-write-wins on a re-download. */
    private fun moveToStaging(tempUrl: NSURL, destination: String): Boolean {
        val fm = NSFileManager.defaultManager
        fm.createDirectoryAtPath(
            destination.substringBeforeLast('/'),
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        fm.removeItemAtPath(destination, error = null)
        if (!fm.moveItemAtURL(tempUrl, NSURL.fileURLWithPath(destination), error = null)) {
            log.w { "failed to stage $destination" }
            return false
        }
        return true
    }

    /**
     * The Obj-C download delegate — a NON-inner nested class (the proven codegen-safe shape for an Obj-C
     * protocol implementer) holding a back-reference to the [transport] it forwards to.
     */
    private class Delegate(private val transport: IosDownloadTransport) : NSObject(), NSURLSessionDownloadDelegateProtocol {
        // PLATFORM ENTRY POINTS (spec `diagnostic-logging`): the OS calls these, so each records that
        // it was called before doing anything. The two per-task callbacks log at DEBUG on purpose —
        // they fire once per photo, and at INFO a 200-photo event would flush the crash reporter's
        // bounded breadcrumb window and roll the size-capped device log before anyone read it.
        @PlatformEntry
        override fun URLSession(
            session: NSURLSession,
            downloadTask: NSURLSessionDownloadTask,
            didFinishDownloadingToURL: NSURL,
        ) = transport.log.invocation("download.didFinishDownloading", severity = Severity.Debug) {
            transport.onFinished(downloadTask, didFinishDownloadingToURL)
        }

        @PlatformEntry
        override fun URLSession(
            session: NSURLSession,
            task: NSURLSessionTask,
            didCompleteWithError: NSError?,
        ) = transport.log.invocation(
            "download.didComplete",
            // Domain and code, not just `localizedDescription`. iOS renders NSURLErrorUnknown as the
            // literal string "unknown error", which names nothing an operator can act on or search for —
            // an absence collapse in the one line that reports a failed transfer. `NSURLErrorDomain/-1`
            // is what identified the simulator's background-session behaviour (decision record:
            // changes/add-simulator-rig-host, task 6.2) and cost a rebuild to recover.
            params = "error=${didCompleteWithError?.let { "${it.domain}/${it.code}: ${it.localizedDescription}" } ?: "«none»"}",
            severity = Severity.Debug,
        ) {
            transport.onComplete(task, didCompleteWithError)
        }

        /**
         * The session died and was **not** killed by us (we never invalidate). Tell the owner so it
         * discards this transport; reusing the session would create a task on a dead session, raising an
         * `NSException` Kotlin/Native cannot catch — an abort.
         */
        @PlatformEntry
        override fun URLSession(session: NSURLSession, didBecomeInvalidWithError: NSError?) {
            transport.log.w { "background session invalidated by the system: ${didBecomeInvalidWithError?.localizedDescription}" }
            transport.host.onInvalidated()
        }

        // Session-level, not per-task: INFO.
        @PlatformEntry
        override fun URLSessionDidFinishEventsForBackgroundURLSession(session: NSURLSession) =
            transport.log.invocation("download.didFinishEvents") {
                transport.host.onBackgroundEventsFinished()
            }
    }
}
