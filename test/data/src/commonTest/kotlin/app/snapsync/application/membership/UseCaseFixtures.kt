package app.snapsync.application.membership

import app.snapsync.data.backend.BackendGateway
import app.snapsync.data.backend.ScriptedCredential
import app.snapsync.data.backend.ScriptedTransport
import app.snapsync.data.backend.exchangeOver
import app.snapsync.data.identity.DeviceIdRead
import app.snapsync.data.identity.DeviceIdentity
import app.snapsync.data.membership.InviteKeyCheck
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.data.membership.sharedOver
import app.snapsync.data.time.WallClock
import app.snapsync.domain.event.EventKeyId
import app.snapsync.domain.invite.InviteKey
import app.snapsync.mock.ClockMock
import app.snapsync.mock.DatabasesMock
import app.snapsync.testids.testDeviceId
import kotlin.time.Instant

internal const val DEVICE_ID = "11111111-0000-4000-8000-000000000002"

/** A device as a use case sees it: its store, the backend behind a scripted transport, its clock and identity. */
internal class Device {
    val databases = DatabasesMock()
    val shared = sharedOver(databases)
    val membership = MembershipRepository(shared)
    val transport = ScriptedTransport()
    val gateway = BackendGateway(exchangeOver(transport), ScriptedCredential("T1"))
    val clock = ClockMock(Instant.parse("2026-07-22T12:00:00Z"))
    val wallClock = WallClock(clock.port())
    val identity = FixedIdentity(DeviceIdRead.Ok(testDeviceId(DEVICE_ID), DeviceIdRead.Via.READ))
}

internal class FixedIdentity(var read: DeviceIdRead) : DeviceIdentity {
    override fun id(): DeviceIdRead = read
}

/** The key check as the event keys answer it: a key opens exactly the event whose key id is its text. */
internal object KeyIsItsId : InviteKeyCheck {
    override fun opens(key: InviteKey?, keyId: EventKeyId?): Boolean = key?.text == keyId?.value
}
