@file:OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)

package app.snapsync.contract.extension

import app.snapsync.contracts.CONTRACT_REFUSED
import app.snapsync.contracts.BackgroundTransferContract
import app.snapsync.ios.upload.Step
import app.snapsync.ios.upload.callsFor
import app.snapsync.ios.upload.extensionTransferClauses
import app.snapsync.ios.upload.transferRunStep
import app.snapsync.logging.appGroupDirectory
import app.snapsync.model.CycleResult
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
 * A port-contract run inside the upload extension (`docs/architecture.md`, "The device run is reached
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

/** How many calls the current staged run has made — kept between the extension's processes. */
const val RUN_CALL_FILE: String = "contract-run-call"

/** What the current staged run has recorded so far — kept between the extension's processes. */
const val RUN_TAPE_FILE: String = "contract-run-tape"

/**
 * The runs the extension records, in order: one per presented-state clause of the upload-job contract, each with the
 * number of operating-system calls it takes (its preparation calls, then the clause's own). A run is requested as
 * `<Contract> <CLAUSE_ID>`.
 */
fun extensionRunPlan(): List<Pair<String, Int>> =
    extensionTransferClauses().map { id -> id to callsFor(BackgroundTransferContract.clauses.first { it.id == id }.state) }

/**
 * [core] with one difference: when the app's rig has requested a contract run, `process()` performs the run's next
 * call INSTEAD of the upload cycle.
 *
 * A run spans calls — a job the extension creates is uploaded only after the call returns (measured, SE2, iOS 26.6) —
 * so a call that prepared answers `PROCESSING`, which brings the next call five minutes later (measured), and keeps
 * the request, the call count and the partial recording in the App Group; the run's last call writes the recording
 * and answers `COMPLETED`. The request is deleted BEFORE each call's work and rewritten only after it, so a call the OS
 * kills — about 60 s in, with no notice — ends the run rather than repeating.
 */
fun contractRunningEntries(core: ExtensionEntries): ExtensionEntries = object : ExtensionEntries by core {
    private val log = Logger.withTag("ExtensionContract")

    override suspend fun process(): CycleResult {
        val requestPath = contractRunFile(RUN_REQUEST_FILE) ?: return core.process()
        val request = readContractRunFile(requestPath)?.trim() ?: return core.process()
        deleteContractRunFile(requestPath)
        val call = contractRunFile(RUN_CALL_FILE)?.let(::readContractRunFile)?.trim()?.toIntOrNull() ?: 1
        val tape = contractRunFile(RUN_TAPE_FILE)?.let(::readContractRunFile)
        log.i { "[contract] $request: call $call, in place of the upload cycle" }
        val step = if (request.substringBefore(' ') == BackgroundTransferContract.name) {
            transferRunStep(request.substringAfter(' '), call, tape)
        } else {
            Step.Done("${CONTRACT_REFUSED}no contract named '${request.substringBefore(' ')}' records inside the upload extension\n")
        }
        return when (step) {
            is Step.Continue -> {
                contractRunFile(RUN_TAPE_FILE)?.let { writeContractRunFile(it, step.tape) }
                contractRunFile(RUN_CALL_FILE)?.let { writeContractRunFile(it, "${call + 1}") }
                writeContractRunFile(requestPath, request)
                log.i { "[contract] $request: call $call prepared; asking for the next" }
                CycleResult.PROCESSING
            }
            is Step.Done -> {
                clearRunProgress()
                val written = contractRunFile(RUN_RESULT_FILE)?.let { writeContractRunFile(it, step.body) } ?: false
                log.i { "[contract] $request: finished; result written=$written" }
                CycleResult.COMPLETED
            }
        }
    }
}

/** Forgets a staged run's progress — before a new request, and after a run ends. */
fun clearRunProgress() {
    contractRunFile(RUN_CALL_FILE)?.let(::deleteContractRunFile)
    contractRunFile(RUN_TAPE_FILE)?.let(::deleteContractRunFile)
}
