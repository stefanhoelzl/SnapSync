package app.snapsync.integration

import app.snapsync.control.Choices
import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Acts
import app.snapsync.control.behaviour.Behaviour
import app.snapsync.control.behaviour.Given
import app.snapsync.control.behaviour.Seen
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.BackendFault
import app.snapsync.levers.KeychainItem
import app.snapsync.levers.NetworkState
import app.snapsync.levers.PhotoGrant
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.Arrow
import app.snapsync.ui.state.DirectionCount
import app.snapsync.ui.state.EventTiming
import app.snapsync.ui.state.JoinedSurface
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.NetworkNotice
import app.snapsync.ui.state.SharingNote
import app.snapsync.ui.state.SyncCounts
import app.snapsync.ui.state.SyncHealth
import app.snapsync.ui.state.TimeLeft
import app.snapsync.ui.state.UiIntent
import kotlin.test.Test
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/**
 * **The joined screen's status** (capability `sync-status`), over the real composed stack: the one status line and the
 * order its conditions outrank each other in, the arrows and the counts, how long the event lasts and when it has
 * ended, how many members it has, and that the screen stays whole until the event's photos are deleted.
 *
 * The clock starts on Monday 1 June 2026 at noon, in the device's zone, UTC; the default event runs from 15 May to
 * 14 June.
 */
class StatusLineIntegrationTest {

