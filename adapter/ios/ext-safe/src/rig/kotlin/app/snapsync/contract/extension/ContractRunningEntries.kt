@file:OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)

package app.snapsync.contract.extension

import app.snapsync.contracts.CONTRACT_REFUSED
import app.snapsync.contracts.InAppContract
import app.snapsync.ios.upload.extensionTransferContract
import app.snapsync.logging.appGroupDirectory
import app.snapsync.ports.CycleResult
import app.snapsync.ports.ExtensionEntries
import co.touchlab.kermit.Logger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

/*
 * A port-contract run inside the upload extension (capability `port-contracts`, "The device run is reached
 * through the rig and contained at compile time"). The rig cannot reach the extension, and the extension lives
 * only as long as the operating system's `process()` call, so the run is requested and answered through the
 * App Group both processes share:
 *
 *  1. the app's rig writes [RUN_REQUEST_FILE] naming the contract, then makes the OS invoke the extension;
 *  2. the extension's `process()` — [contractRunningEntries] — finds the request, deletes it, runs the contract
 *     in place of the upload cycle, and writes the recording to [RUN_RESULT_FILE];
 *  3. the rig reads the result back and answers it.
 *
 * Compiled into this module's `iosMain` only under `-Psnapsync.rig=true` (and into `iosTest` otherwise), so a
 * production extension contains none of it.
 */

/** Names the contract the next `process()` call runs in place of its cycle. Written by the app's rig. */
const val RUN_REQUEST_FILE: String = "contract-run-request"

/** The recording (or refusal) a requested run answered. Written by the extension, read by the app's rig. */
const val RUN_RESULT_FILE: String = "contract-run-result"

/** The App Group path of [name], or `null` where this process has no App Group. */
fun contractRunFile(name: String): String? = appGroupDirectory()?.let { "$it/$name" }

/** [text] written atomically to [path]; `false` when the write failed. */
fun writeContractRunFile(path: String, text: String): Boolean =
    NSString.create(string = text).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)

/** The content of [path], or `null` when it does not exist or cannot be read. */
fun readContractRunFile(path: String): String? =
    NSString.stringWithContentsOfFile(path, encoding = NSUTF8StringEncoding, error = null)

/** Deletes [path]; absence is not an error. */
fun deleteContractRunFile(path: String) {
    NSFileManager.defaultManager.removeItemAtPath(path, error = null)
}

/** Where the upload receiver keeps what landed, one file per fixture route, holding its content type. */
private const val LANDED_DIRECTORY: String = "contract-landed"

private fun landedFile(route: String): String? =
    contractRunFile(LANDED_DIRECTORY)?.let { "$it/" + route.trim('/').replace('/', '_') }

/** Keeps that a `PUT` to [route] landed with [contentType]. Called by the app's upload receiver. */
fun recordLanded(route: String, contentType: String?) {
    val dir = contractRunFile(LANDED_DIRECTORY) ?: return
    NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
    landedFile(route)?.let { writeContractRunFile(it, contentType.orEmpty()) }
}

/** What landed at [route]: its content type (empty when none was sent), or `null` when nothing has. */
fun landedAt(route: String): String? = landedFile(route)?.let(::readContractRunFile)

/** Forgets everything that landed — before a run, so a route never reads a previous run's object. */
fun clearLanded() {
    contractRunFile(LANDED_DIRECTORY)?.let(::deleteContractRunFile)
}

/**
 * The contracts that record inside the upload extension, by name — the registry a requested run is resolved
 * against. Each answers the recording to commit verbatim, as an in-app device run does.
 */
fun extensionContracts(): List<InAppContract> = listOf(extensionTransferContract())

/**
 * [core] with one difference: when the app's rig has requested a contract run, `process()` runs that contract
 * INSTEAD of the upload cycle and answers `COMPLETED`.
 *
 * `COMPLETED`, never `PROCESSING`: `PROCESSING` asks the OS for another call, which comes five minutes later
 * (measured, SE2, iOS 26.6), and nothing about a finished run needs one. The request is deleted BEFORE the run,
 * so a run the OS kills — about 60 s into a call, with no notice — is not repeated by the next call.
 */
fun contractRunningEntries(
    core: ExtensionEntries,
    contracts: () -> List<InAppContract> = ::extensionContracts,
): ExtensionEntries = object : ExtensionEntries by core {
    private val log = Logger.withTag("ExtensionContract")

    override suspend fun process(): CycleResult {
        val requestPath = contractRunFile(RUN_REQUEST_FILE) ?: return core.process()
        val requested = readContractRunFile(requestPath)?.trim() ?: return core.process()
        deleteContractRunFile(requestPath)
        log.i { "[contract] running $requested in place of the upload cycle" }
        val body = contracts().firstOrNull { it.name == requested }?.run?.invoke(emptyMap())
            ?: "${CONTRACT_REFUSED}no contract named '$requested' records inside the upload extension\n"
        val written = contractRunFile(RUN_RESULT_FILE)?.let { writeContractRunFile(it, body) } ?: false
        log.i { "[contract] $requested finished; result written=$written" }
        return CycleResult.COMPLETED
    }
}
