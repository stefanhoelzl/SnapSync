package app.snapsync.services.backend

import app.snapsync.data.backend.BackendGateway
import app.snapsync.data.event.EventDirectory
import app.snapsync.data.event.EventRename
import app.snapsync.data.identity.DeviceIdentity
import app.snapsync.data.membership.EventJoin
import app.snapsync.data.membership.LeaveNotifier
import app.snapsync.domain.event.EventId

/**
 * Every need-shaped backend service, over ONE [BackendGateway] — so a composition cannot give two of them different
 * credentials or different verdict handling, and adding a need adds it here once.
 *
 * Each is built on first use, never at composition: nothing here reads [identity] or touches the network until a
 * caller asks, which is what a locked background launch depends on.
 */
class BackendServices(
    val gateway: BackendGateway,
    private val identity: DeviceIdentity,
    /** The device's zone, read at a create (the app's clock); the extension never creates, so it passes none. */
    private val zone: () -> String?,
    /**
     * The service answered the member's shared set that [EventId]'s photos were deleted — its positive word, which the
     * app's membership marks; the extension has no membership to mark, so passes none.
     */
    private val eventOver: (suspend (EventId) -> Unit)?,
) {
    val directory: EventDirectory by lazy { EventDirectory(gateway) }
    val creation: EventCreation by lazy { BackendEventCreation(gateway, zone) }
    val rename: EventRename by lazy { EventRename(gateway) }
    val join: EventJoin by lazy { EventJoin(gateway) }
    val manifest: ManifestPublisher by lazy { BackendManifestPublisher(gateway, eventOver) }
    val leave: LeaveNotifier by lazy { LeaveNotifier(gateway, identity) }
    val received: ReceivedReporter by lazy { BackendReceivedReporter(gateway, identity) }
    val union: EventUnionSource by lazy { BackendEventUnionSource(gateway) }
    val deviceFiles: DeviceFilesSource by lazy { BackendDeviceFilesSource(gateway) }
    val pushTokens: PushTokenPublisher by lazy { BackendPushTokenPublisher(gateway, identity) }
}
