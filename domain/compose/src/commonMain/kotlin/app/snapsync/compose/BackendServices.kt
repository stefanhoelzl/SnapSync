package app.snapsync.compose

import app.snapsync.data.backend.BackendGateway
import app.snapsync.data.event.EventDirectory
import app.snapsync.data.event.EventRename
import app.snapsync.data.identity.DeviceIdentity
import app.snapsync.data.membership.EventJoin
import app.snapsync.data.membership.LeaveNotifier
import app.snapsync.data.push.PushEndpointPublisher

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
) {
    val directory: EventDirectory by lazy { EventDirectory(gateway) }
    val rename: EventRename by lazy { EventRename(gateway) }
    val join: EventJoin by lazy { EventJoin(gateway) }
    val leave: LeaveNotifier by lazy { LeaveNotifier(gateway, identity) }
    val push: PushEndpointPublisher by lazy { PushEndpointPublisher(gateway, identity) }
}
