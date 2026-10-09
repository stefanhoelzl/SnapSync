package app.snapsync.contracts

import app.snapsync.model.AssetId
import app.snapsync.model.ResourceRole
import app.snapsync.model.UnionTrigger
import app.snapsync.model.uploadKey
import app.snapsync.wire.api.DeviceFilesReply
import app.snapsync.wire.api.EventFilesReply
import app.snapsync.wire.api.EventNotFound
import app.snapsync.wire.api.PublishManifestReply
import app.snapsync.wire.api.Routes
import app.snapsync.wire.api.Unauthorized
import app.snapsync.wire.models.Role
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The two listings — the event-wide union and a device's own stored files. Part of [BackendContract]'s clause list. */
internal fun ClauseList<BackendState, EdgeSubject<BackendClient>>.listingClauses() {
    clause(
        "UNION_AN_UNKNOWN_EVENT_IS_NOT_FOUND",
        BackendState.NO_SUCH_EVENT,
        covers = cells { backend(Routes.EventFiles.ID, EventNotFound::class) },
    ) { s ->
        assertAnswer<EventNotFound>(s.union(), "absent is a refusal, never an empty union")
    }

    clause(
        "UNION_AN_EMPTY_EVENT_IS_EMPTY",
        BackendState.EVENT_EXISTS,
        covers = cells { backend(Routes.EventFiles.ID, EventFilesReply.Page::class) },
    ) { s ->
        assertEquals(emptyList(), s.page().items)
    }

    clause(
        "UNION_A_COMPLETE_ASSET_IS_LISTED_UNDER_ITS_DEVICE",
        BackendState.UNION_COMPLETE_ASSET,
        covers = cells { backend(Routes.EventFiles.ID, EventFilesReply.Page::class) },
    ) { s ->
        val asset = s.seeded.asset!!
        val listed = s.page(urls = true).items.single()
        assertEquals(s.seeded.deviceId, listed.deviceId)
        assertEquals(asset.assetId.value, listed.assetId)
        assertEquals(asset.creationDate, listed.creationDate)
        val resource = listed.resources.single()
        assertEquals(Role.PRIMARY, resource.role)
        assertEquals(asset.key(ResourceRole.PRIMARY), resource.key, "the key the uploader stored it under")
        assertEquals(asset.filename, resource.filename)
        assertEquals("image/jpeg", resource.contentType)
        assertTrue(assertNotNull(resource.url, "a fetchable url").isNotBlank(), "a fetchable url")
    }

    // The app builds each download address itself (decision record `changes/incremental-union`, D1), so it asks for none.
    clause(
        "UNION_WITHOUT_URLS_NAMES_NONE",
        BackendState.UNION_COMPLETE_ASSET,
        covers = cells { backend(Routes.EventFiles.ID, EventFilesReply.Page::class) },
    ) { s ->
        val resource = s.page(urls = false).items.single().resources.single()
        assertNull(resource.url, "urls=false omits the address")
        assertEquals(s.seeded.asset!!.key(ResourceRole.PRIMARY), resource.key, "and only it")
    }

    clause(
        "UNION_AN_INCOMPLETE_ASSET_IS_OMITTED",
        BackendState.UNION_INCOMPLETE_ASSET,
        covers = cells { backend(Routes.EventFiles.ID, EventFilesReply.Page::class) },
    ) { s ->
        assertEquals(emptyList(), s.page().items, "never half a photo")
    }

    // Incremental reads (decision record `changes/incremental-union`, D3–D4): a full read names the position it
    // covers, and a read from that position serves only what the union gained after it.
    clause(
        "UNION_A_READ_FROM_ITS_POSITION_SERVES_ONLY_WHAT_WAS_GAINED_SINCE",
        BackendState.MEMBER_WITH_TWO_UPLOADED_ASSETS,
        covers = cells {
            backend(Routes.EventFiles.ID, EventFilesReply.Page::class)
            backend(Routes.PublishManifest.ID, PublishManifestReply.Published::class)
        },
    ) { s ->
        val device = s.seeded.deviceId
        assertAnswer<PublishManifestReply.Published>(s.publish(manifestOf(device, listOf(BackendContract.FIRST))))
        val position = s.page().position()
        assertEquals(emptyList(), s.page(position).items, "nothing was gained since")
        assertAnswer<PublishManifestReply.Published>(
            s.publish(manifestOf(device, listOf(BackendContract.FIRST, BackendContract.SECOND))),
        )
        val delta = s.page(position)
        assertEquals(listOf(BackendContract.SECOND.assetId.value), delta.items.map { it.assetId }, "only the gain")
        assertTrue(delta.position() > position, "the position moved past the gain")
    }

    clause(
        "UNION_AN_ASSET_WITHDRAWN_AFTER_ITS_GAIN_IS_NOT_SERVED_FROM_BEFORE_IT",
        BackendState.MEMBER_WITH_TWO_UPLOADED_ASSETS,
        covers = cells {
            backend(Routes.EventFiles.ID, EventFilesReply.Page::class)
            backend(Routes.PublishManifest.ID, PublishManifestReply.Published::class)
        },
    ) { s ->
        val device = s.seeded.deviceId
        val position = s.page().position()
        assertAnswer<PublishManifestReply.Published>(s.publish(manifestOf(device, listOf(BackendContract.FIRST))))
        assertAnswer<PublishManifestReply.Published>(s.publish(manifestOf(device, emptyList())))
        assertEquals(emptyList(), s.page(position).items, "a delta serves only what the union still holds")
    }

    // Public, but a token it is SENT is verified, so its 401 is a verdict on that token (D5).
    clause(
        "UNION_A_SENT_TOKEN_THE_BACKEND_NEVER_ISSUED_IS_REFUSED",
        BackendState.FOREIGN_TOKEN,
        covers = cells { backend(Routes.EventFiles.ID, Unauthorized::class) },
    ) { s ->
        assertAnswer<Unauthorized>(s.union())
    }

    clause(
        "DEVICE_FILES_A_FOREIGN_TOKEN_IS_REJECTED",
        BackendState.FOREIGN_TOKEN,
        covers = cells { backend(Routes.DeviceFiles.ID, Unauthorized::class) },
    ) { s ->
        assertAnswer<Unauthorized>(s.port.send(Routes.DeviceFiles.request(s.seeded.eventId, s.seeded.deviceId)))
    }

    clause(
        "DEVICE_FILES_A_FRESH_DEVICE_HOLDS_NOTHING",
        BackendState.SERVING,
        covers = cells { backend(Routes.DeviceFiles.ID, DeviceFilesReply.Listed::class) },
    ) { s ->
        assertEquals(emptyList(), s.stored(s.seeded.eventId))
    }

    // The listing answers in identity terms — asset, role and a filename — and the app recomposes the storage key
    // from them. Only the filename's extension enters that key, so what the promise pins is the recomposition, not
    // which of the two names (capture name or storage key, both carrying the extension) the backend echoes.
    clause(
        "DEVICE_FILES_AN_UPLOAD_IS_LISTED_BY_ITS_IDENTITY",
        BackendState.DEVICE_UPLOADED,
        covers = cells { backend(Routes.DeviceFiles.ID, DeviceFilesReply.Listed::class) },
    ) { s ->
        val asset = s.seeded.asset!!
        val listed = s.stored(s.seeded.eventId).single()
        assertEquals(asset.assetId.value, listed.assetId)
        assertEquals(Role.PRIMARY, listed.role)
        assertEquals(
            asset.key(ResourceRole.PRIMARY),
            uploadKey(AssetId(listed.assetId), ResourceRole.PRIMARY, listed.filename),
            "the key recomposes exactly",
        )
    }

    // Each event holds its own bytes (change `per-event-storage-layout`): the device marks every listed resource
    // uploaded, so a resource stored for one event listed under another would never reach that one.
    clause(
        "DEVICE_FILES_LISTS_ONLY_THE_EVENT_ASKED_ABOUT",
        BackendState.DEVICE_UPLOADED,
        covers = cells { backend(Routes.DeviceFiles.ID, DeviceFilesReply.Listed::class) },
    ) { s ->
        assertEquals(emptyList(), s.stored(ANOTHER_EVENT))
    }
}

