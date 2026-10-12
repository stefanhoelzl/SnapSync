package app.snapsync.contracts

import app.snapsync.model.AssetId
import app.snapsync.model.ResourceRole
import app.snapsync.wire.api.EventNotFound
import app.snapsync.wire.api.JoinEventReply
import app.snapsync.wire.api.LeaveEventReply
import app.snapsync.wire.api.PublishManifestReply
import app.snapsync.wire.api.PutDeviceReply
import app.snapsync.wire.api.Routes
import app.snapsync.wire.api.Unauthorized
import app.snapsync.wire.models.DeviceConfig
import app.snapsync.wire.models.Manifest
import app.snapsync.wire.models.PushRegistration
import kotlin.test.assertEquals

/**
 * Membership and contribution — join, manifest, leave — and the device's push registration. Part of
 * [BackendContract]'s clause list, a split for size only.
 */
internal fun ClauseList<BackendState, EdgeSubject<BackendClient>>.membershipClauses() {
    clause(
        "JOIN_AN_OPEN_EVENT_IS_JOINED",
        BackendState.EVENT_EXISTS,
        covers = cells { backend(Routes.JoinEvent.ID, JoinEventReply.Joined::class) },
    ) { s ->
        assertAnswer<JoinEventReply.Joined>(s.join())
    }

    clause(
        "JOIN_A_REJOIN_IS_JOINED",
        BackendState.EVENT_EXISTS,
        covers = cells { backend(Routes.JoinEvent.ID, JoinEventReply.Joined::class) },
    ) { s ->
        s.join()
        assertAnswer<JoinEventReply.Joined>(s.join(), "joining is idempotent")
    }

    clause(
        "JOIN_AN_UNKNOWN_EVENT_IS_NOT_FOUND",
        BackendState.NO_SUCH_EVENT,
        covers = cells { backend(Routes.JoinEvent.ID, EventNotFound::class) },
    ) { s ->
        assertAnswer<EventNotFound>(s.join())
    }

    clause(
        "A_FOREIGN_TOKEN_IS_REJECTED_ON_A_GATED_ROUTE",
        BackendState.FOREIGN_TOKEN,
        covers = cells { backend(Routes.JoinEvent.ID, Unauthorized::class) },
    ) { s ->
        assertAnswer<Unauthorized>(s.join(), "a refused credential joins nothing")
    }

    clause(
        "MANIFEST_A_MEMBER_PUBLISH_IS_ACCEPTED",
        BackendState.MEMBER,
        covers = cells { backend(Routes.PublishManifest.ID, PublishManifestReply.Published::class) },
    ) { s ->
        assertAnswer<PublishManifestReply.Published>(s.publish(manifest(s.seeded.deviceId)))
    }

    clause(
        "MANIFEST_A_NON_MEMBER_PUBLISH_IS_REFUSED",
        BackendState.EVENT_EXISTS,
        covers = cells { backend(Routes.PublishManifest.ID, PublishManifestReply.NotAMember::class) },
    ) { s ->
        assertAnswer<PublishManifestReply.NotAMember>(
            s.publish(manifest(s.seeded.deviceId)),
            "publishing never enrolls",
        )
    }

    clause(
        "MANIFEST_AN_UNKNOWN_EVENT_PUBLISH_IS_NOT_FOUND",
        BackendState.NO_SUCH_EVENT,
        covers = cells { backend(Routes.PublishManifest.ID, EventNotFound::class) },
    ) { s ->
        assertAnswer<EventNotFound>(s.publish(manifest(s.seeded.deviceId)))
    }

    // Two processes' publishes can cross in the network (a publish carries the manifest
    // version): the one landing LAST may be the older snapshot. It is answered as a success — a snapshot at least as
    // new is already there — and changes nothing the union serves.
    clause(
        "MANIFEST_AN_OLDER_PUBLISH_LANDING_LAST_CHANGES_NOTHING",
        BackendState.MEMBER_WITH_TWO_UPLOADED_ASSETS,
        covers = cells { backend(Routes.PublishManifest.ID, PublishManifestReply.Published::class) },
    ) { s ->
        val device = s.seeded.deviceId
        assertAnswer<PublishManifestReply.Published>(s.publish(manifestOf(device, listOf(BackendContract.SECOND), 2)))
        assertAnswer<PublishManifestReply.Published>(
            s.publish(manifestOf(device, listOf(BackendContract.FIRST), 1)),
            "an older publish is still answered",
        )
        assertEquals(
            setOf(BackendContract.SECOND.assetId),
            s.setup.unionAssetIds(s.seeded.eventId),
            "the older snapshot changed nothing",
        )
    }

    // One version names one snapshot, so an equal one is applied — which is what lets a republish of an unchanged
    // version land at all.
    clause(
        "MANIFEST_AN_EQUAL_VERSION_PUBLISH_IS_APPLIED",
        BackendState.MEMBER_WITH_TWO_UPLOADED_ASSETS,
        covers = cells { backend(Routes.PublishManifest.ID, PublishManifestReply.Published::class) },
    ) { s ->
        val device = s.seeded.deviceId
        assertAnswer<PublishManifestReply.Published>(s.publish(manifestOf(device, listOf(BackendContract.SECOND), 2)))
        assertAnswer<PublishManifestReply.Published>(s.publish(manifestOf(device, listOf(BackendContract.FIRST), 2)))
        assertEquals(
            setOf(BackendContract.FIRST.assetId),
            s.setup.unionAssetIds(s.seeded.eventId),
            "the equal-version snapshot replaced it",
        )
    }

    clause(
        "LEAVE_A_MEMBER_LEAVES",
        BackendState.MEMBER,
        covers = cells { backend(Routes.LeaveEvent.ID, LeaveEventReply.Left::class) },
    ) { s ->
        assertAnswer<LeaveEventReply.Left>(s.leave(received = false))
    }

    clause(
        "LEAVE_AN_UNKNOWN_EVENT_IS_NOT_FOUND",
        BackendState.NO_SUCH_EVENT,
        covers = cells { backend(Routes.LeaveEvent.ID, EventNotFound::class) },
    ) { s ->
        assertAnswer<EventNotFound>(s.leave(received = false), "the backend says the event is gone")
    }

    clause(
        "DEVICE_CONFIG_A_PUSH_TOKEN_IS_PUBLISHED",
        BackendState.SERVING,
        covers = cells { backend(Routes.PutDevice.ID, PutDeviceReply.Recorded::class) },
    ) { s ->
        assertAnswer<PutDeviceReply.Recorded>(s.putDevice("DEVICE_CONFIG_A_PUSH_TOKEN_IS_PUBLISHED"))
    }

    clause(
        "DEVICE_CONFIG_A_ROTATED_TOKEN_IS_PUBLISHED_OVER_THE_LAST",
        BackendState.SERVING,
        covers = cells { backend(Routes.PutDevice.ID, PutDeviceReply.Recorded::class) },
    ) { s ->
        val id = "DEVICE_CONFIG_A_ROTATED_TOKEN_IS_PUBLISHED_OVER_THE_LAST"
        assertAnswer<PutDeviceReply.Recorded>(s.putDevice(id, 1))
        assertAnswer<PutDeviceReply.Recorded>(s.putDevice(id, 2), "a rotation overwrites; it is never a conflict")
    }

    clause(
        "DEVICE_CONFIG_A_FOREIGN_TOKEN_IS_REJECTED",
        BackendState.FOREIGN_TOKEN,
        covers = cells { backend(Routes.PutDevice.ID, Unauthorized::class) },
    ) { s ->
        assertAnswer<Unauthorized>(s.putDevice("DEVICE_CONFIG_A_FOREIGN_TOKEN_IS_REJECTED"))
    }
}

