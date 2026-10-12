package app.snapsync.ios.upload

import app.snapsync.logging.invocation
import app.snapsync.ports.ChangeOutcome
import app.snapsync.ports.CreateOutcome
import app.snapsync.ports.GalleryResourceHandle
import app.snapsync.ports.OsUploadJob
import app.snapsync.ports.OsUploadJobHandle
import app.snapsync.ports.OsUploadQueue
import app.snapsync.ports.UploadTarget
import co.touchlab.kermit.Logger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURL

/**
 * The PhotoKit (iOS ≥26.1) [OsUploadQueue]: the OS-owned upload-job queue the upload extension runs — fetch a set,
 * create, retry, acknowledge. Thin: it presents jobs as facts and performs what it is asked; which ledger row a job
 * belongs to, what a terminal job means and which offered retry is a key's are the upload services' (`OsQueueTransfer`).
 * A `PHAssetResourceUploadJob` has no public initializer and only ever arrives from a fetch, so every OS effect goes
 * through the [UploadJobApi] seam: the upload extension's contract run records every call and iOS's answer on a device,
 * and every CI build replays that recording against this class. What a replay cannot cover is [SystemUploadJobApi]'s
 * conversion of a real job into facts.
 *
 * It cancels nothing: a leave deregisters the extension, which removes every job the record held.
 *
 * Measured (SE2, iOS 26.6, 2026-09-22), and asserted by no clause: jobs created under a full grant survive a round
 * trip through `.limited` — a withheld call under the partial grant is shown none of them, and the first call after
 * full access returns presents them all as succeeded. See changes/archive/2026-09-22-both-uploaders-active.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosPhotoKitUploadPlatform internal constructor(
    private val log: Logger,
    // Every operating-system effect goes through this seam, so the upload extension's contract run can record it and
    // CI can replay it (`docs/architecture.md`). Production never passes one.
    private val api: UploadJobApi,
) : OsUploadQueue {

    /** The production adapter, over the real PhotoKit calls. */
    constructor(log: Logger) : this(log, SystemUploadJobApi(log))

    override suspend fun retryOffered(): List<OsUploadJob> = fetch(JobSet.RETRY)

    override suspend fun terminals(): List<OsUploadJob> = fetch(JobSet.ACKNOWLEDGE)

    private fun fetch(set: JobSet): List<OsUploadJob> =
        log.invocation("photokit.jobs", params = "set=$set", result = { "${it.size} job(s)" }) {
            api.fetch(set).map(::jobOf)
        }

    override suspend fun create(resource: GalleryResourceHandle, target: UploadTarget): CreateOutcome =
        log.invocation("photokit.create", params = "to=${target.url}", result = { "$it" }) {
            val url = NSURL.URLWithString(target.url) ?: return@invocation CreateOutcome.FAILED
            val answer = api.create(uploadUrlRequest(url, target), resource)
            // A refusal that names no code is still a refusal: it maps to FAILED, never to CREATED.
            createResultFor(if (answer.ok) null else answer.code ?: UNCODED_REFUSAL).also {
                if (it == CreateOutcome.FAILED) log.w { "create refused: code=${answer.code} ${answer.description}" }
            }
        }

    override suspend fun retry(job: OsUploadJob, target: UploadTarget): ChangeOutcome {
        val facts = (job.handle as? PhotoKitJob)?.facts ?: return ChangeOutcome.Refused(null, "not a PhotoKit job")
        val url = NSURL.URLWithString(target.url) ?: return ChangeOutcome.Refused(null, "not a URL")
        return api.retry(facts, uploadUrlRequest(url, target)).toOutcome()
    }

    override suspend fun acknowledge(job: OsUploadJob): ChangeOutcome {
        val facts = (job.handle as? PhotoKitJob)?.facts ?: return ChangeOutcome.Refused(null, "not a PhotoKit job")
        return api.acknowledge(facts).toOutcome()
    }

    private fun jobOf(facts: UploadJobFacts) = OsUploadJob(
        handle = PhotoKitJob(facts),
        destinationUrl = facts.destinationUrl,
        contentType = facts.contentTypeHeader,
        state = facts.state,
        failure = facts.error,
        resource = facts.resource,
    )

    private fun ChangeAnswer.toOutcome(): ChangeOutcome =
        if (ok) ChangeOutcome.Applied else ChangeOutcome.Refused(code, description)
}

/** A presented PhotoKit job, as the port hands it back: the facts it was read as. */
private class PhotoKitJob(val facts: UploadJobFacts) : OsUploadJobHandle

/** The code a refusal without an `NSError` maps to — never `null`, which [createResultFor] reads as created. */
private const val UNCODED_REFUSAL = -1L
