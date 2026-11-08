package app.snapsync.data.receiving

import app.snapsync.data.Parsed
import app.snapsync.data.backend.BackendGateway
import app.snapsync.data.receiving.DownloadDatabaseFile.Companion.read
import app.snapsync.data.receiving.DownloadDatabaseFile.Companion.refOf
import app.snapsync.data.sharing.ManifestRecord
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.event.EventId
import app.snapsync.domain.receiving.OthersDeclared
import app.snapsync.domain.receiving.SenderFilename
import app.snapsync.domain.receiving.UnionPage
import app.snapsync.domain.receiving.UnionPhoto
import app.snapsync.domain.receiving.UnionReadPlan
import app.snapsync.domain.receiving.UnionResource
import app.snapsync.domain.receiving.UnionTrigger
import app.snapsync.format.TimestampText
import app.snapsync.wire.api.ClientError
import app.snapsync.wire.api.EventFilesReply
import app.snapsync.wire.api.NoAnswer
import app.snapsync.wire.api.Routes
import app.snapsync.wire.api.ServerIssue

/**
 * The event's union over the backend: every contributing device's COMPLETE photos, each tagged with its source and
 * carrying a download address this build builds itself — the route `downloadResource` on its own backend, which
 * redirects to the bytes (decision record `changes/incremental-union`, D1). Read from a position it answers only what
 * was gained since (D3); the trigger says why, and the backend records it.
 *
 * The page's declared total is netted here, at the read, of what this device had declared ([own]): the two numbers
 * must describe one backend state — subtracted later, a publish in between showed the member's own photos as awaited.
 */
class EventUnion(private val gateway: BackendGateway, private val own: ManifestRecord) {

    /** [event]'s union, read as [plan] says — [UnionReadPlan.AlreadyRead] reads nothing and is no read. */
    suspend fun read(event: EventId, plan: UnionReadPlan, trigger: UnionTrigger): UnionRead = when (plan) {
        UnionReadPlan.Whole -> fetched(event, null, trigger)
        is UnionReadPlan.Since -> fetched(event, plan.from, trigger)
        UnionReadPlan.AlreadyRead -> UnionRead.Unavailable("the announced position is already read")
    }

    private suspend fun fetched(event: EventId, from: Long?, trigger: UnionTrigger): UnionRead {
        val request = Routes.EventFiles.request(
            event.value,
            from?.toString(),
            urls = false,
            snapSyncTrigger = trigger.wire,
        )
        return when (val reply = gateway.send(request)) {
            is EventFilesReply.Page -> paged(event, reply)
            is ClientError, is ServerIssue, is NoAnswer -> UnionRead.Unavailable(
                "union of $event (${trigger.wire}, from ${from ?: "start"}): $reply",
            )
        }
    }

    /**
     * A page with a photo whose id is no id or whose resource names no sender filename, or a position that is no
     * number, is a page the app cannot go on from.
     */
    private fun paged(event: EventId, reply: EventFilesReply.Page): UnionRead =
        when (val position = positionOf(reply.snapSyncCursor)) {
            is Parsed.Ok -> positioned(event, reply, position.value)
            is Parsed.Refused -> unreadable(event)
        }

    private fun positionOf(text: String): Parsed<Long> =
        text.toLongOrNull()?.let { Parsed.Ok(it) } ?: Parsed.Refused("$text is no position")

    private fun positioned(event: EventId, reply: EventFilesReply.Page, position: Long): UnionRead =
        photos(event, reply.items)?.let { UnionRead.Ok(UnionPage(it, position, othersDeclared(event, reply))) }
            ?: unreadable(event)

    private fun othersDeclared(event: EventId, reply: EventFilesReply.Page): Long? =
        OthersDeclared.of(reply.snapSyncDeclared?.toLongOrNull(), own.read(event).declared)

    private fun unreadable(event: EventId) = UnionRead.Unavailable(
        "the union of $event names what this build cannot read",
    )

    private fun photos(event: EventId, items: List<app.snapsync.wire.models.UnionAsset>): List<UnionPhoto>? =
        items.map { photo(event, it) }.takeIf { all -> all.none { it.isEmpty() } }?.flatten()

    /**
     * The photo [dto] describes, as a list of one — or of none when its ids are no ids, or a resource names no sender
     * filename (every upload names one, so the union never serves a resource without: change
     * `a-received-photo-always-has-a-sender-filename`).
     */
    private fun photo(event: EventId, dto: app.snapsync.wire.models.UnionAsset): List<UnionPhoto> =
        refOf(dto.deviceId, dto.assetId).flatMap { ref ->
            resources(event, dto, ref.sourceAssetId)
                ?.let { listOf(UnionPhoto(ref, TimestampText.captureDate(dto.creationDate), it)) }
                .orEmpty()
        }

    /** Every resource of [dto], or `null` when one names no sender filename. */
    private fun resources(
        event: EventId,
        dto: app.snapsync.wire.models.UnionAsset,
        asset: AssetId,
    ): List<UnionResource>? = dto.resources.mapNotNull { SenderFilename.parse(it.filename) }
        .takeIf { it.size == dto.resources.size }
        ?.let { names ->
            dto.resources.zip(names) { r, name ->
                UnionResource(
                    r.key,
                    gateway.open.url(
                        Routes.DownloadResource.path(event.value, dto.deviceId, asset.value, r.role.value),
                    ),
                    r.role.value,
                    r.contentType,
                    name,
                )
            }
        }
}

/** One read of an event's union, or that it could not be made. */
sealed interface UnionRead {
    class Ok(val page: UnionPage) : UnionRead

    class Unavailable(val detail: String) : UnionRead
}
