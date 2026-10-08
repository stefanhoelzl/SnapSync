package app.snapsync.contracts

import app.snapsync.model.DeviceManifest
import app.snapsync.model.Reply
import app.snapsync.model.ResourceRole
import app.snapsync.model.UnionPage
import app.snapsync.model.UnionTrigger
import app.snapsync.model.uploadKey
import app.snapsync.ports.Backend
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The two listings — the event-wide union and a device's own stored files. Part of [BackendContract]'s clause list. */
internal fun ClauseList<BackendState, EdgeSubject<Backend>>.listingClauses() {
    clause(
        "UNION_AN_UNKNOWN_EVENT_IS_REFUSED",
        BackendState.NO_SUCH_EVENT,
        covers = cells { on<Backend>().answers(Backend::eventFiles).with(Reply.Refused::class) },
    ) { s ->
        assertIs<Reply.Refused>(s.union(), "absent is a refusal, never an empty union")
    }

    clause(
        "UNION_AN_EMPTY_EVENT_IS_EMPTY",
        BackendState.EVENT_EXISTS,
        covers = cells { on<Backend>().answers(Backend::eventFiles).withGenericLeaf(Reply.Ok::class) },
    ) { s ->
        assertEquals(emptyList(), assertOk(s.union()).assets)
    }

    clause(
        "UNION_A_COMPLETE_ASSET_IS_LISTED_UNDER_ITS_DEVICE",
        BackendState.UNION_COMPLETE_ASSET,
        covers = cells { on<Backend>().answers(Backend::eventFiles).withGenericLeaf(Reply.Ok::class) },
    ) { s ->
        val asset = s.seeded.asset!!
        val listed = assertOk(s.union()).assets.single()
        assertEquals(s.seeded.deviceId, listed.deviceId)
        assertEquals(asset.assetId, listed.assetId)
        assertEquals(asset.creationDate, listed.creationDate)
        val resource = listed.resources.single()
        assertEquals(ResourceRole.PRIMARY.wire, resource.role)
        assertEquals(asset.key(ResourceRole.PRIMARY), resource.key, "the key the uploader stored it under")
        assertEquals(asset.filename, resource.originalFilename)
        assertEquals("image/jpeg", resource.contentType)
        assertTrue(resource.url.isNotBlank(), "a fetchable url")
    }

    clause(
        "UNION_AN_INCOMPLETE_ASSET_IS_OMITTED",
        BackendState.UNION_INCOMPLETE_ASSET,
        covers = cells { on<Backend>().answers(Backend::eventFiles).withGenericLeaf(Reply.Ok::class) },
    ) { s ->
        assertEquals(emptyList(), assertOk(s.union()).assets, "never half a photo")
    }

    // Incremental reads (decision record `changes/incremental-union`, D3–D4): a full read names the position it
    // covers, and a read from that position serves only what the union gained after it.
    clause(
        "UNION_A_READ_FROM_ITS_POSITION_SERVES_ONLY_WHAT_WAS_GAINED_SINCE",
        BackendState.MEMBER_WITH_TWO_UPLOADED_ASSETS,
        covers = cells {
            on<Backend> {
                answers(Backend::eventFiles).withGenericLeaf(Reply.Ok::class)
                answers(Backend::publishManifest).withGenericLeaf(Reply.Ok::class)
            }
        },
    ) { s ->
        val (event, device) = s.seeded.eventId to s.seeded.deviceId
        assertOk(
            s.port.publishManifest(
                s.token,
                event,
                device,
                DeviceManifest(device, listOf(BackendContract.FIRST.manifestEntry()), version = 0),
            ),
        )
        val position = assertOk(s.union()).cursor
        assertEquals(emptyList(), assertOk(s.union(position)).assets, "nothing was gained since")
        assertOk(
            s.port.publishManifest(
                s.token,
                event,
                device,
                DeviceManifest(
                    device,
                    listOf(BackendContract.FIRST.manifestEntry(), BackendContract.SECOND.manifestEntry()),
                    version = 0,
                ),
            ),
        )
        val delta = assertOk(s.union(position))
        assertEquals(listOf(BackendContract.SECOND.assetId), delta.assets.map { it.assetId }, "only the gain")
        assertTrue(delta.cursor > position, "the position moved past the gain")
    }

    clause(
        "UNION_AN_ASSET_WITHDRAWN_AFTER_ITS_GAIN_IS_NOT_SERVED_FROM_BEFORE_IT",
        BackendState.MEMBER_WITH_TWO_UPLOADED_ASSETS,
        covers = cells {
            on<Backend> {
                answers(Backend::eventFiles).withGenericLeaf(Reply.Ok::class)
                answers(Backend::publishManifest).withGenericLeaf(Reply.Ok::class)
            }
        },
    ) { s ->
        val (event, device) = s.seeded.eventId to s.seeded.deviceId
        val position = assertOk(s.union()).cursor
        assertOk(
            s.port.publishManifest(
                s.token,
                event,
                device,
                DeviceManifest(device, listOf(BackendContract.FIRST.manifestEntry()), version = 0),
            ),
        )
        assertOk(s.port.publishManifest(s.token, event, device, DeviceManifest(device, emptyList(), version = 0)))
        assertEquals(emptyList(), assertOk(s.union(position)).assets, "a delta serves only what the union still holds")
    }

    // Public, but a token it is SENT is verified, so its 401 is a verdict on that token (D5).
    clause(
        "UNION_A_SENT_TOKEN_THE_BACKEND_NEVER_ISSUED_IS_REFUSED",
        BackendState.FOREIGN_TOKEN,
        covers = cells { on<Backend>().answers(Backend::eventFiles).with(Reply.Refused::class) },
    ) { s ->
        assertRefused(UNAUTHORIZED, s.port.eventFiles(s.token, s.seeded.eventId, null, UnionTrigger.FOREGROUND))
    }

    clause(
        "UNION_IS_SERVED_WITHOUT_A_TOKEN",
        BackendState.EVENT_EXISTS,
        covers = cells { on<Backend>().answers(Backend::eventFiles).withGenericLeaf(Reply.Ok::class) },
    ) { s ->
        assertOk(s.port.eventFiles(null, s.seeded.eventId, null, UnionTrigger.FOREGROUND))
    }

    clause(
        "DEVICE_FILES_A_FRESH_DEVICE_HOLDS_NOTHING",
        BackendState.SERVING,
        covers = cells { on<Backend>().answers(Backend::deviceFiles).withGenericLeaf(Reply.Ok::class) },
    ) { s ->
        assertEquals(emptyList(), assertOk(s.port.deviceFiles(s.token, s.seeded.eventId, s.seeded.deviceId)))
    }

    // The listing answers in identity terms — asset, role and a filename — and the app recomposes the storage key
    // from them. Only the filename's extension enters that key, so what the promise pins is the recomposition, not
    // which of the two names (capture name or storage key, both carrying the extension) the backend echoes.
    clause(
        "DEVICE_FILES_AN_UPLOAD_IS_LISTED_BY_ITS_IDENTITY",
        BackendState.DEVICE_UPLOADED,
        covers = cells { on<Backend>().answers(Backend::deviceFiles).withGenericLeaf(Reply.Ok::class) },
    ) { s ->
        val asset = s.seeded.asset!!
        val listed = assertOk(s.port.deviceFiles(s.token, s.seeded.eventId, s.seeded.deviceId)).single()
        assertEquals(asset.assetId, listed.assetId)
        assertEquals(ResourceRole.PRIMARY, listed.role)
        assertEquals(
            asset.key(ResourceRole.PRIMARY),
            uploadKey(listed.assetId, listed.role, listed.filename),
            "the key recomposes exactly",
        )
    }

    // Each event holds its own bytes (change `per-event-storage-layout`): the device marks every listed resource
    // uploaded, so a resource stored for one event listed under another would never reach that one.
    clause(
        "DEVICE_FILES_LISTS_ONLY_THE_EVENT_ASKED_ABOUT",
        BackendState.DEVICE_UPLOADED,
        covers = cells { on<Backend>().answers(Backend::deviceFiles).withGenericLeaf(Reply.Ok::class) },
    ) { s ->
        assertEquals(emptyList(), assertOk(s.port.deviceFiles(s.token, ANOTHER_EVENT, s.seeded.deviceId)))
    }
}

/** An event id no seeded state uses — the "another event" a listing must not answer with this one's resources. */
private const val ANOTHER_EVENT = "0e1a5e00-0000-4000-8000-000000000001"

/** A read of this state's event union as its member reads it, from [cursor] (`null`: all of it). */
private suspend fun EdgeSubject<Backend>.union(cursor: Long? = null): Reply<UnionPage> =
    port.eventFiles(token, seeded.eventId, cursor, if (cursor == null) UnionTrigger.FOREGROUND else UnionTrigger.PUSH)
