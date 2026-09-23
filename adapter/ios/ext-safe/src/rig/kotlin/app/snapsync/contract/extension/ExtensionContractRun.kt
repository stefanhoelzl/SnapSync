package app.snapsync.contract.extension

import app.snapsync.contracts.CONTRACT_REFUSED
import app.snapsync.contracts.CONTRACT_TIMEOUT
import app.snapsync.contracts.BackgroundTransferContract
import app.snapsync.contracts.Host
import app.snapsync.contracts.Recording
import app.snapsync.contracts.InAppContract
import app.snapsync.gallery.currentPhotoPermission
import app.snapsync.model.PermissionStatus
import app.snapsync.model.RegistrationOutcome
import app.snapsync.ports.UploadExtensionRegistry
import app.snapsync.contracts.runEntry
import platform.Foundation.NSThread
import platform.Foundation.NSProcessInfo
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/** How often the app looks for the extension's answer. */
private const val POLL_SECONDS: Double = 2.0

/** How long one call takes to come: the OS calls again five minutes after `PROCESSING` (measured), plus margin. */
private val PER_CALL: Duration = 6.minutes

/**
 * The app-side entries of the contracts that record inside the upload extension, on [Host.IOS_DEVICE_PHOTOKIT_EXT].
 * `POST /contract/<name>?host=IOS_DEVICE_PHOTOKIT_EXT` selects one.
 *
 * [membershipRefusal] is the rig's no-membership precondition; [registry] is the app's registration port, or `null` on
 * an OS below the one that carries the extension.
 */
fun extensionContractEntries(
    membershipRefusal: () -> String?,
    registry: () -> UploadExtensionRegistry?,
): List<InAppContract> = listOf(
    InAppContract(BackgroundTransferContract.name, Host.IOS_DEVICE_PHOTOKIT_EXT) {
        runInExtension(BackgroundTransferContract.name, membershipRefusal, registry)
    },
)

/**
 * Records [contract] inside the upload extension: one run per clause, alone in the OS queue — the real adapter
 * acknowledges every job it is presented, so a second clause's jobs would not survive the first's drain. Each run
 * re-registers the extension (which empties the queue and invokes it), then waits for the run's calls, five minutes
 * apart. The recordings are merged into one file. A full run takes about twenty minutes: call it in the background.
 *
 * Preconditions first, in order (capability `port-contracts`): a full photo grant, which only a person can set; no
 * membership, because re-registering wipes every in-flight upload job.
 */
private fun runInExtension(
    contract: String,
    membershipRefusal: () -> String?,
    registry: () -> UploadExtensionRegistry?,
): String {
    val refused = refusalFor(membershipRefusal, registry)
    if (refused != null) return "$CONTRACT_REFUSED$refused\n"
    val bodies = mutableListOf<String>()
    for ((clauseId, calls) in extensionRunPlan()) {
        val requestFailed = requestRun("$contract $clauseId", registry)
        if (requestFailed != null) return "$CONTRACT_REFUSED$requestFailed\n"
        val body = awaitResult(clauseId, PER_CALL * calls)
        if (body.startsWith(CONTRACT_REFUSED) || body.startsWith(CONTRACT_TIMEOUT)) return body
        bodies += body
    }
    return merge(bodies)
}

/** Writes the run request and re-registers the extension; answers why that failed, or `null`. */
private fun requestRun(request: String, registry: () -> UploadExtensionRegistry?): String? {
    val requestPath = contractRunFile(RUN_REQUEST_FILE) ?: return "this process has no App Group container"
    contractRunFile(RUN_RESULT_FILE)?.let(::deleteContractRunFile)
    clearRunProgress()
    clearLanded()
    if (!writeContractRunFile(requestPath, request)) return "could not write the run request"
    var enabled: RegistrationOutcome? = null
    runEntry {
        registry()?.setEnabled(false)
        enabled = registry()?.setEnabled(true)
    }
    if (enabled == RegistrationOutcome.Applied(enabling = true)) return null
    deleteContractRunFile(requestPath)
    return "re-registering the extension did not take (${enabled?.message}), so the OS will not invoke it"
}

/** Waits up to [bound] for the run's result, or answers a timeout naming where it stopped. */
private fun awaitResult(clauseId: String, bound: Duration): String {
    val resultPath = contractRunFile(RUN_RESULT_FILE).orEmpty()
    val started = TimeSource.Monotonic.markNow()
    while (started.elapsedNow() < bound) {
        val body = readContractRunFile(resultPath)
        if (body != null) {
            deleteContractRunFile(resultPath)
            return body
        }
        NSThread.sleepForTimeInterval(POLL_SECONDS)
    }
    val call = contractRunFile(RUN_CALL_FILE)?.let(::readContractRunFile)?.trim() ?: "1"
    val pending = contractRunFile(RUN_REQUEST_FILE)?.let(::readContractRunFile) != null
    contractRunFile(RUN_REQUEST_FILE)?.let(::deleteContractRunFile)
    clearRunProgress()
    val why = if (pending) {
        "$clauseId: the OS did not make call $call within $bound — it may be backing off after a killed call (6–11 min)"
    } else {
        "$clauseId: call $call was taken but wrote nothing — probably killed at the ~60 s budget"
    }
    return "$CONTRACT_TIMEOUT$why\n"
}

/** One recording from the runs': the first run's provenance, every run's live outcomes, every run's blocks. */
private fun merge(bodies: List<String>): String {
    val runs = bodies.map(Recording::parse)
    val provenance = runs.first().header.filterNot { it.first.startsWith("live ") }
    val live = runs.flatMap { run -> run.header.filter { it.first.startsWith("live ") } }
    return Recording(provenance + live, runs.fold(emptyMap()) { all, run -> all + run.blocks }).render()
}

private fun refusalFor(membershipRefusal: () -> String?, registry: () -> UploadExtensionRegistry?): String? = when {
    NSProcessInfo.processInfo.environment["SIMULATOR_DEVICE_NAME"] != null ->
        "this process is a simulator app; the upload extension is recorded on a device"
    currentPhotoPermission() != PermissionStatus.GRANTED ->
        "the upload-job contract records under a full photo grant; this process holds ${currentPhotoPermission()}"
    registry() == null -> "this OS carries no upload extension (below iOS 26.1)"
    else -> membershipRefusal()
}
