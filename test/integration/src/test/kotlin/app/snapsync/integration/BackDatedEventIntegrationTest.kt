package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Acts
import app.snapsync.control.behaviour.Given
import app.snapsync.control.behaviour.Seen
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.SettledEvent
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.SharingNote
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * An event created after its end, over the REAL composition (decision record `changes/back-dated-events`): the host
 * creates it for a weekend already over and keeps it open for a week, a guest joins days later and shares, both have
 * everything, nothing ends the host's membership, and once the sweep deletes the photos the host is shown the event as
 * over. Beside it, a member on a build that predates the received report: it sees the event closed once everyone has
 * shared, leaves on its own as it always did, and holds nothing up.
 */
class BackDatedEventIntegrationTest {

    @Test
    @Verifies(
        spec = "event-lifetime",
        requirement = "An event's photos are deleted once it is finished, and after 30 days at the latest",
        scenario = "A back-dated event",
    )
    fun a_weekend_already_over_is_shared_until_it_is_deleted() = behaviour {
        // Created on 1 June for a weekend in May; the sheet is answered with a week.
        val event = given { hostOfTheWeekend() }

        whenever { uploadAll() }
        then { event<WorldEvent.UnionChanged> { it.asset == "HOST-1" && it.gained } }

        // Day 2: a guest scans the invite, joins and shares the photo they took that weekend.
        whenever {
            awayUntil(DAY_TWO)
            publicBackend.foreignDevice(GUEST, listOf("GUEST-1"), final = true)
            os.foreground()
        }
        then {
            event<WorldEvent.UnionChanged> { it.asset == "GUEST-1" && it.gained } // both members' photos are in the event
            event<WorldEvent.DownloadStarted>()
            settled { true }
        }
        lateinit var shared: Seen<Layer.Joined>
        lateinit var library: Seen<WorldEvent.Settled>
        whenever {
            downloadSession.finish()
            os.foreground()
            advance(STATUS_POLL)
        }
        then {
            event<WorldEvent.LibraryAssetCreated> { it.asset == "imported-$GUEST-GUEST-1" }
            shared = screen<Layer.Joined> { it.sharingNote == SharingNote.EveryoneShared }
            library = settled { true }
        }
        assertEquals(2, shared.value.members, "the joined screen counts both members")

        // Everyone has everything, but the week the host chose is not over: the event is not finished.
        whenever {
            publicBackend.foreignDeviceReceived(GUEST)
            os.foreground()
        }
        then { settled { it.of(event)?.finished == false } } // the open-until date has not passed

        // After the week, with everyone done, the event is finished — and the host is still in it.
        whenever {
            awayUntil(AFTER_THE_WEEK)
            os.foreground()
        }
        then { settled { it.of(event)?.finished == true } }
        whenever { os.foreground() }
        then {
            never<WorldEvent.UiChanged> { it.ui.layer !is Layer.Joined } // a finished event never ends a membership
            settled { true }
        }

        // The sweep deletes the photos: the host is shown the event as over, with the guest's photo still in the
        // library.
        lateinit var over: Seen<Layer.Joined>
        whenever {
            backend.complete(event)
            os.foreground()
        }
        then {
            over = screen<Layer.Joined> { it.deleted }
            // the membership stays until the member leaves
            never<WorldEvent.UiChanged> { it.ui.layer !is Layer.Joined }
            // deletion touches no photo in the library
            settled { it.state.libraryPhotos.orEmpty().toSet() == library.value.state.libraryPhotos.orEmpty().toSet() }
        }
        assertNull(over.value.members, "a deleted event shows no member count")
    }

    @Test
    @Verifies(
        spec = "event-lifetime",
        requirement = "An event's photos are deleted once it is finished, and after 30 days at the latest",
    )
    fun a_member_on_an_older_build_leaves_once_everyone_shared_and_holds_nothing_up() = behaviour {
        val event = given {
            hostOfTheWeekend().also {
                uploaded("HOST-1")
                // A member whose build predates the received report: it shares, and never says what it received.
                step("a member on an older build shares", act = {
                    publicBackend.foreignDevice(OLD_BUILD, listOf("OLD-1"), final = true)
                    os.foreground()
                }, until = {
                    event<WorldEvent.DownloadStarted>()
                    settled { true }
                })
                step("its photo arrived", act = { downloadSession.finish() }, until = {
                    event<WorldEvent.LibraryAssetCreated>()
                    settled { true }
                })
            }
        }

        // Once everyone still in the event has shared, the older build is told the event closed — what it waits for…
        whenever {
            awayUntil(AFTER_THE_WEEK)
            os.foreground()
        }
        // …and as it never reports what it received, the event waits on it.
        then { settled { it.of(event)?.closedForOldBuilds == true && it.of(event)?.finished == false } }

        // It leaves on its own, as that build always has; nothing it never reported holds the event up any more.
        whenever {
            publicBackend.foreignDeviceLeave(OLD_BUILD)
            os.foreground()
        }
        then {
            never<WorldEvent.UiChanged> { it.ui.layer !is Layer.Joined } // this member is still in the event
            settled { it.of(event)?.finished == true }
        }
    }

    /**
     * The host's photo of the weekend in the library, and the weekend's event created on 1 June and joined, kept open for
     * a week. Answers its id.
     */
    private suspend fun Given.hostOfTheWeekend(): String {
        extensionUploadsOnly()
        photoTaken("HOST-1", date = IN_THE_WEEKEND)
        val event = createdEvent(startsAt = WEEKEND_START, endsAt = WEEKEND_END)
        step("joined", act = { confirmJoin() }, until = {
            screen<Layer.Joined> { it.joinableUntil != null } // the joined screen says until when others can join
            settled { true }
        })
        return event
    }

    /** The member puts the phone away until [at]. */
    private suspend fun Acts.awayUntil(at: String) {
        os.background()
        advanceTo(at)
    }

    /** What the backend derives of [event] at this settle. */
    private fun WorldEvent.Settled.of(event: String): SettledEvent? = state.backendEvents?.get(event)

    private companion object {
        const val GUEST = "9e570000-0000-4000-8000-000000000001"
        const val OLD_BUILD = "01db0000-0000-4000-8000-000000000001"

        // A weekend in May — over by the time every test's clock starts on 1 June.
        const val WEEKEND_START = "2026-05-08T18:00:00"
        const val WEEKEND_END = "2026-05-10T20:00:00"
        const val IN_THE_WEEKEND = "2026-05-09T12:00:00Z"

        /** Two days after the event was created (`NOW`), inside the week the host kept it open. */
        const val DAY_TWO = "2026-06-03T12:00:00Z"

        /** Past the week the host chose (created 1 June 12:00, open for 7 days). */
        const val AFTER_THE_WEEK = "2026-06-09T12:00:00Z"

        /** Long enough for the joined screen's status poll (every 2 s) to read the event again. */
        val STATUS_POLL = 5.seconds
    }
}
