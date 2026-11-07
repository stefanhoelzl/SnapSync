package app.snapsync.data.crash

import app.snapsync.data.identity.DeviceIdRead
import app.snapsync.data.identity.DeviceIdentity
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.domain.crash.ReportedIds
import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.membership.MembershipRead
import app.snapsync.domain.report.Fact
import app.snapsync.domain.report.ProcessName
import app.snapsync.ports.BuildInfo

/**
 * The ids a report carries about SnapSync, read now: the event the [membership] holds, the device id as it stands —
 * **never minted**, a report must not create the identity it reports — and the process, named as its log names it.
 */
class ReportedIdsRead(
    private val membership: MembershipRepository,
    private val identity: DeviceIdentity,
    private val build: BuildInfo,
) {
    fun now(): ReportedIds = ReportedIds(
        event = when (val held = membership.state.value) {
            is MembershipRead.Joined -> held.membership.event.id
            is MembershipRead.NotJoined, is MembershipRead.Unavailable -> null
        },
        device = device(identity.current()),
        process = ProcessName.of(build.processId, build.platform.tag),
    )

    /** The device id as a report writes it: the id, or why there is none. */
    private fun device(read: DeviceIdRead): Fact<DeviceId> = when (read) {
        is DeviceIdRead.Ok -> Fact.Known(read.value)
        is DeviceIdRead.Unavailable -> Fact.Failed("the secure store could not be read: ${read.detail}")
        DeviceIdRead.Absent -> Fact.Failed("no device id stored yet")
    }
}
