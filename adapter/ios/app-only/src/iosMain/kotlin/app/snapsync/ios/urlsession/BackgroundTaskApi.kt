@file:OptIn(ExperimentalForeignApi::class)

package app.snapsync.ios.urlsession

import app.snapsync.objc.checkedObjC
import app.snapsync.objc.objcBoundary
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.BackgroundTasks.BGTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import kotlin.coroutines.resume

/**
 * **The operating-system boundary of [IosBackgroundScheduler]**: the `BGTaskScheduler` calls it makes, and the one
 * read a clause observes the system's queue through (capability `port-contracts`, "Hosts CI cannot reach are
 * recorded at the operating-system boundary and replayed on every build").
 *
 * It sits below every decision the adapter makes — the request it builds, how it reports a refusal — so a replay
 * runs the CURRENT adapter against what iOS answered on a device, and a change in what the adapter asks iOS reads
 * as a divergence rather than a stale green. Same shape as `KeychainApi`, for the same reason.
 *
 * `internal`: the recording and replaying implementations live in this module's rig-gated source set and its tests.
 */
internal interface BackgroundTaskApi {
    /** Submit [request]: success when the system accepted it, otherwise its refusal as an `ObjCFailure`. */
    fun submit(request: BGProcessingTaskRequest): Result<Unit>

    fun cancel(identifier: String)

    /** The identifiers of every request the system holds for this app, as `getPendingTaskRequests` answers. */
    suspend fun pendingIdentifiers(): List<String>
}

/** The real `BGTaskScheduler`. The only implementation a production build contains. */
internal object SystemBackgroundTaskApi : BackgroundTaskApi {
    private val log = Logger.withTag("BackgroundTaskApi")

    override fun submit(request: BGProcessingTaskRequest): Result<Unit> =
        checkedObjC("submitTaskRequest(${request.identifier})") { BGTaskScheduler.sharedScheduler.submitTaskRequest(request, it) }

    override fun cancel(identifier: String) = BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier(identifier)

    override suspend fun pendingIdentifiers(): List<String> = suspendCancellableCoroutine { cont ->
        BGTaskScheduler.sharedScheduler.getPendingTaskRequestsWithCompletionHandler { requests ->
            objcBoundary(log, "pendingTaskRequests.completion") {
                cont.resume(requests.orEmpty().mapNotNull { (it as? BGTaskRequest)?.identifier })
            }
        }
    }
}
