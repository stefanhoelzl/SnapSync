package app.snapsync.ios

import app.snapsync.engine.LEDGER_APP_GROUP
import app.snapsync.gallery.IosManifestStore
import app.snapsync.gallery.MANIFEST_URLSESSION_IDENTIFIER
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSURLSessionTaskDelegateProtocol
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.darwin.NSObject

/**
 * The **app** end of the manifest background `URLSession` (capability `asset-manifest`): the system
 * relaunches the app to handle the completion of uploads the *extension* started, delivering them to a
 * same-identifier session this controller owns. On a task's success it flips the asset's on-disk
 * manifest marker to **DONE** (so the extension stops touching it); on failure it re-enqueues the
 * upload from the still-present PENDING file with a short backoff. It maps task→asset via
 * `taskDescription`.
 *
 * Wiring-only and untestable (background `URLSession`, device-only); verified on device. The on-disk
 * marker it flips ([IosManifestStore]) is covered with the manifest model in `commonTest`.
 */
@OptIn(ExperimentalForeignApi::class)
class ManifestUploadController(
    private val store: IosManifestStore,
    private val host: String,
    private val eventIdProvider: () -> String?,
    private val log: Logger = Logger.withTag("ManifestUpload"),
) {

    private var backgroundCompletionHandler: (() -> Unit)? = null

    // The app's adoption of the extension-started background session: same identifier + shared
    // container so the system delivers the extension's task events here. Built lazily; constructing it
    // registers the delegate that receives those events.
    private val session: NSURLSession by lazy {
        val config = NSURLSessionConfiguration
            .backgroundSessionConfigurationWithIdentifier(MANIFEST_URLSESSION_IDENTIFIER)
        config.sharedContainerIdentifier = LEDGER_APP_GROUP
        NSURLSession.sessionWithConfiguration(config, Delegate(), delegateQueue = null)
    }

    /**
     * Adopt the background session whose events the system is delivering. Called from the app
     * delegate's `handleEventsForBackgroundURLSession`; stashes the OS completion handler (invoked once
     * the session finishes delivering events) and forces the session into existence so events flow.
     */
    fun handleEvents(identifier: String, completionHandler: () -> Unit) {
        if (identifier != MANIFEST_URLSESSION_IDENTIFIER) {
            completionHandler() // not ours — release the OS immediately
            return
        }
        backgroundCompletionHandler = completionHandler
        session // ensure the session exists to receive the events
    }

    private fun reenqueue(assetId: String) {
        val eventId = eventIdProvider() ?: return
        val fileUrl = store.pendingFileUrl(assetId) ?: return // already DONE/pruned — nothing to retry
        val url = NSURL.URLWithString("${host.trimEnd('/')}/event/$eventId/file/$assetId.manifest.json") ?: return
        val request = NSMutableURLRequest(uRL = url)
        request.setHTTPMethod("PUT")
        request.setValue("application/json", forHTTPHeaderField = "Content-Type")
        val task = session.uploadTaskWithRequest(request, fromFile = fileUrl)
        task.taskDescription = assetId
        task.earliestBeginDate = NSDate.dateWithTimeIntervalSinceNow(BACKOFF_SECONDS) // simple fixed backoff
        task.resume()
    }

    private inner class Delegate : NSObject(), NSURLSessionTaskDelegateProtocol {
        override fun URLSession(
            session: NSURLSession,
            task: NSURLSessionTask,
            didCompleteWithError: NSError?,
        ) {
            val assetId = task.taskDescription ?: return
            val status = (task.response as? NSHTTPURLResponse)?.statusCode
            val ok = didCompleteWithError == null && status != null && status in 200L..299L
            if (ok) {
                store.markDone(assetId)
                log.i { "manifest $assetId DONE" }
            } else {
                log.w { "manifest $assetId failed (err=${didCompleteWithError?.localizedDescription}, status=$status) — re-enqueuing" }
                reenqueue(assetId)
            }
        }

        override fun URLSessionDidFinishEventsForBackgroundURLSession(session: NSURLSession) {
            backgroundCompletionHandler?.invoke()
            backgroundCompletionHandler = null
        }
    }

    private companion object {
        const val BACKOFF_SECONDS: Double = 30.0
    }
}
