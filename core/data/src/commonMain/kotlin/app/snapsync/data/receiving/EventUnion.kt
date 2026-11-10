package app.snapsync.data.receiving

import app.snapsync.data.Parsed
import app.snapsync.data.backend.BackendGateway
import app.snapsync.data.crypto.NameReading
import app.snapsync.data.crypto.ServedNames
import app.snapsync.data.receiving.DownloadDatabaseFile.Companion.read
import app.snapsync.data.receiving.DownloadDatabaseFile.Companion.refOf
import app.snapsync.data.sharing.ManifestRecord
import app.snapsync.domain.asset.FileBinding
import app.snapsync.domain.asset.ResourceRole
import app.snapsync.domain.event.EventId
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.receiving.AssetRef
import app.snapsync.domain.receiving.HeldUnion
import app.snapsync.domain.receiving.OthersDeclared
import app.snapsync.domain.receiving.PushNotice
import app.snapsync.domain.receiving.SenderFilename
import app.snapsync.domain.receiving.ServedFileName
import app.snapsync.domain.receiving.ServedPhoto
import app.snapsync.domain.receiving.ServedResource
import app.snapsync.domain.receiving.UnionPage
import app.snapsync.domain.receiving.UnionPhoto
import app.snapsync.domain.receiving.UnionReadPlan
import app.snapsync.domain.receiving.UnionResource
import app.snapsync.domain.receiving.UnionTrigger
import app.snapsync.domain.upload.UploadKey
import app.snapsync.format.TimestampText
import app.snapsync.format.UploadKeyText
import app.snapsync.inbound.Field.Companion.of
import app.snapsync.inbound.Log
import app.snapsync.wire.api.ClientError
import app.snapsync.wire.api.EventFilesReply
import app.snapsync.wire.api.NoAnswer
import app.snapsync.wire.api.Routes
import app.snapsync.wire.api.ServerIssue
import app.snapsync.wire.models.Role
import app.snapsync.wire.models.UnionAsset
import app.snapsync.wire.models.UnionResource as WireResource

/**
 * The event's union over the backend: every contributing device's COMPLETE photos, each tagged with its source and
 * carrying a download address this build builds itself — the route `downloadResource` on its own backend, which
 * redirects to the bytes (decision record `changes/incremental-union`, D1). Read from a position it answers only what
 * was gained since (D3); the trigger says why, and the backend records it.
 *
 * The page's declared total is netted here, at the read, of what this device had declared ([own]): the two numbers
 * must describe one backend state — subtracted later, a publish in between showed the member's own photos as awaited.
 *
 * Each resource's name is read through [names] (decision record `changes/seal-file-names`, D6) — opened with the
 * event's key in an encrypted event — and its key is derived from that name, as the uploading phone derived its own,
 * so the union's `key` is never read. A page whose names cannot be read now (the key locked away) is no read, and is
 * read again later.
 */
class EventUnion(
    private val gateway: BackendGateway,
    private val own: ManifestRecord,
    private val names: ServedNames,
) {
    /**
     * [event]'s whole union as a holding check counts it ([HeldUnion]): every complete photo by ref, and the position —
     * read without a single name, so a member whose key is lost still learns what it holds.
     */
    suspend fun held(event: EventId, trigger: UnionTrigger): HeldRead {
        val request = Routes.EventFiles.request(event.value, null, urls = false, snapSyncTrigger = trigger.wire)
        return when (val reply = gateway.send(request)) {
            is EventFilesReply.Page -> counted(event, reply)
            is ClientError, is ServerIssue, is NoAnswer ->
                HeldRead.Unavailable("union of $event (${trigger.wire}): $reply")
        }
    }

    private fun counted(event: EventId, reply: EventFilesReply.Page): HeldRead {
        val refs = reply.items.map { refOf(it.deviceId, it.assetId).singleOrNull() }
        return HeldUnion.of(reply.snapSyncCursor.toLongOrNull(), refs)?.let(HeldRead::Counted)
            ?: HeldRead.Unavailable("the union of $event names what this build cannot read")
    }

    /**
     * [membership]'s event's union, read as [plan] says, its names read as that membership's event serves them. A read
     * a push caused names it ([push]) for the service's delivery record.
     */
    suspend fun read(
        membership: Membership,
        plan: UnionReadPlan.Reading,
        trigger: UnionTrigger,
        push: PushNotice? = null,
    ): UnionRead = when (plan) {
        UnionReadPlan.Whole -> fetched(membership, null, trigger, push)
        is UnionReadPlan.Since -> fetched(membership, plan.from, trigger, push)
    }

    private suspend fun fetched(
        membership: Membership,
        from: Long?,
        trigger: UnionTrigger,
        push: PushNotice?,
    ): UnionRead {
        val event = membership.event.id
        return when (val reading = names.of(membership)) {
            is NameReading.Opens -> requested(ServedPhotos(gateway, event, reading), from, trigger, push)
            is NameReading.Unavailable -> UnionRead.Unavailable("the names of $event cannot be read: ${reading.detail}")
        }
    }

    private suspend fun requested(
        photos: ServedPhotos,
        from: Long?,
        trigger: UnionTrigger,
        push: PushNotice?,
    ): UnionRead {
        val event = photos.event
        val request = Routes.EventFiles.request(
            event.value,
            from?.toString(),
            urls = false,
            snapSyncTrigger = trigger.wire,
            xSnapsyncNotified = PushHeaders.notified(push),
            xSnapsyncAnnounced = PushHeaders.announced(push),
        )
        return when (val reply = gateway.send(request)) {
            is EventFilesReply.Page -> paged(photos, reply)
            is ClientError, is ServerIssue, is NoAnswer -> UnionRead.Unavailable(
                "union of $event (${trigger.wire}, from ${from ?: "start"}): $reply",
            )
        }
    }

    /**
     * A page with a photo whose id is no id or whose resource names no sender filename, or a position that is no
     * number, is a page the app cannot go on from.
     */
    private suspend fun paged(photos: ServedPhotos, reply: EventFilesReply.Page): UnionRead =
        when (val position = positionOf(reply.snapSyncCursor)) {
            is Parsed.Ok -> positioned(photos, reply, position.value)
            is Parsed.Refused -> unreadable(photos.event)
        }

    private fun positionOf(text: String): Parsed<Long> =
        text.toLongOrNull()?.let { Parsed.Ok(it) } ?: Parsed.Refused("$text is no position")

    private suspend fun positioned(photos: ServedPhotos, reply: EventFilesReply.Page, position: Long): UnionRead =
        photos.of(reply.items)?.let { UnionRead.Ok(UnionPage(it, position, othersDeclared(photos.event, reply))) }
            ?: unreadable(photos.event)

    private fun othersDeclared(event: EventId, reply: EventFilesReply.Page): Long? =
        OthersDeclared.of(reply.snapSyncDeclared?.toLongOrNull(), own.read(event).declared)

    private fun unreadable(event: EventId) = UnionRead.Unavailable(
        "the union of $event names what this build cannot read",
    )
}