/** An event id no seeded state uses — the "another event" a listing must not answer with this one's resources. */
private const val ANOTHER_EVENT = "0e1a5e00-0000-4000-8000-000000000001"

/** A read of this state's event union as its member reads it, from [cursor] (`null`: all of it). */
private suspend fun EdgeSubject<BackendClient>.union(cursor: Long? = null, urls: Boolean = false): EventFilesReply {
    val trigger = if (cursor == null) UnionTrigger.FOREGROUND else UnionTrigger.PUSH
    return port.send(Routes.EventFiles.request(seeded.eventId, cursor?.toString(), urls, trigger.wire))
}

/** [union], which must be served. */
private suspend fun EdgeSubject<BackendClient>.page(cursor: Long? = null, urls: Boolean = false): EventFilesReply.Page =
    assertAnswer(union(cursor, urls))

private fun EventFilesReply.Page.position(): Long =
    assertNotNull(snapSyncCursor.toLongOrNull(), "the position is a number: $snapSyncCursor")

/** What the backend holds for this state's device in [eventId], which it must list. */
private suspend fun EdgeSubject<BackendClient>.stored(eventId: String) =
    assertAnswer<DeviceFilesReply.Listed>(port.send(Routes.DeviceFiles.request(eventId, seeded.deviceId))).items
