package app.snapsync.rig

import app.snapsync.contract.extension.RUN_REQUEST_FILE
import app.snapsync.contract.extension.RUN_RESULT_FILE
import app.snapsync.contract.extension.clearLanded
import app.snapsync.contract.extension.contractRunFile
import app.snapsync.contract.extension.deleteContractRunFile
import app.snapsync.contract.extension.extensionContracts
import app.snapsync.contract.extension.readContractRunFile
import app.snapsync.contract.extension.writeContractRunFile
import app.snapsync.contracts.CONTRACT_REFUSED
import app.snapsync.contracts.CONTRACT_TIMEOUT
import app.snapsync.contracts.Host
import app.snapsync.contracts.InAppContract
import app.snapsync.gallery.currentPhotoPermission
import app.snapsync.model.PermissionStatus
import app.snapsync.model.RegistrationOutcome
import app.snapsync.ports.UploadExtensionRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSProcessInfo
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * How long the app waits for the extension's answer. A run is sized to finish well inside one `process()` call
 * (about 60 s); the rest covers the OS's delay in invoking the extension after re-registration (~1 s, measured).
 */
private val EXTENSION_RUN_BOUND: Duration = 90.seconds

/**
 * The app-side entries of the contracts that record inside the upload extension — one per name in the extension's
 * own registry, on [Host.IOS_DEVICE_PHOTOKIT_EXT]. `POST /contract/<name>?host=IOS_DEVICE_PHOTOKIT_EXT` selects one.
 *
 * [membershipRefusal] is the rig's no-membership precondition; [registry] is the app's registration port, or
 * `null` on an OS below the one that carries the extension.
 */
fun extensionContractEntries(
    membershipRefusal: () -> String?,
    registry: () -> UploadExtensionRegistry?,
): List<InAppContract> = extensionContracts().map { entry ->
    InAppContract(entry.name, Host.IOS_DEVICE_PHOTOKIT_EXT) { runInExtension(entry.name, membershipRefusal, registry) }
}

/**
 * Requests a run of [name] inside the upload extension and waits for its answer.
 *
 * Preconditions first, in order (capability `port-contracts`): a full photo grant, which only a person can set;
 * no membership, because re-registering wipes every in-flight upload job; then — automatically — the
 * re-registration itself, disable then enable, which empties the job queue and makes the OS invoke the
 * extension. The membership check comes before it for that reason.
 */
private fun runInExtension(
    name: String,
    membershipRefusal: () -> String?,
    registry: () -> UploadExtensionRegistry?,
): String {
    val refused = refusalFor(membershipRefusal, registry) ?: requestRun(name, registry)
    return if (refused != null) "$CONTRACT_REFUSED$refused\n" else awaitResult()
}

/** Writes the run request and re-registers the extension; answers why that failed, or `null`. */
private fun requestRun(name: String, registry: () -> UploadExtensionRegistry?): String? {
    val requestPath = contractRunFile(RUN_REQUEST_FILE) ?: return "this process has no App Group container"
    contractRunFile(RUN_RESULT_FILE)?.let(::deleteContractRunFile)
    clearLanded()
    if (!writeContractRunFile(requestPath, name)) return "could not write the run request"
    val enabled = runBlocking {
        registry()?.setEnabled(false)
        registry()?.setEnabled(true)
    }
    if (enabled == RegistrationOutcome.Applied(enabling = true)) return null
    deleteContractRunFile(requestPath)
    return "re-registering the extension did not take (${enabled?.message}), so the OS will not invoke it"
}

/** Waits for the extension's result, or answers a timeout naming which half never happened. */
private fun awaitResult(): String {
    val requestPath = contractRunFile(RUN_REQUEST_FILE).orEmpty()
    val resultPath = contractRunFile(RUN_RESULT_FILE).orEmpty()
    val started = TimeSource.Monotonic.markNow()
    while (started.elapsedNow() < EXTENSION_RUN_BOUND) {
        val body = readContractRunFile(resultPath)
        if (body != null) {
            deleteContractRunFile(resultPath)
            return body
        }
        runBlocking { delay(1.seconds) }
    }
    val neverInvoked = readContractRunFile(requestPath) != null
    deleteContractRunFile(requestPath)
    val why = if (neverInvoked) {
        "the OS did not invoke the extension within $EXTENSION_RUN_BOUND — it may be backing off after a killed call (6–11 min)"
    } else {
        "the extension took the request but wrote no result within $EXTENSION_RUN_BOUND — the run was probably killed at the ~60 s budget"
    }
    return "$CONTRACT_TIMEOUT$why\n"
}

private fun refusalFor(membershipRefusal: () -> String?, registry: () -> UploadExtensionRegistry?): String? = when {
    NSProcessInfo.processInfo.environment["SIMULATOR_DEVICE_NAME"] != null ->
        "this process is a simulator app; the upload extension is recorded on a device"
    currentPhotoPermission() != PermissionStatus.GRANTED ->
        "the upload-job contract records under a full photo grant; this process holds ${currentPhotoPermission()}"
    registry() == null -> "this OS carries no upload extension (below iOS 26.1)"
    else -> membershipRefusal()
}