/**
 * **One union page's photos**, each resource's name read as [event] serves it ([reading]; decision record
 * `changes/seal-file-names`, D6) and its key derived from that name, as the uploading phone derived its own. A photo
 * whose ids are no ids, or one with an empty readable name (every upload names its file: change
 * `a-received-photo-always-has-a-sender-filename`), makes a page this build cannot go on from; one with a SEALED name
 * that does not open under the event's key is left out — never received, while every other photo of the page is — and
 * said, as the service misbehaving or a member sealing under another key.
 */
internal class ServedPhotos(
    private val gateway: BackendGateway,
    val event: EventId,
    private val reading: NameReading.Opens,
) {
    private val log = Log("EventUnion")

    /** The photos [items] serve, those left out dropped — or `null` when the page cannot be gone on from. */
    suspend fun of(items: List<UnionAsset>): List<UnionPhoto>? = ServedPhoto.pageOf(
        items.map { photo(it) }.onEach { told(it) },
    )

    private fun photo(dto: UnionAsset): ServedPhoto = when (val ref = refOf(dto.deviceId, dto.assetId).singleOrNull()) {
        null -> ServedPhoto.Malformed
        is AssetRef -> photo(ref, dto)
    }

    private fun photo(ref: AssetRef, dto: UnionAsset): ServedPhoto =
        ServedPhoto.of(ref, TimestampText.captureDate(dto.creationDate), dto.resources.map { resource(ref, it) })

    private fun resource(ref: AssetRef, dto: WireResource): ServedResource {
        val role = roleOf(dto.role)
        val name = reading.nameOf(FileBinding(event, ref.sourceDeviceId, ref.sourceAssetId, role), dto.filename)
        return when (name) {
            is ServedFileName.Named -> ServedResource.Named(named(ref, role, dto, name.name))
            is ServedFileName.Unopened -> ServedResource.Unopened(name.reason)
            ServedFileName.Malformed -> ServedResource.Malformed
        }
    }

    private fun named(ref: AssetRef, role: ResourceRole, dto: WireResource, name: SenderFilename) = UnionResource(
        UploadKeyText.of(UploadKey.of(ref.sourceAssetId, role, name.value)),
        gateway.open.url(
            Routes.DownloadResource.path(
                event.value,
                ref.sourceDeviceId.value,
                ref.sourceAssetId.value,
                dto.role.value,
            ),
        ),
        dto.role.value,
        dto.contentType,
        name,
    )

    private suspend fun told(photo: ServedPhoto) = when (photo) {
        is ServedPhoto.Left -> log.w(
            "a photo's sealed name does not open under the event's key; it is not received",
            "device" of photo.ref.sourceDeviceId,
            "asset" of photo.ref.sourceAssetId,
            "reasons" of photo.reasons.joinToString(),
        )
        is ServedPhoto.Read, ServedPhoto.Malformed -> Unit
    }

    private fun roleOf(role: Role): ResourceRole = when (role) {
        Role.PRIMARY -> ResourceRole.PRIMARY
        Role.LIVE -> ResourceRole.LIVE
    }
}

/** One read of an event's union, or that it could not be made. */
sealed interface UnionRead {
    class Ok(val page: UnionPage) : UnionRead

    class Unavailable(val detail: String) : UnionRead
}

/** A whole union as a holding check counts it, or that it could not be read. */
sealed interface HeldRead {
    class Counted(val union: HeldUnion) : HeldRead

    class Unavailable(val detail: String) : HeldRead
}

/** The delivery record's facts of a read a push caused, as its headers spell them — none for a read no push caused. */
internal object PushHeaders {
    /** When the app acted on the push. */
    fun notified(push: PushNotice?): String? = when (push) {
        null -> null
        is PushNotice -> TimestampText.format(push.at)
    }

    /** The union position the push announced. */
    fun announced(push: PushNotice?): String? = when (push) {
        null -> null
        is PushNotice -> push.announced.toString()
    }
}
