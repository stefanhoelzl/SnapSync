package app.snapsync.contracts

import app.snapsync.model.ApnsPushToken
import app.snapsync.model.AssetId
import app.snapsync.model.DeviceManifest
import app.snapsync.model.Reply
import app.snapsync.ports.Backend
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Membership and contribution — join, manifest, leave — and the device's push registration. Part of
 * [BackendContract]'s clause list, a split for size only.
 */
internal fun ClauseList<BackendState, EdgeSubject<Backend>>.membershipClauses() {

    clause("JOIN_AN_OPEN_EVENT_IS_JOINED", BackendState.EVENT_EXISTS) { s ->
        assertOk(s.port.joinEvent(s.token, s.seeded.eventId, s.seeded.deviceId))
    }

    clause("JOIN_A_REJOIN_IS_JOINED", BackendState.EVENT_EXISTS) { s ->
        s.port.joinEvent(s.token, s.seeded.eventId, s.seeded.deviceId)
        assertOk(s.port.joinEvent(s.token, s.seeded.eventId, s.seeded.deviceId), "joining is idempotent")
    }

    clause("JOIN_A_FULL_EVENT_IS_FULL", BackendState.EVENT_FULL) { s ->
        assertRefused(CONFLICT, s.port.joinEvent(s.token, s.seeded.eventId, s.seeded.deviceId), "full, never not-found")
    }

    clause("JOIN_AN_UNKNOWN_EVENT_IS_NOT_FOUND", BackendState.NO_SUCH_EVENT) { s ->
        assertRefused(NOT_FOUND, s.port.joinEvent(s.token, s.seeded.eventId, s.seeded.deviceId))
    }

    clause("A_FOREIGN_TOKEN_IS_REJECTED_ON_A_GATED_ROUTE", BackendState.FOREIGN_TOKEN) { s ->
        assertRefused(UNAUTHORIZED, s.port.joinEvent(s.token, s.seeded.eventId, s.seeded.deviceId), "a refused credential joins nothing")
    }

    clause("MANIFEST_A_MEMBER_PUBLISH_IS_ACCEPTED", BackendState.MEMBER) { s ->
        assertOk(s.port.publishManifest(s.token, s.seeded.eventId, s.seeded.deviceId, manifest(s.seeded.deviceId)))
    }

    clause("MANIFEST_A_NON_MEMBER_PUBLISH_IS_REFUSED", BackendState.EVENT_EXISTS) { s ->
        assertIs<Reply.Refused>(
            s.port.publishManifest(s.token, s.seeded.eventId, s.seeded.deviceId, manifest(s.seeded.deviceId)),
            "publishing never enrolls",
        )
    }

    clause("MANIFEST_AN_UNKNOWN_EVENT_PUBLISH_IS_REFUSED", BackendState.NO_SUCH_EVENT) { s ->
        assertIs<Reply.Refused>(s.port.publishManifest(s.token, s.seeded.eventId, s.seeded.deviceId, manifest(s.seeded.deviceId)))
    }

    // Two processes' publishes can cross in the network (capability `photo-sharing`, "A publish carries the manifest
    // version"): the one landing LAST may be the older snapshot. It is answered as a success — a snapshot at least as
    // new is already there — and changes nothing the union serves.
    clause("MANIFEST_AN_OLDER_PUBLISH_LANDING_LAST_CHANGES_NOTHING", BackendState.MEMBER_WITH_TWO_UPLOADED_ASSETS) { s ->
        val (event, device) = s.seeded.eventId to s.seeded.deviceId
        assertOk(s.port.publishManifest(s.token, event, device, versioned(device, BackendContract.SECOND, version = 2)))
        assertOk(
            s.port.publishManifest(s.token, event, device, versioned(device, BackendContract.FIRST, version = 1)),
            "an older publish is still answered",
        )
        assertEquals(setOf(BackendContract.SECOND.assetId), s.setup.unionAssetIds(event), "the older snapshot changed nothing")
    }

    // One version names one snapshot, so an equal one is applied — which is what lets a republish of an unchanged
    // version land at all.
    clause("MANIFEST_AN_EQUAL_VERSION_PUBLISH_IS_APPLIED", BackendState.MEMBER_WITH_TWO_UPLOADED_ASSETS) { s ->
        val (event, device) = s.seeded.eventId to s.seeded.deviceId
        assertOk(s.port.publishManifest(s.token, event, device, versioned(device, BackendContract.SECOND, version = 2)))
        assertOk(s.port.publishManifest(s.token, event, device, versioned(device, BackendContract.FIRST, version = 2)))
        assertEquals(setOf(BackendContract.FIRST.assetId), s.setup.unionAssetIds(event), "the equal-version snapshot replaced it")
    }

    clause("LEAVE_A_MEMBER_LEAVES", BackendState.MEMBER) { s ->
        assertOk(s.port.leaveEvent(s.token, s.seeded.eventId, s.seeded.deviceId))
    }

    clause("LEAVE_AN_UNKNOWN_EVENT_IS_REFUSED", BackendState.NO_SUCH_EVENT) { s ->
        assertIs<Reply.Refused>(s.port.leaveEvent(s.token, s.seeded.eventId, s.seeded.deviceId), "the backend says the event is gone")
    }

    clause("DEVICE_CONFIG_A_PUSH_TOKEN_IS_PUBLISHED", BackendState.SERVING) { s ->
        assertOk(s.port.putDeviceConfig(s.token, s.seeded.deviceId, pushToken("DEVICE_CONFIG_A_PUSH_TOKEN_IS_PUBLISHED")))
    }

    clause("DEVICE_CONFIG_A_ROTATED_TOKEN_IS_PUBLISHED_OVER_THE_LAST", BackendState.SERVING) { s ->
        val id = "DEVICE_CONFIG_A_ROTATED_TOKEN_IS_PUBLISHED_OVER_THE_LAST"
        assertOk(s.port.putDeviceConfig(s.token, s.seeded.deviceId, pushToken(id, 1)))
        assertOk(s.port.putDeviceConfig(s.token, s.seeded.deviceId, pushToken(id, 2)), "a rotation overwrites; it is never a conflict")
    }

    clause("DEVICE_CONFIG_A_FOREIGN_TOKEN_IS_REJECTED", BackendState.FOREIGN_TOKEN) { s ->
        assertRefused(UNAUTHORIZED, s.port.putDeviceConfig(s.token, s.seeded.deviceId, pushToken("DEVICE_CONFIG_A_FOREIGN_TOKEN_IS_REJECTED")))
    }
}

private fun manifest(deviceId: String) =
    DeviceManifest(deviceId, listOf(SeededAsset(AssetId("asset-1"), listOf(app.snapsync.model.ResourceRole.PRIMARY)).manifestEntry()))

/** A manifest declaring exactly [asset], projected under manifest [version]. */
private fun versioned(deviceId: String, asset: SeededAsset, version: Long) =
    DeviceManifest(deviceId, listOf(asset.manifestEntry()), version = version)

private fun pushToken(clauseId: String, n: Int = 1) = ApnsPushToken("token$n:$clauseId", "sandbox")