    // ---- one status line in a fixed priority ----------------------------------------------------

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "The joined screen shows one status line in a fixed priority",
        scenario = "Missing access outranks a future start",
    )
    fun missing_access_is_told_before_a_future_start() = behaviour {
        given { joinedEvent(startsAt = TOMORROW, endsAt = IN_THREE_DAYS) }

        whenever {
            library.permission(PhotoGrant.DENIED)
            readAgain()
        }
        then {
            screen<Layer.Joined> { it.health == SyncHealth.NeedsAccess(canAsk = false) }
            settled { true }
        }
    }

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "The joined screen shows one status line in a fixed priority",
        scenario = "A lost key outranks missing access",
    )
    fun a_lost_key_is_told_before_missing_access() = behaviour {
        given { joinedEvent() }

        whenever {
            keychain.forget(KeychainItem.EVENT_KEY)
            library.permission(PhotoGrant.DENIED)
            readAgain()
        }
        then {
            screen<Layer.Joined> { it.health == SyncHealth.KeyLost }
            settled { true }
        }
    }

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "The joined screen shows one status line in a fixed priority",
        scenario = "A future start outranks progress",
    )
    fun a_future_start_is_told_whatever_work_is_outstanding() = behaviour {
        given { joinedEvent(startsAt = TOMORROW, endsAt = IN_THREE_DAYS) }

        whenever {
            publicBackend.foreignDevice(DEV_F, listOf("F1"))
            readAgain()
        }
        then {
            screen<Layer.Joined> { it.health == SyncHealth.NotStarted }
            neverScreen<Layer.Joined> { it.health is SyncHealth.Syncing }
            settled { true }
        }
    }

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "Only missing access and a blocked network make the status line tappable",
        scenario = "Network blocked",
    )
    fun a_blocked_network_line_opens_settings() = behaviour {
        given {
            joinedEvent()
            inFront()
        }

        whenever {
            network(NetworkState.BLOCKED)
            advance(NETWORK_GRACE)
        }
        then { screen<Layer.Joined> { it.health == SyncHealth.NoNetwork(NetworkNotice.BLOCKED) } }

        whenever { tap(UiIntent.OpenSettings) }
        then { event<WorldEvent.SettingsOpened>() }
    }

    // ---- a not-yet-started event ----------------------------------------------------------------

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "A not-yet-started event says when it starts",
        scenario = "Before the start",
    )
    fun an_event_two_days_ahead_says_it_starts_in_two_days() = behaviour {
        given { joinedEvent(startsAt = IN_TWO_DAYS, endsAt = IN_FOUR_DAYS) }

        whenever { readAgain() }
        then {
            screen<Layer.Joined> {
                it.health == SyncHealth.NotStarted && it.timing == EventTiming.Upcoming(TimeLeft.Days(2))
            }
        }
    }

    // ---- a device that cannot be verified -------------------------------------------------------

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "A device that cannot be verified is shown, and never blamed on the member",
        scenario = "Offline with an expired verification",
    )
    fun an_expired_verification_offline_says_offline() = behaviour {
        given { proofExpiredInThePocket() }

        whenever {
            network(NetworkState.OFFLINE)
            os.foreground()
            advance(NETWORK_GRACE)
        }
        then {
            screen<Layer.Joined> { it.health == SyncHealth.NoNetwork(NetworkNotice.OFFLINE) }
            neverScreen<Layer.Joined> { it.health is SyncHealth.Unattested }
        }
    }

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "A device that cannot be verified is shown, and never blamed on the member",
        scenario = "Opening the app retries before alarming",
    )
    fun an_opening_after_a_failed_background_renewal_retries_before_saying_anything() = behaviour {
        given {
            proofExpiredInThePocket()
            step("a background renewal refused", act = {
                listOf("challenge", "mintToken", "renewToken").forEach { backend.fail(it, BackendFault.CLOSED, 10_000) }
                heartbeat()
            }, until = { settled { true } })
            step("the service works again", act = { backend.recover() }, until = {})
        }

        whenever {
            os.foreground()
            advance(STATUS_POLL)
        }
        then {
            event<WorldEvent.BackendReceived> { it.operation in setOf("challenge", "mintToken", "renewToken") }
            neverScreen<Layer.Joined> { it.health is SyncHealth.Unattested }
            settled { true }
        }
    }

    // ---- the network ----------------------------------------------------------------------------

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "The app says when it cannot reach the network",
        scenario = "Airplane mode",
    )
    fun no_network_at_all_says_offline() = behaviour {
        given {
            joinedEvent()
            inFront()
        }

        whenever {
            network(NetworkState.OFFLINE)
            advance(NETWORK_GRACE)
        }
        then { screen<Layer.Joined> { it.health == SyncHealth.NoNetwork(NetworkNotice.OFFLINE) } }
    }

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "The app says when it cannot reach the network",
        scenario = "A brief drop shows nothing",
    )
    fun a_drop_shorter_than_the_grace_says_nothing() = behaviour {
        given { inFrontInSync() }

        whenever {
            network(NetworkState.OFFLINE)
            advance(BRIEF_DROP)
            network(NetworkState.ONLINE)
            advance(NETWORK_GRACE)
        }
        then {
            neverScreen<Layer.Joined> { it.health is SyncHealth.NoNetwork }
            settled { true }
        }
    }

    // ---- "Up to date" only once the app has looked ----------------------------------------------

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "\"Up to date\" is never claimed before the app has looked",
        scenario = "A short visit never shows a false settle",
    )
    fun a_visit_shorter_than_the_first_read_never_says_up_to_date() = behaviour {
        given {
            joinedEvent()
            photos("P1")
            step("the library slow to read", act = { library.holdEnumeration(true) }, until = {})
        }

        whenever {
            host.relaunch()
            os.foreground()
            os.background()
        }
        then {
            neverScreen<Layer.Joined> { it.health == SyncHealth.InSync }
            settled { true }
        }

        whenever { library.holdEnumeration(false) }
        then { settled { true } }
    }

    // ---- the arrows -----------------------------------------------------------------------------

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "Direction arrows show the work left and the transfer running",
        scenario = "Photos arriving",
    )
    fun photos_downloading_pulse_the_download_arrow_alone() = behaviour {
        given { inFrontInSync() }

        whenever {
            publicBackend.foreignDevice(DEV_F, listOf("F1"))
            readAgain()
        }
        then {
            event<WorldEvent.DownloadStarted>()
            screen<Layer.Joined> { it.health == SyncHealth.Syncing(upload = Arrow.HIDDEN, download = Arrow.PULSING) }
        }
    }

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "Direction arrows show the work left and the transfer running",
        scenario = "The device's own waiting work comes first",
    )
    fun a_photo_waiting_here_reads_queued_before_waiting_for_others() = behaviour {
        given {
            extensionUploadsOnly()
            joinedEvent()
        }

        whenever {
            publicBackend.foreignDevice(DEV_F, listOf("X"), uploading = listOf("X"))
            takePhoto("P1")
            readAgain()
        }
        then {
            screen<Layer.Joined> {
                it.health == SyncHealth.Syncing(upload = Arrow.STATIC, download = Arrow.STATIC, waitingForOthers = false)
            }
        }
    }

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "Direction arrows show the work left and the transfer running",
        scenario = "Reaching Wi-Fi replaces the waiting line",
    )
    fun reaching_wifi_turns_the_waiting_line_into_photos_on_their_way() = behaviour {
        given {
            step("only the app uploads", act = { devControls.pin(app = true, extension = false) }, until = {})
            joinedEvent()
            step("mobile data off, on mobile data", act = {
                tap(UiIntent.MobileData(false))
                network.set(NetworkState.RESTRICTED)
            }, until = { settled { true } })
            step("P1 waiting for Wi-Fi", act = {
                takePhoto("P1")
                readAgain()
            }, until = {
                screen<Layer.Joined> { (it.health as? SyncHealth.Syncing)?.waitingForWifi == true }
                settled { true }
            })
        }

        whenever {
            network.set(NetworkState.ONLINE)
            advance(STATUS_POLL)
        }
        then {
            screen<Layer.Joined> {
                (it.health as? SyncHealth.Syncing)?.let { health -> !health.waitingForWifi && health.upload == Arrow.PULSING } == true
            }
        }
    }

    // ---- what the counts include ----------------------------------------------------------------

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "Progress counts only what this membership shares",
        scenario = "New contributions reopen the download side",
    )
    fun a_new_photo_from_another_member_reopens_the_download_side_until_it_arrives() = behaviour {
        given { inFrontInSync() }

        whenever {
            publicBackend.foreignDevice(DEV_F, listOf("FA"))
            readAgain()
        }
        then {
            screen<Layer.Joined> { (it.health as? SyncHealth.Syncing)?.download?.let { arrow -> arrow != Arrow.HIDDEN } == true }
        }

        whenever {
            downloadSession.finish()
            advance(STATUS_POLL)
        }
        then { screen<Layer.Joined> { it.health == SyncHealth.InSync } }
    }

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "Progress counts only what this membership shares",
        scenario = "A withdrawn photo stops counting",
    )
    fun a_photo_withdrawn_before_it_arrived_leaves_the_total() = behaviour {
        given {
            joinedEvent()
            step("two of three arrived, the third still uploading there", act = {
                publicBackend.foreignDevice(DEV_F, listOf("A", "B", "C"), uploading = listOf("C"))
                os.foreground()
                downloadSession.finish()
                advance(STATUS_POLL)
            }, until = {
                screen<Layer.Joined> { it.counts?.received == DirectionCount.Progress(2, 3) }
                settled { true }
            })
        }

        whenever {
            publicBackend.foreignDevice(DEV_F, listOf("A", "B"))
            readAgain()
        }
        then { screen<Layer.Joined> { it.counts?.received == DirectionCount.Progress(2, 2) } }
    }

    // ---- how long the event lasts ---------------------------------------------------------------

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "The joined screen shows how long the event lasts",
        scenario = "A running event counts down in days",
    )
    fun a_running_event_counts_its_days_left() = behaviour(startAt = TWELFTH_OF_JULY_EVENING) {
        given { joinedEvent(startsAt = JULY_START, endsAt = JULY_END) }

        whenever { readAgain() }
        then {
            screen<Layer.Joined> {
                it.timing == EventTiming.Running(TimeLeft.Days(2)) &&
                    it.membership.startsAt.date.toString() == "2026-07-12" && it.membership.endsAt.date.toString() == "2026-07-14"
            }
        }
    }

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "The joined screen shows how long the event lasts",
        scenario = "The last day counts in hours, then minutes",
    )
    fun the_last_day_counts_hours_then_minutes() = behaviour(startAt = TWELFTH_OF_JULY_EVENING) {
        given { joinedEvent(startsAt = JULY_START, endsAt = JULY_END) }

        whenever {
            os.background()
            advanceTo(FIVE_HOURS_BEFORE_THE_JULY_END)
            reopened()
        }
        then {
            screen<Layer.Joined> {
                it.timing == EventTiming.Running(TimeLeft.Hours(5))
            }
        }

        whenever {
            os.background()
            advanceTo(FORTY_MINUTES_BEFORE_THE_JULY_END)
            reopened()
        }
        then {
            screen<Layer.Joined> {
                it.timing == EventTiming.Running(TimeLeft.Minutes(40))
            }
        }
    }

    // ---- the counts -----------------------------------------------------------------------------

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "The joined screen counts the photos shared and received",
        scenario = "Syncing in both directions",
    )
    fun counts_in_both_directions_show_done_of_total() = behaviour {
        given {
            step("only the app uploads", act = { devControls.pin(app = true, extension = false) }, until = {})
            joinedEvent()
            step("three photos on their way, two of them shared", act = {
                listOf("P1", "P2", "P3").forEach { takePhoto(it) }
                os.foreground()
            }, until = {
                times(3) { event<WorldEvent.SessionTransferCreated>() }
                settled { true }
            })
            step("P1 shared", act = { uploadSession.complete(primaryKey("P1")) }, until = { settled { true } })
            step("P2 shared", act = { uploadSession.complete(primaryKey("P2")) }, until = { settled { true } })
            step("two of three of the others' arrived", act = {
                publicBackend.foreignDevice(DEV_F, listOf("A", "B", "C"), uploading = listOf("C"))
                os.foreground()
                downloadSession.finish()
            }, until = { settled { true } })
        }

        whenever { readAgain() }
        then {
            screen<Layer.Joined> {
                it.health is SyncHealth.Syncing &&
                    it.counts == SyncCounts(shared = DirectionCount.Progress(2, 3), received = DirectionCount.Progress(2, 3))
            }
        }
    }

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "The joined screen counts the photos shared and received",
        scenario = "Limited access shows counts",
    )
    fun a_limited_member_up_to_date_sees_what_they_shared() = behaviour {
        given {
            limitedMember()
            photos("S1", "S2", "S3")
            selected("S1", "S2", "S3")
            step("all three shared", act = { uploadSession.complete() }, until = { settled { true } })
        }

        whenever { readAgain() }
        then {
            screen<Layer.Joined> {
                it.health == SyncHealth.InSync && it.canChoosePhotos && it.counts?.shared == DirectionCount.Progress(3, 3)
            }
        }
    }

    // ---- staying current ------------------------------------------------------------------------

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "The status stays current while the app is open",
        scenario = "A background upload finishes while the app is open",
    )
    fun an_upload_the_extension_finishes_reaches_the_open_screen() = behaviour {
        given {
            extensionUploadsOnly()
            joinedEvent()
            step("a photo waiting, the app in front", act = {
                takePhoto("P1")
                readAgain()
            }, until = {
                screen<Layer.Joined> { it.health is SyncHealth.Syncing }
                settled { true }
            })
        }

        whenever {
            cycle()
            uploadQueue.completeJob()
            cycle()
            advance(STATUS_POLL)
        }
        then { screen<Layer.Joined> { it.health == SyncHealth.InSync } }
    }

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "The status stays current while the app is open",
        scenario = "Photos discovered after opening leave \"Up to date\"",
    )
    fun a_photo_discovered_while_open_leaves_up_to_date_within_two_seconds() = behaviour {
        given { inFrontInSync() }

        whenever {
            publicBackend.foreignDevice(DEV_F, listOf("FA"))
            os.foreground()
            advance(2.seconds)
        }
        then { screen<Layer.Joined> { it.health is SyncHealth.Syncing } }
    }

    // ---- an ended event -------------------------------------------------------------------------

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "An ended event is marked without stopping sync",
        scenario = "Ended and still syncing",
    )
    fun an_ended_event_with_a_photo_waiting_is_marked_and_still_uploads() = behaviour(startAt = DURING_MAY) {
        given {
            extensionUploadsOnly()
            joinedEvent(startsAt = MAY_START, endsAt = MAY_END)
            photoTaken("P1", date = TAKEN_IN_MAY)
            laterInThePocket(AFTER_MAY_END)
        }

        whenever { reopened() }
        then {
            screen<Layer.Joined> {
                it.timing == EventTiming.Ended && it.health == SyncHealth.Syncing(Arrow.STATIC, Arrow.HIDDEN)
            }
        }

        whenever { uploadAll() }
        then { event<WorldEvent.BytesStored> { it.asset == "P1" } }
    }

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "An ended event is marked without stopping sync",
        scenario = "Waiting for the others",
    )
    fun after_the_end_up_to_date_names_how_many_members_have_not_finished() = behaviour(startAt = DURING_MAY) {
        given {
            extensionUploadsOnly()
            joinedEvent(startsAt = MAY_START, endsAt = MAY_END)
            received(DEV_F, "FQ", final = true)
            step("one member done, another not", act = {
                publicBackend.foreignDeviceReceived(DEV_F)
                publicBackend.foreignDevice(DEV_G, listOf("GQ"), final = false)
                os.foreground()
                downloadSession.finish()
            }, until = { settled { true } })
            laterInThePocket(AFTER_MAY_END)
            step("this device's share settled", act = { cycle() }, until = { settled { true } })
        }

        whenever { reopened() }
    }

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "An ended event is marked without stopping sync",
        scenario = "Waiting for the others' photos after the end",
    )
    fun after_the_end_a_member_still_uploading_is_waited_for() = behaviour(startAt = DURING_MAY) {
        given {
            extensionUploadsOnly()
            joinedEvent(startsAt = MAY_START, endsAt = MAY_END)
            laterInThePocket(AFTER_MAY_END)
            step("this device's share settled", act = { cycle() }, until = { settled { true } })
        }

        whenever {
            publicBackend.foreignDevice(DEV_F, listOf("X"), uploading = listOf("X"))
            reopened()
        }
        then {
            screen<Layer.Joined> {
                (it.health as? SyncHealth.Syncing)?.waitingForOthers == true && it.sharingNote == SharingNote.WaitingFor(1)
            }
        }
    }

    // ---- the explanation, and the screen staying whole ------------------------------------------

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "The joined screen explains how the event works for this member",
        scenario = "The range the member chose is the one named",
    )
    fun the_joined_screen_names_the_range_the_member_chose() = behaviour {
        given { joinedEvent(Choices(cutoff = LATER_START)) }

        whenever { readAgain() }
        then { screen<Layer.Joined> { it.membership.sharesFrom.date.toString() == LATER_START_DAY } }

        whenever { reconfigure(Choices(cutoff = EARLIER_START)) }
        then { screen<Layer.Joined> { it.membership.sharesFrom.date.toString() == EARLIER_START_DAY } }
    }

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "The joined screen is whole until the event is deleted",
        scenario = "Before the start everything is available",
    )
    fun before_the_start_every_action_works() = behaviour {
        given { joinedEvent(startsAt = TOMORROW, endsAt = IN_THREE_DAYS) }
        everyActionWorks()
    }

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "The joined screen is whole until the event is deleted",
        scenario = "After the end everything is still available",
    )
    fun after_the_end_every_action_still_works() = behaviour(startAt = DURING_MAY) {
        given {
            joinedEvent(startsAt = MAY_START, endsAt = MAY_END)
            laterInThePocket(THREE_DAYS_AFTER_MAY_END)
            inFront()
        }
        everyActionWorks()
    }

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "The joined screen is whole until the event is deleted",
        scenario = "A deleted event is shown as over",
    )
    fun a_deleted_event_keeps_its_counts_and_drops_its_members() = behaviour {
        lateinit var before: Seen<Layer.Joined>
        val event = given {
            joinedEvent().also {
                received(DEV_F, "FQ")
                step("the counts read", act = { readAgain() }, until = {
                    before = screen<Layer.Joined> { it.counts != null && it.members != null }
                    settled { true }
                })
            }
        }

        whenever {
            backend.complete(event)
            readAgain()
        }
        then { screen<Layer.Joined> { it.deleted && it.members == null && it.counts == before.value.counts } }
    }

    // ---- the member count -----------------------------------------------------------------------

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "The joined screen shows how many members the event has",
        scenario = "The host alone",
    )
    fun a_host_alone_counts_one_member() = behaviour {
        given { joinedEvent() }

        whenever { readAgain() }
        then { screen<Layer.Joined> { it.members == 1 } }
    }

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "The joined screen shows how many members the event has",
        scenario = "Guests joined",
    )
    @Verifies(
        spec = "sync-status",
        requirement = "The joined screen shows how many members the event has",
        scenario = "A member leaves",
    )
    fun guests_joining_and_leaving_change_the_member_count() = behaviour {
        given { joinedEvent() }

        whenever {
            listOf(DEV_F, DEV_G, DEV_H).forEach { publicBackend.foreignDevice(it, listOf("${it.take(4)}-1")) }
            readAgain()
        }
        then { screen<Layer.Joined> { it.members == 4 } }

        whenever {
            publicBackend.foreignDeviceLeave(DEV_H)
            readAgain()
        }
        then { screen<Layer.Joined> { it.members == 3 } }
    }

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "The joined screen shows how many members the event has",
        scenario = "Shown without counts too",
    )
    fun the_member_count_shows_without_photo_access() = behaviour {
        given { joinedEvent() }

        whenever {
            library.permission(PhotoGrant.DENIED)
            readAgain()
        }
        then { screen<Layer.Joined> { it.health is SyncHealth.NeedsAccess && it.counts == null && it.members == 1 } }
    }

    // ---- until when guests can join ------------------------------------------------------------

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "A back-dated event says until when guests can join",
        scenario = "A regular event",
    )
    fun a_regular_event_says_nothing_about_joining() = behaviour {
        given { joinedEvent() }

        whenever { readAgain() }
        then { screen<Layer.Joined> { it.joinableUntil == null } }
    }

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "A back-dated event says until when guests can join",
        scenario = "The day has passed",
    )
    fun the_joinable_until_line_goes_once_its_day_has_passed() = behaviour {
        given {
            createdEvent(startsAt = LAST_WEEKEND_START, endsAt = LAST_WEEKEND_END)
            step("joined, the screen saying until when", act = { confirmJoin() }, until = {
                screen<Layer.Joined> { it.joinableUntil != null }
                settled { true }
            })
            laterInThePocket(DAY_AFTER_THE_OPEN_UNTIL)
        }

        whenever { reopened() }
        then { screen<Layer.Joined> { it.joinableUntil == null } }
    }

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "Progress counts only what this membership shares",
        scenario = "Earlier uploads do not mask this event's work",
    )
    fun photos_shared_to_an_earlier_event_still_count_as_work_for_this_one() = behaviour {
        given {
            extensionUploadsOnly()
            joinedEvent(name = "Last week")
            photos("A", "B", "C")
            uploaded("A", "B", "C")
            left()
            joinedEvent(name = "This week")
        }

        whenever { readAgain() }
        then {
            screen<Layer.Joined> {
                it.membership.name == "This week" && it.counts?.shared == DirectionCount.Progress(0, 3) &&
                    (it.health as? SyncHealth.Syncing)?.upload == Arrow.STATIC
            }
        }

        whenever {
            uploadAll()
            readAgain()
        }
        then { screen<Layer.Joined> { it.health == SyncHealth.InSync && it.counts?.shared == DirectionCount.Progress(3, 3) } }
    }

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "An ended event is marked without stopping sync",
        scenario = "A late guest turns it back",
    )
    fun a_late_guest_sharing_turns_everyone_shared_back_into_waiting() = behaviour(startAt = DURING_MAY) {
        given {
            extensionUploadsOnly()
            joinedEvent(startsAt = MAY_START, endsAt = MAY_END)
            received(DEV_F, "FQ", final = true)
            step("the fellow member holds everything", act = { publicBackend.foreignDeviceReceived(DEV_F) }, until = {})
            laterInThePocket(AFTER_MAY_END)
            step("this device's share settled", act = { cycle() }, until = { settled { true } })
            step("everyone has shared", act = { reopened() }, until = {
                screen<Layer.Joined> { it.sharingNote == SharingNote.EveryoneShared }
                settled { true }
            })
        }

        whenever {
            publicBackend.foreignDevice(DEV_G, listOf("G1"), uploading = listOf("G1"))
            readAgain()
        }
        then { screen<Layer.Joined> { it.sharingNote == SharingNote.WaitingFor(1) } }
    }

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "A back-dated event says until when guests can join",
        scenario = "A back-dated event",
    )
    fun a_back_dated_event_says_until_when_guests_can_join() = behaviour(startAt = TWELFTH_OF_OCTOBER) {
        given { createdEvent(startsAt = OCTOBER_WEEKEND_START, endsAt = OCTOBER_WEEKEND_END) }

        whenever { confirmJoin() }
        then {
            screen<Layer.Joined> {
                it.ended && it.joinableUntil?.date?.toString() == "2026-10-19" &&
                    it.membership.startsAt.date.toString() == "2026-10-02" && it.membership.endsAt.date.toString() == "2026-10-04"
            }
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    /**
     * The app opened afresh — what a member who comes back later meets: the joined screen's clock-driven lines read the
     * time as it is now. (The host's mocked clock does not drive the screen's minute tick, so a screen left open does not
     * follow the clock here.)
     */
    private suspend fun Acts.reopened() {
        host.relaunch()
        os.foreground()
        advance(STATUS_POLL)
    }

    /** The app in front long enough for the joined screen's status poll to read the device again. */
    private suspend fun Acts.readAgain() {
        os.foreground()
        advance(STATUS_POLL)
    }

    /** Joined, in front, and up to date. */
    private suspend fun Given.inFrontInSync() {
        joinedEvent()
        step("in front, up to date", act = { readAgain() }, until = {
            screen<Layer.Joined> { it.health == SyncHealth.InSync }
            settled { true }
        })
    }

    /** Joined, then left unopened longer than the device's verification lasts. */
    private suspend fun Given.proofExpiredInThePocket() {
        joinedEvent()
        inFront()
        step("in the background", act = { os.background() }, until = { settled { true } })
        step("left unopened longer than the proof lasts", act = { advance(31.days) }, until = { settled { true } })
    }

    /** Sharing the invite, showing its QR code, the settings, a rename and leaving all work on the joined screen. */
    private suspend fun Behaviour.everyActionWorks() {
        whenever { tap(UiIntent.ShareInvite) }
        then { event<WorldEvent.Shared>() }

        whenever { tap(UiIntent.QrOpen) }
        then { event<WorldEvent.UiChanged> { it.ui.overlays.showingQr } }

        whenever { tap(UiIntent.QrDismiss, UiIntent.OpenReconfigure) }
        then { screen<Layer.Joined> { it.surface is JoinedSurface.Reconfigure } }

        whenever {
            tap(UiIntent.CancelReconfigure)
            rename("Still here")
        }
        then { event<WorldEvent.EventRenamed> { it.name == "Still here" } }

        whenever { tap(UiIntent.LeaveEvent) }
        then { event<WorldEvent.MemberLeft>() }
    }

    private companion object {
        /** Long enough for the joined screen's status poll (every 2 s) to read again. */
        val STATUS_POLL = 5.seconds

        /** Longer than the network watch's grace before it reports a change, and a drop shorter than it. */
        val NETWORK_GRACE = 6.seconds
        val BRIEF_DROP = 3.seconds

        const val DEV_G = "9e570000-0000-4000-8000-0000000000a7"
        const val DEV_H = "9e570000-0000-4000-8000-0000000000a8"

        /** Dates around the clock's start, 1 June at noon. */
        const val TOMORROW = "2026-06-02T00:00:00"
        const val IN_TWO_DAYS = "2026-06-03T14:00:00"
        const val IN_THREE_DAYS = "2026-06-04T00:00:00"
        const val IN_FOUR_DAYS = "2026-06-05T00:00:00"

        /** Starts within the default window, one later than the other. */
        const val LATER_START = "2026-05-25T00:00:00Z"
        const val LATER_START_DAY = "2026-05-25"
        const val EARLIER_START = "2026-05-18T00:00:00Z"
        const val EARLIER_START_DAY = "2026-05-18"

        /** An event from 12 July at midnight to 14 July at 23:00, device time, and moments around it in UTC. */
        const val JULY_START = "2026-07-12T00:00:00"
        const val JULY_END = "2026-07-14T23:00:00"
        const val TWELFTH_OF_JULY_EVENING = "2026-07-12T16:00:00Z"
        const val FIVE_HOURS_BEFORE_THE_JULY_END = "2026-07-14T18:00:00Z"
        const val FORTY_MINUTES_BEFORE_THE_JULY_END = "2026-07-14T22:20:00Z"

        /** A short event in May, and moments around it. */
        const val MAY_START = "2026-05-25T00:00:00"
        const val MAY_END = "2026-05-29T00:00:00"
        const val DURING_MAY = "2026-05-26T12:00:00Z"
        const val TAKEN_IN_MAY = "2026-05-26T10:00:00Z"
        const val AFTER_MAY_END = "2026-05-30T12:00:00Z"
        const val THREE_DAYS_AFTER_MAY_END = "2026-06-01T00:00:00Z"

        /** A weekend over by the clock's start, created a week open, and a day past that week. */
        const val LAST_WEEKEND_START = "2026-05-22T18:00:00"
        const val LAST_WEEKEND_END = "2026-05-24T20:00:00"
        const val DAY_AFTER_THE_OPEN_UNTIL = "2026-06-09T12:00:00Z"

        /** On 12 October, an event for the weekend of 2 to 4 October, created with a week to join. */
        const val TWELFTH_OF_OCTOBER = "2026-10-12T10:00:00Z"
        const val OCTOBER_WEEKEND_START = "2026-10-02T18:00:00"
        const val OCTOBER_WEEKEND_END = "2026-10-04T20:00:00"
    }
}
