package app.snapsync.ios.upload

import app.snapsync.logging.invocation
import app.snapsync.model.ChangeOutcome
import app.snapsync.model.UploadCreateOutcome
import app.snapsync.model.UploadJob
import app.snapsync.model.UploadJobSet
import app.snapsync.model.UploadSource
import app.snapsync.model.UploadSourceKind
import app.snapsync.model.UploadTarget
import app.snapsync.ports.Upload
import app.snapsync.ports.UploadHandlers
import co.touchlab.kermit.Logger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURL

/**
 * The PhotoKit (iOS ≥26.1) [Upload]: the OS-owned upload-job queue the upload extension runs — fetch a set, create,
 * retry, acknowledge. Thin since phase 11f: it presents jobs as facts and performs what it is asked; which ledger row a
 * job belongs to, what a terminal job means and which offered retry is a key's are the upload services'
 * (`UploadTransferService`). A `PHAssetResourceUploadJob` has no public initializer and only ever arrives from a fetch,
 * so every OS effect goes through the [UploadJobApi] seam: the upload extension's contract run records every call and
 * iOS's answer on a device, and every CI build replays that recording against this class. What a replay cannot cover
 * is [SystemUploadJobApi]'s conversion of a real job into facts.
 *
 * It raises no events: the OS presents terminal jobs when asked ([jobs]), so [listen] registers nothing that fires.
 * It cancels nothing either — a leave deregisters the extension, which removes every job the record held.
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
) : Upload {

    /** The production adapter, over the real PhotoKit calls. */
    constructor(log: Logger) : this(log, SystemUploadJobApi(log))

    override val accepts: UploadSourceKind = UploadSourceKind.RESOURCE

    override fun listen(handlers: UploadHandlers) = Unit

    override suspend fun jobs(set: UploadJobSet): List<UploadJob> =
        log.invocation("photokit.jobs", params = "set=$set", result = { "${it.size} job(s)" }) {
            when (set) {
                UploadJobSet.RETRY_OFFERED -> api.fetch(JobSet.RETRY).map(::jobOf)
                UploadJobSet.TERMINAL -> api.fetch(JobSet.ACKNOWLEDGE).map(::jobOf)
                UploadJobSet.IN_FLIGHT -> emptyList()
            }
        }

    override suspend fun create(source: UploadSource, target: UploadTarget, tag: String): UploadCreateOutcome =
        log.invocation("photokit.create", params = "tag=$tag", result = { "$it" }) {
            val resource = (source as? UploadSource.Resource)?.handle ?: run {
                log.w { "create: PhotoKit uploads a resource, not a file — not creating" }
                return@invocation UploadCreateOutcome.FAILED
            }
            val url = NSURL.URLWithString(target.url) ?: return@invocation UploadCreateOutcome.FAILED
            val answer = api.create(uploadUrlRequest(url, target), resource)
            // A refusal that names no code is still a refusal: it maps to FAILED, never to CREATED.
            createResultFor(if (answer.ok) null else answer.code ?: UNCODED_REFUSAL).also {
                if (it == UploadCreateOutcome.FAILED) log.w { "create refused for $tag: code=${answer.code} ${answer.description}" }
            }
        }

    override suspend fun retry(job: UploadJob, target: UploadTarget): ChangeOutcome {
        val facts = job.handle as? UploadJobFacts ?: return ChangeOutcome.Refused(null, "not a PhotoKit job")
        val url = NSURL.URLWithString(target.url) ?: return ChangeOutcome.Refused(null, "not a URL")
        return api.retry(facts, uploadUrlRequest(url, target)).toOutcome()
    }

    override suspend fun acknowledge(job: UploadJob): ChangeOutcome {
        val facts = job.handle as? UploadJobFacts ?: return ChangeOutcome.Refused(null, "not a PhotoKit job")
        return api.acknowledge(facts).toOutcome()
    }

    /** PhotoKit's jobs are not cancelled one by one: a leave deregisters the extension, removing every job. */
    override suspend fun cancel(job: UploadJob): ChangeOutcome =
        ChangeOutcome.Refused(null, "PhotoKit upload jobs are removed by deregistering, not cancelled")

    private fun jobOf(facts: UploadJobFacts) = UploadJob(
        handle = facts,
        tag = null,
        destinationPath = facts.destinationPath,
        contentType = facts.contentTypeHeader,
        state = facts.state,
        error = facts.error,
        source = facts.resource?.let(UploadSource::Resource),
    )

    private fun ChangeAnswer.toOutcome(): ChangeOutcome =
        if (ok) ChangeOutcome.Applied else ChangeOutcome.Refused(code, description)
}

/** The code a refusal without an `NSError` maps to — never `null`, which [createResultFor] reads as created. */
private const val UNCODED_REFUSAL = -1L
