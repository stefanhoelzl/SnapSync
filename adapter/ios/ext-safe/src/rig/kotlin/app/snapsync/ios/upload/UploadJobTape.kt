@file:OptIn(ExperimentalForeignApi::class)

package app.snapsync.ios.upload

import app.snapsync.contracts.FixtureObjects
import app.snapsync.contracts.Landed
import app.snapsync.contracts.Recorder
import app.snapsync.contracts.Replayer
import app.snapsync.gallery.PhotoKitResource
import app.snapsync.ports.GalleryResourceHandle
import app.snapsync.ports.TransferFailure
import app.snapsync.ports.UploadJobState
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.HTTPMethod
import platform.Foundation.NSURLRequest
import platform.Foundation.allHTTPHeaderFields

/*
 * The upload-job seam and the fixture reads, recorded inside the upload extension and replayed on every CI build
 * (`docs/architecture.md`). One file, so the recorder and the replayer cannot spell a call differently.
 *
 * Every input a clause's poll reads is here — the job API's answers and what the upload receiver says landed — so a
 * replay answers each poll in recorded order and stops where the device stopped.
 *
 * Compiled into this module's `iosMain` only under `-Psnapsync.rig=true`, and into `iosTest` otherwise.
 */

/** Stands for a library photo's resource on replay, where no `PHAssetResource` exists. */
internal object ReplayPhoto : GalleryResourceHandle

/** Stands, on replay, for a resource the recording called another kind. */
private object ReplayOther : GalleryResourceHandle

private const val NONE = "-"

private fun String?.orNone() = this ?: NONE
private fun String.noneAsNull() = takeIf { it != NONE }

private fun NSURLRequest.render(): String {
    val headers = allHTTPHeaderFields.orEmpty().entries.map { "${it.key}:${it.value}" }.sorted().joinToString(",")
    return "to=${URL?.absoluteString.orNone()} method=${HTTPMethod().orNone()} headers=${headers.ifEmpty { NONE }}"
}

private fun resourceKind(resource: Any?): String = when (resource) {
    null -> NONE
    is PhotoKitResource, ReplayPhoto -> "photo"
    else -> "other"
}

private fun UploadJobFacts.render(): String =
    "url=${destinationUrl.orNone()} ct=${contentTypeHeader.orNone()} state=${state.name} " +
        "err=${error?.detail.orNone()} res=${resourceKind(resource)} type=${resourceType.orNone()}"

private fun factsCall(set: JobSet) = "fetch(${set.name})"
private fun ackCall(job: UploadJobFacts) = "acknowledge(url=${job.destinationUrl.orNone()})"
private fun retryCall(
    job: UploadJobFacts,
    to: NSURLRequest,
) = "retry(url=${job.destinationUrl.orNone()} ${to.render()})"
private fun createCall(
    to: NSURLRequest,
    resource: GalleryResourceHandle,
) = "create(${to.render()} resource=${resourceKind(resource)})"
private fun landedCall(route: String) = "landed($route)"

/** The answer's description is LAST, so it may hold spaces. */
private fun ChangeAnswer.render() = "ok=$ok code=${code?.toString().orNone()} desc=${description.orNone()}"

private fun parseChange(rendered: String): ChangeAnswer {
    val desc = rendered.substringAfter(" desc=")
    val head = rendered.substringBefore(
        " desc=",
    ).split(' ').associate { it.substringBefore('=') to it.substringAfter('=') }
    return ChangeAnswer(
        ok = head.getValue("ok").toBooleanStrict(),
        code = head.getValue("code").noneAsNull()?.toLong(),
        description = desc.noneAsNull(),
    )
}

/** A fetch's answer: `n=<count>` then one `[…]` per job. */
private fun List<UploadJobFacts>.render(): String = "n=$size" + joinToString("") { " [${it.render()}]" }

private fun parseFacts(rendered: String): List<UploadJobFacts> =
    JOB.findAll(rendered).mapIndexed { i, m ->
        val f = m.groupValues[1].split(' ').associate { it.substringBefore('=') to it.substringAfter('=') }
        val url = f.getValue("url").noneAsNull()
        UploadJobFacts(
            handle = "job#$i:$url",
            destinationUrl = url,
            contentTypeHeader = f.getValue("ct").noneAsNull(),
            state = UploadJobState.valueOf(f.getValue("state")),
            error = f.getValue("err").noneAsNull()?.let { TransferFailure(it) },
            resource = when (f.getValue("res")) {
                "photo" -> ReplayPhoto
                NONE -> null
                else -> ReplayOther
            },
            resourceType = f.getValue("type").noneAsNull(),
        )
    }.toList()

private val JOB = Regex("""\[([^\]]*)]""")

/** Passes every call to [real] and records it, with iOS's answer, in the clause block [recorder] has open. */
internal class RecordingUploadJobApi(private val real: UploadJobApi, private val recorder: Recorder) : UploadJobApi {
    override fun fetch(set: JobSet) = real.fetch(set).also { recorder.record(factsCall(set), it.render()) }
    override fun acknowledge(
        job: UploadJobFacts,
    ) = real.acknowledge(job).also { recorder.record(ackCall(job), it.render()) }
    override fun retry(job: UploadJobFacts, destination: NSURLRequest) =
        real.retry(job, destination).also { recorder.record(retryCall(job, destination), it.render()) }
    override fun create(destination: NSURLRequest, resource: GalleryResourceHandle) =
        real.create(destination, resource).also { recorder.record(createCall(destination, resource), it.render()) }
}

/** Answers every call from one clause's recorded block, exactly and in order. */
internal class ReplayingUploadJobApi(private val replayer: Replayer) : UploadJobApi {
    override fun fetch(set: JobSet) = parseFacts(replayer.answer(factsCall(set)))
    override fun acknowledge(job: UploadJobFacts) = parseChange(replayer.answer(ackCall(job)))
    override fun retry(
        job: UploadJobFacts,
        destination: NSURLRequest,
    ) = parseChange(replayer.answer(retryCall(job, destination)))
    override fun create(
        destination: NSURLRequest,
        resource: GalleryResourceHandle,
    ) = parseChange(replayer.answer(createCall(destination, resource)))
}

private fun Landed?.render() = if (this == null) "none" else "ct=${contentType.orNone()}"

private fun parseLanded(rendered: String): Landed? =
    if (rendered == "none") null else Landed(rendered.removePrefix("ct=").noneAsNull())

/** What the upload receiver says landed, recorded. [read] answers the receiver's App Group record. */
internal fun recordingFixtureObjects(recorder: Recorder, read: (String) -> Landed?) = FixtureObjects { route ->
    read(route).also { recorder.record(landedCall(route), it.render()) }
}

/** What landed, from the recording. */
internal fun replayingFixtureObjects(replayer: Replayer) = FixtureObjects { route ->
    parseLanded(replayer.answer(landedCall(route)))
}
