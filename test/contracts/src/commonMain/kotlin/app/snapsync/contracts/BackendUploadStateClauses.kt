package app.snapsync.contracts

import app.snapsync.model.ResourceRole
import app.snapsync.wire.api.Routes
import app.snapsync.wire.api.UploadStateReply
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Where an upload stands (`docs/architecture.md`, "Resumable uploads") — asked before a failed upload is sent again.
 * Part of [BackendContract]'s clause list.
 */
internal fun ClauseList<BackendState, EdgeSubject<BackendClient>>.uploadStateClauses() {
    clause(
        "UPLOAD_STATE_BYTES_THAT_LANDED_ARE_LANDED",
        BackendState.DEVICE_UPLOADED,
        covers = cells { backend(Routes.UploadState.ID, UploadStateReply.Landed::class) },
    ) { s ->
        assertEquals(UploadStateReply.Landed, s.uploadState(SEEDED_SHA256), "the same bytes are not sent again")
    }

    clause(
        "UPLOAD_STATE_A_BROKEN_OFF_UPLOAD_KEPT_WHAT_ARRIVED",
        BackendState.DEVICE_UPLOAD_BROKE_OFF,
        covers = cells { backend(Routes.UploadState.ID, UploadStateReply.Stored::class) },
    ) { s ->
        val stored = assertIs<UploadStateReply.Stored>(s.uploadState(SEEDED_PART_SHA256))
        assertEquals("$SEEDED_PART", stored.uploadOffset, "the rest goes from the first byte that did not arrive")
    }

    clause(
        "UPLOAD_STATE_NOTHING_STORED_FOR_THESE_BYTES",
        BackendState.DEVICE_UPLOADED,
        covers = cells { backend(Routes.UploadState.ID, UploadStateReply.NothingStored::class) },
    ) { s ->
        assertEquals(UploadStateReply.NothingStored, s.uploadState(OTHER_SHA256), "other bytes: send them whole")
        assertEquals(UploadStateReply.NothingStored, s.uploadState(SEEDED_SHA256, "never-1"), "another resource")
    }

    clause(
        "UPLOAD_STATE_A_DEVICE_NOT_IN_THE_EVENT_IS_REFUSED",
        BackendState.EVENT_EXISTS,
        covers = cells { backend(Routes.UploadState.ID, UploadStateReply.Forbidden::class) },
    ) { s ->
        assertEquals(UploadStateReply.Forbidden, s.uploadState(SEEDED_SHA256, "never-1"))
    }
}

/** SHA-256 hex no seeded upload declares. */
private const val OTHER_SHA256 = "0000000000000000000000000000000000000000000000000000000000000001"

private suspend fun EdgeSubject<BackendClient>.uploadState(
    sha256: String,
    assetId: String = seeded.asset!!.assetId.value,
): UploadStateReply = port.send(
    Routes.UploadState.request(seeded.eventId, seeded.deviceId, assetId, ResourceRole.PRIMARY.wire, sha256),
)
