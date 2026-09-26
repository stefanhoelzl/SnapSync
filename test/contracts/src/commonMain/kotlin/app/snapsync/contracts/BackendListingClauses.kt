package app.snapsync.contracts

import app.snapsync.model.AssetId
import app.snapsync.model.Reply
import app.snapsync.model.ResourceRole
import app.snapsync.model.uploadKey
import app.snapsync.ports.Backend
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The two listings — the event-wide union and a device's own stored files. Part of [BackendContract]'s clause list. */
internal fun ClauseList<BackendState, EdgeSubject<Backend>>.listingClauses() {

    clause("UNION_AN_UNKNOWN_EVENT_IS_REFUSED", BackendState.NO_SUCH_EVENT) { s ->
        assertIs<Reply.Refused>(s.port.eventFiles(s.seeded.eventId), "absent is a refusal, never an empty union")
    }

    clause("UNION_AN_EMPTY_EVENT_IS_EMPTY", BackendState.EVENT_EXISTS) { s ->
        assertEquals(emptyList(), assertOk(s.port.eventFiles(s.seeded.eventId)))
    }

    clause("UNION_A_COMPLETE_ASSET_IS_LISTED_UNDER_ITS_DEVICE", BackendState.UNION_COMPLETE_ASSET) { s ->
        val asset = s.seeded.asset!!
        val listed = assertOk(s.port.eventFiles(s.seeded.eventId)).single()
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

    clause("UNION_AN_INCOMPLETE_ASSET_IS_OMITTED", BackendState.UNION_INCOMPLETE_ASSET) { s ->
        assertEquals(emptyList(), assertOk(s.port.eventFiles(s.seeded.eventId)), "never half a photo")
    }

    clause("DEVICE_FILES_A_FRESH_DEVICE_HOLDS_NOTHING", BackendState.SERVING) { s ->
        assertEquals(emptyList(), assertOk(s.port.deviceFiles(s.token, s.seeded.deviceId)))
    }

    // The listing answers in identity terms — asset, role and a filename — and the app recomposes the storage key
    // from them. Only the filename's extension enters that key, so what the promise pins is the recomposition, not
    // which of the two names (capture name or storage key, both carrying the extension) the backend echoes.
    clause("DEVICE_FILES_AN_UPLOAD_IS_LISTED_BY_ITS_IDENTITY", BackendState.DEVICE_UPLOADED) { s ->
        val asset = s.seeded.asset!!
        val listed = assertOk(s.port.deviceFiles(s.token, s.seeded.deviceId)).single()
        assertEquals(asset.assetId, listed.assetId)
        assertEquals(ResourceRole.PRIMARY, listed.role)
        assertEquals(asset.key(ResourceRole.PRIMARY), uploadKey(listed.assetId, listed.role, listed.filename), "the key recomposes exactly")
    }
}
