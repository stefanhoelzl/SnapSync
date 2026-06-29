package app.snapsync.ios.upload

import app.snapsync.engine.LEDGER_APP_GROUP
import app.snapsync.gallery.MANIFEST_URLSESSION_IDENTIFIER
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionTask
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import kotlin.coroutines.resume

/**
 * The **extension** end of the manifest background `URLSession` (capability `asset-manifest`): it only
 * *starts* transfers; their completion is delivered to the **containing app**, which the system
 * relaunches (the app constructs the same-identifier session to adopt the events). The session is
 * built with [MANIFEST_URLSESSION_IDENTIFIER] and `sharedContainerIdentifier = LEDGER_APP_GROUP` so
 * the app can adopt it. Delegate-less here — the extension never handles completions.
 *
 * Wiring-only and untestable (background `URLSession`, device-only); verified on device.
 */
@OptIn(ExperimentalForeignApi::class)
class ManifestUploadSession {

    private val session: NSURLSession by lazy {
        val config = NSURLSessionConfiguration
            .backgroundSessionConfigurationWithIdentifier(MANIFEST_URLSESSION_IDENTIFIER)
        config.sharedContainerIdentifier = LEDGER_APP_GROUP
        NSURLSession.sessionWithConfiguration(config, delegate = null, delegateQueue = null)
    }

    /**
     * Enqueue exactly one background `PUT` of the manifest file at [fileUrl] to
     * `<host>/event/<eventId>/file/<assetId>.manifest.json` (`Content-Type: application/json`), tagging
     * the task with [assetId] (`taskDescription`) so the app maps the completion back to its asset.
     */
    fun enqueue(fileUrl: NSURL, host: String, eventId: String, assetId: String) {
        val url = NSURL.URLWithString(
            "${host.trimEnd('/')}/event/$eventId/file/$assetId.manifest.json",
        ) ?: return
        val request = NSMutableURLRequest(uRL = url)
        request.setHTTPMethod("PUT")
        request.setValue("application/json", forHTTPHeaderField = "Content-Type")
        val task = session.uploadTaskWithRequest(request, fromFile = fileUrl)
        task.taskDescription = assetId
        task.resume()
    }

    /** The `assetId`s (task descriptions) of the session's currently in-flight upload tasks. */
    suspend fun inFlightAssetIds(): Set<String> = suspendCancellableCoroutine { cont ->
        session.getTasksWithCompletionHandler { _, uploadTasks, _ ->
            val ids = uploadTasks.orEmpty()
                .mapNotNull { (it as? NSURLSessionTask)?.taskDescription }
                .toSet()
            cont.resume(ids)
        }
    }
}