/** A manifest declaring one asset for [deviceId], version 0. */
internal fun manifest(deviceId: String): Manifest =
    manifestOf(deviceId, listOf(SeededAsset(AssetId("asset-1"), listOf(ResourceRole.PRIMARY))))

/** This state's device joining this state's event. */
internal suspend fun EdgeSubject<BackendClient>.join(): JoinEventReply =
    port.send(Routes.JoinEvent.request(seeded.eventId, seeded.deviceId))

/** This state's device publishing [manifest] into this state's event. */
internal suspend fun EdgeSubject<BackendClient>.publish(manifest: Manifest): PublishManifestReply =
    port.send(Routes.PublishManifest.request(seeded.eventId, seeded.deviceId, manifest))

/** This state's device leaving this state's event, having [received] everything or not. */
internal suspend fun EdgeSubject<BackendClient>.leave(received: Boolean): LeaveEventReply =
    port.send(Routes.LeaveEvent.request(seeded.eventId, seeded.deviceId, received))

/** This state's device recording push token [n] of [clauseId]. */
internal suspend fun EdgeSubject<BackendClient>.putDevice(clauseId: String, n: Int = 1): PutDeviceReply =
    port.send(
        Routes.PutDevice.request(
            seeded.deviceId,
            DeviceConfig(PushRegistration("apns", "token$n:$clauseId", "sandbox")),
        ),
    )
