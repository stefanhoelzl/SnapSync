package app.snapsync.feature.membership

import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.event.EventId
import app.snapsync.model.JoinResult
import app.snapsync.services.backend.EventJoin
import app.snapsync.testids.testDeviceId
import app.snapsync.testids.testEventId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The enroller is now a join and nothing else (capability `join-event`).
 *
 * What these tests replace is worth stating, because the old ones were correct about a design that has
 * gone: enrolment used to PUT a **register-only empty manifest**, which made it a second writer of a
 * document the upload cycle owns. It therefore had to reach into the manifest store and invalidate the
 * producer's skip-if-unchanged record, purely to repair the blank it had just written. Both the wound
 * and the repair are gone — the join carries no body, so a rejoining device's asset set survives
 * untouched and there is no window in which the event union lists none of its photos.
 */
class ManifestDeviceEnrollerTest {

    private class CapturingJoin(private val result: JoinResult = JoinResult.Joined) : EventJoin {
        val calls = mutableListOf<Pair<EventId, DeviceId>>()
        override suspend fun join(eventId: EventId, deviceId: DeviceId): JoinResult {
            calls += eventId to deviceId
            return result
        }
    }

    @Test
    fun enrolling_issues_one_join_for_the_event_and_device() = runTest {
        val join = CapturingJoin()

        assertEquals(JoinResult.Joined, ManifestDeviceEnroller(join).enroll(testEventId("E"), testDeviceId("dev")))

        assertEquals(listOf(testEventId("E") to testDeviceId("dev")), join.calls)
    }

    @Test
    fun a_closed_event_does_not_enrol() = runTest {
        // Passed through rather than flattened, so the join surface can say WHY: a closed event is a refusal
        // the user can act on, and a transport failure is not (capability `join-event`).
        assertEquals(
            JoinResult.EventClosed,
            ManifestDeviceEnroller(
                CapturingJoin(JoinResult.EventClosed),
            ).enroll(testEventId("E"), testDeviceId("dev")),
        )
    }

    @Test
    fun an_absent_event_does_not_enrol() = runTest {
        assertEquals(
            JoinResult.EventNotFound,
            ManifestDeviceEnroller(
                CapturingJoin(JoinResult.EventNotFound),
            ).enroll(testEventId("E"), testDeviceId("dev")),
        )
    }

    @Test
    fun a_transport_failure_does_not_enrol() = runTest {
        // No config is saved and no producer enabled on a failed join — there is no half-joined state.
        assertEquals(
            JoinResult.Failed(),
            ManifestDeviceEnroller(CapturingJoin(JoinResult.Failed())).enroll(testEventId("E"), testDeviceId("dev")),
        )
    }
}
