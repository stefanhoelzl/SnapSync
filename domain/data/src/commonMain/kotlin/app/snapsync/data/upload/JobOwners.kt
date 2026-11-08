package app.snapsync.data.upload

import app.snapsync.domain.event.EventId
import app.snapsync.domain.upload.DestinationPath
import app.snapsync.domain.upload.JobRoute
import app.snapsync.domain.upload.Ownerless
import app.snapsync.domain.upload.UploadRow
import app.snapsync.inbound.Field.Companion.of
import app.snapsync.inbound.Log
import app.snapsync.ports.OsUploadJob
import app.snapsync.wire.api.Routes

/**
 * **Which row an OS-held job belongs to** — found again by the destination the row recorded when its job was made, the
 * only way back: under the byte route a job's last path segment is the resource's role, never its key. A job no row
 * owns is a withdrawn row's or an unmappable one ([JobRoute.ownerless]).
 */
class JobOwners(private val rows: OwnUploadRows) {
    private val log = Log("JobOwners")

    // A template holds only path literals and `{parameter}`s, so a parameter's `[^/]+` is the one pattern it needs;
    // anchored at the end, after whatever version prefix the build's base carries.
    private val byteRoutes = listOf(Routes.UploadResource.TEMPLATE, Routes.UploadResourceEventless.TEMPLATE).map {
        Regex(it.replace(Regex("""\{\w+\}"""), "[^/]+") + "$")
    }

    /** Who owns [job] among [event]'s rows. */
    suspend fun of(event: EventId, job: OsUploadJob): JobOwner = when (val path = destinationOf(job)) {
        null -> JobOwner.None(Ownerless.UNMAPPABLE)
        is DestinationPath -> when (val read = rows.byDestination(event, path)) {
            is RowRead.Found -> JobOwner.Row(read.row)
            RowRead.Absent -> JobOwner.None(routeOf(path).ownerless())
            is RowRead.Unavailable -> unreadable(read.detail)
        }
    }

    private fun destinationOf(job: OsUploadJob): DestinationPath? = job.destinationUrl?.let(Destinations::pathOf)

    private fun routeOf(path: DestinationPath): JobRoute = when (byteRoutes.any { it.containsMatchIn(path.path) }) {
        true -> JobRoute.UPLOAD
        false -> JobRoute.OTHER
    }

    private suspend fun unreadable(detail: String): JobOwner {
        log.w("the upload rows could not be read — the job is left for the queue to present again", "detail" of detail)
        return JobOwner.Unreadable
    }
}

/** Who owns a presented job. */
sealed interface JobOwner {
    class Row(val row: UploadRow) : JobOwner

    class None(val why: Ownerless) : JobOwner

    /** The rows could not be read: the job is left unanswered, so its outcome is not discarded. */
    data object Unreadable : JobOwner
}

/** The path of an upload's URL, without its query — what a row records and a returned job is matched by. */
object Destinations {
    fun pathOf(url: String): DestinationPath = DestinationPath(
        "/" + url.substringAfter("://").substringAfter('/', missingDelimiterValue = "").substringBefore('?'),
    )
}
