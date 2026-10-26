package app.snapsync.application.membership

import app.snapsync.data.backend.BackendGateway
import app.snapsync.data.backend.ScriptedCredential
import app.snapsync.data.backend.ScriptedTransport
import app.snapsync.data.backend.exchangeOver
import app.snapsync.data.crypto.EventKeyIds
import app.snapsync.data.identity.DeviceIdRead
import app.snapsync.data.identity.DeviceIdentity
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.data.membership.sharedOver
import app.snapsync.data.time.WallClock
import app.snapsync.domain.event.EventKey
import app.snapsync.domain.event.MakesEventKey
import app.snapsync.mock.ClockMock
import app.snapsync.mock.DatabasesMock
import app.snapsync.mock.fakeCrypto
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

/** The key ids, derived for real over a fake platform's primitives. */
internal val testKeyIds = EventKeyIds(fakeCrypto())

/** The key a test names [name]: each name its own key. */
@OptIn(MakesEventKey::class)
internal fun testKey(name: String): EventKey =
    EventKey.of(ByteArray(EventKey.LENGTH) { name.encodeToByteArray()[it % name.length] })!!

/** The id of the key a test names [name], as the event row and the backend hold it. */
internal fun testKeyId(name: String): String = testKeyIds.identify(testKey(name)).id.value
