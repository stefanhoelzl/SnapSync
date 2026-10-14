package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.PhotoGrant
import app.snapsync.levers.WorldEvent
import kotlin.test.Test
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * **The heartbeat stays pending while the device is joined, busy or idle** (decision record
 * `changes/timely-background-receiving`, D1), as the operating system takes the app's request after a heartbeat wake.
 * The JVM host carries no OS uploader, so its full-grant sharer is the one that notices its own photos only by looking
 * — iOS below 26.1; the confirmed-uploader row is `WakeRearmTest`'s and `HeartbeatCadenceTest`'s.
 *
 * Every request the wake makes is the cadence asked for — never the other one, which a later request would replace it
 * with.
 */
class HeartbeatCadenceIntegrationTest {

    @Test
    @Verifies(
        spec = "delivery",
        requirement = "Photos travel without the app being opened",
        scenario = "Uploads work below iOS 26.1",
    )
    fun a_full_grant_sharer_keeps_looking_during_the_event() = behaviour(startAt = DURING) {
        given {
            photoAccess(PhotoGrant.GRANTED)
            joinedEvent("direction" to "upload", startsAt = SHORT_START, endsAt = SHORT_END)
            // A second member that never settles keeps the event open after its end, so this one stays joined.
            fellowMember(DEV_F, "FQ")
        }
        whenever { heartbeat() }
        then {
            event<WorldEvent.WakeScheduled> { it.wake.cadence == BUSY }
            never<WorldEvent.WakeScheduled> { it.wake.cadence != BUSY }
        }
    }

    @Test
    @Verifies(
        spec = "delivery",
        requirement = "Only new photos are announced by a silent wake, and never only by it",
        scenario = "No wake arrives",
    )
    fun a_full_grant_sharer_looks_in_hourly_after_the_end() = behaviour(startAt = DURING) {
        given {
            photoAccess(PhotoGrant.GRANTED)
            joinedEvent("direction" to "upload", startsAt = SHORT_START, endsAt = SHORT_END)
            fellowMember(DEV_F, "FQ")
            step("past the end", act = { advance(UNTIL_AFTER_THE_END) }, until = { settled { true } })
        }
        // After the end nothing new can enter the event, so it idles — and looks in hourly.
        whenever { heartbeat() }
        then {
            event<WorldEvent.WakeScheduled> { it.wake.cadence == IDLE && it.wake.earliestSeconds == HOURLY }
            never<WorldEvent.WakeScheduled> { it.wake.cadence != IDLE }
        }
    }

    @Test
    @Verifies(
        spec = "delivery",
        requirement = "Only new photos are announced by a silent wake, and never only by it",
        scenario = "No wake arrives",
    )
    fun a_member_who_only_receives_idles_instead_of_keeping_no_wake() = behaviour {
        given { joinedEvent("direction" to "download") }
        whenever { heartbeat() }
        then {
            event<WorldEvent.WakeScheduled> { it.wake.cadence == IDLE }
            never<WorldEvent.WakeScheduled> { it.wake.cadence != IDLE }
        }
    }

    @Test
    @Verifies(
        spec = "delivery",
        requirement = "Only new photos are announced by a silent wake, and never only by it",
        scenario = "No wake arrives",
    )
    fun a_sharer_whose_uploads_are_held_back_idles() = behaviour {
        given {
            joinedEvent()
            photoAccess(PhotoGrant.DENIED)
        }
        // It uploads nothing, but still looks for others' photos.
        whenever { heartbeat() }
        then {
            event<WorldEvent.WakeScheduled> { it.wake.cadence == IDLE }
            never<WorldEvent.WakeScheduled> { it.wake.cadence != IDLE }
        }
    }

    @Test
    @Verifies(
        spec = "delivery",
        requirement = "Only new photos are announced by a silent wake, and never only by it",
        scenario = "No wake arrives",
    )
    fun a_partial_grant_idles_because_a_new_camera_photo_never_joins_the_selection() = behaviour {
        given {
            photoAccess(PhotoGrant.LIMITED)
            joinedEvent()
        }
        whenever { heartbeat() }
        then {
            event<WorldEvent.WakeScheduled> { it.wake.cadence == IDLE }
            never<WorldEvent.WakeScheduled> { it.wake.cadence != IDLE }
        }
    }

    @Test
    @Verifies(spec = "delivery", requirement = "Leaving or switching stops every transfer for the old event")
    fun leaving_withdraws_the_heartbeat() = behaviour {
        given {
            joinedEvent()
            step("a heartbeat run", act = { heartbeat() }, until = { settled { true } })
        }
        whenever { user("leave") }
        then {
            event<WorldEvent.WakeCancelled> { it.wake == HEARTBEAT }
            never<WorldEvent.WakeScheduled> { it.wake.wake == HEARTBEAT }
        }
    }

    private companion object {
        const val HEARTBEAT = "heartbeat"
        const val BUSY = "busy"
        const val IDLE = "idle"
        const val HOURLY = 3600L
        const val SHORT_START = "2026-05-15T00:00:00"
        const val SHORT_END = "2026-05-20T00:00:00"

        /** Inside [SHORT_START]..[SHORT_END]: the event is running. */
        const val DURING = "2026-05-16T12:00:00Z"

        /** From [DURING] to after [SHORT_END], and long before the event's deadline. */
        val UNTIL_AFTER_THE_END = 5.days + 12.hours
    }
}
