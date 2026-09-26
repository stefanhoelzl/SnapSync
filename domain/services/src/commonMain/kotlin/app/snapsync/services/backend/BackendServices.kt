package app.snapsync.services.backend

import app.snapsync.services.identity.PersistedDeviceIdentity

/**
 * Every need-shaped backend service, over ONE [AuthenticatedBackend] — so a composition cannot give two of them
 * different credentials or different verdict handling, and adding a need adds it here once.
 *
 * Each is built on first use, never at composition: nothing here reads [identity] or touches the network until a
 * caller asks, which is what a locked background launch depends on.
 */
class BackendServices(
    val backend: AuthenticatedBackend,
    private val identity: PersistedDeviceIdentity,
) {
    val directory: EventDirectory by lazy { BackendEventDirectory(backend) }
    val creation: EventCreation by lazy { BackendEventCreation(backend) }
    val rename: EventRename by lazy { BackendEventRename(backend) }
    val join: EventJoin by lazy { BackendEventJoin(backend) }
    val manifest: ManifestPublisher by lazy { BackendManifestPublisher(backend) }
    val leave: LeaveNotifier by lazy { BackendLeaveNotifier(backend, identity) }
    val union: EventUnionSource by lazy { BackendEventUnionSource(backend) }
    val deviceFiles: DeviceFilesSource by lazy { BackendDeviceFilesSource(backend) }
    val pushTokens: PushTokenPublisher by lazy { BackendPushTokenPublisher(backend, identity) }
}
