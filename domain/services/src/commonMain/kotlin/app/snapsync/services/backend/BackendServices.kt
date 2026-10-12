package app.snapsync.services.backend

import app.snapsync.services.identity.PersistedDeviceIdentity

/**
 * Every need-shaped backend service, over ONE [BackendGateway] — so a composition cannot give two of them different
 * credentials or different verdict handling, and adding a need adds it here once.
 *
 * Each is built on first use, never at composition: nothing here reads [identity] or touches the network until a
 * caller asks, which is what a locked background launch depends on.
 */
class BackendServices(
    val gateway: BackendGateway,
    private val identity: PersistedDeviceIdentity,
    /** The device's zone, read at a create (the app's clock); the extension never creates, so it passes none. */
    private val zone: () -> String?,
) {
    val directory: EventDirectory by lazy { BackendEventDirectory(gateway) }
    val creation: EventCreation by lazy { BackendEventCreation(gateway, zone) }
    val rename: EventRename by lazy { BackendEventRename(gateway) }
    val join: EventJoin by lazy { BackendEventJoin(gateway) }
    val manifest: ManifestPublisher by lazy { BackendManifestPublisher(gateway) }
    val leave: LeaveNotifier by lazy { BackendLeaveNotifier(gateway, identity) }
    val received: ReceivedReporter by lazy { BackendReceivedReporter(gateway, identity) }
    val union: EventUnionSource by lazy { BackendEventUnionSource(gateway) }
    val deviceFiles: DeviceFilesSource by lazy { BackendDeviceFilesSource(gateway) }
    val pushTokens: PushTokenPublisher by lazy { BackendPushTokenPublisher(gateway, identity) }
}
