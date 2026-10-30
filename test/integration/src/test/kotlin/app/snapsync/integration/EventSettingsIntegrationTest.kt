package app.snapsync.integration

import app.snapsync.control.Choices
import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Given
import app.snapsync.control.behaviour.Seen
import app.snapsync.control.behaviour.behaviour
import app.snapsync.domain.sharing.Direction
import app.snapsync.levers.NetworkState
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.AlbumHolds
import app.snapsync.ui.state.JoinedSurface
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.OutsideReach
import app.snapsync.ui.state.RangeChoice
import app.snapsync.ui.state.SyncHealth
import app.snapsync.ui.state.UiIntent
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * **An event's settings, changed as they are made** (capability `manage-membership`), over the real composed stack:
 * what the settings open on, which changes ask first, that each applies as it is made with nothing to save, and that
 * the member stays in the event throughout — offline, after the end, and until the photos are deleted.
 *
 * The default event runs from 15 May to 14 June; the clock starts on 1 June.
 */
class EventSettingsIntegrationTest {

    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "Settings explain what a change does",
        scenario = "Switching sharing off asks first",
    )
    fun switching_sharing_off_asks_before_anything_changes() = behaviour {
        given {
            joinedEvent()
            settingsOpen()
        }

        whenever { tap(UiIntent.ShareOn(false)) }
        then {
            screen<Layer.Joined> { joined -> joined.settings()?.let { it.askingToStopSharing && it.range.shareOn } == true }
            neverScreen<Layer.Joined> { !it.membership.shares }
            never<WorldEvent.ManifestPublished> { it.withdrawn.isNotEmpty() }
            settled { true }
        }
    }

    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "Settings explain what a change does",
        scenario = "Narrowing is described honestly",
    )
    fun narrowing_asks_before_the_range_changes() = behaviour {
        given {
            joinedEvent()
            settingsOpen()
        }

        whenever { setRange(Choices(cutoff = LATER)) }
        then {
            screen<Layer.Joined> { it.settings()?.askingToStopSharing == true }
            neverScreen<Layer.Joined> { it.membership.sharesFrom.date.toString() != WINDOW_START_DAY }
            settled { true }
        }
    }

    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "Settings explain what a change does",
        scenario = "Keeping sharing changes nothing",
    )
    fun keeping_sharing_leaves_the_switch_on_and_withdraws_nothing() = behaviour {
        given {
            joinedEvent()
            settingsOpen()
            step("asked whether to stop sharing", act = { tap(UiIntent.ShareOn(false)) }, until = {
                screen<Layer.Joined> { it.settings()?.askingToStopSharing == true }
            })
        }

        whenever { tap(UiIntent.KeepSharing) }
        then {
            screen<Layer.Joined> { joined -> joined.settings()?.let { !it.askingToStopSharing && it.range.shareOn } == true }
            neverScreen<Layer.Joined> { !it.membership.shares }
            never<WorldEvent.ManifestPublished> { it.withdrawn.isNotEmpty() }
            settled { true }
        }
    }

    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "Settings explain what a change does",
        scenario = "Widening does not ask",
    )
    fun moving_the_start_earlier_applies_without_a_question() = behaviour {
        given {
            joinedEvent(Choices(cutoff = LATER))
            settingsOpen()
        }

        whenever { setRange(Choices(cutoff = EARLIER)) }
        then {
            screen<Layer.Joined> { it.membership.sharesFrom.date.toString() == EARLIER_DAY }
            neverScreen<Layer.Joined> { it.settings()?.askingToStopSharing == true }
        }
    }

    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "Settings explain what a change does",
        scenario = "Album-on mentions photos already synced",
    )
    fun turning_the_album_on_says_it_collects_what_was_already_synced() = behaviour {
        given {
            joinedEvent(Choices(saveToAlbum = false))
            settingsOpen()
        }

        whenever { tap(UiIntent.SaveToAlbum(true)) }
        then {
            screen<Layer.Joined> { joined ->
                joined.settings()?.range?.let { it.saveToAlbum && it.albumHolds == AlbumHolds.SHARED_AND_RECEIVED } == true
            }
        }
    }

    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "A settings change takes effect as soon as it applies",
        scenario = "Moving the start earlier shares older photos",
    )
    fun moving_the_start_earlier_shares_the_newly_included_photos_and_keeps_the_rest() = behaviour {
        given {
            extensionUploadsOnly()
            joinedEvent(Choices(cutoff = LATER))
            photoTaken("OLD", date = TAKEN_BETWEEN)
            photoTaken("NEW")
            uploaded("NEW")
        }

        whenever {
            reconfigure(Choices(cutoff = EARLIER))
            uploadAll()
        }
        then {
            event<WorldEvent.BytesStored> { it.asset == "OLD" }
            event<WorldEvent.ManifestPublished> { "OLD" in it.added }
            never<WorldEvent.ManifestPublished> { "NEW" in it.withdrawn }
        }
    }

    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "Any member renames the event for everyone until its photos are deleted",
        scenario = "A rename leaves everything else alone",
    )
    fun a_rename_changes_the_name_and_nothing_else() = behaviour {
        lateinit var before: Seen<Layer.Joined>
        given {
            joinedEvent(Choices(direction = Direction.UploadOnly, cutoff = LATER))
            step("the joined screen read", act = { os.foreground() }, until = {
                before = screen<Layer.Joined>()
                settled { true }
            })
        }

        whenever { rename("Ana's 30th") }
        then {
            screen<Layer.Joined> {
                it.membership == before.value.membership.copy(name = "Ana's 30th") && it.inviteUrl == before.value.inviteUrl
            }
            never<WorldEvent.ManifestPublished>()
            never<WorldEvent.MemberLeft>()
            settled { true }
        }
    }

    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "Event settings change as they are made, without leaving, until the event's photos are deleted",
        scenario = "Settings open pre-filled",
    )
    fun the_settings_open_on_the_membership_as_it_is() = behaviour {
        given { joinedEvent(Choices(saveToAlbum = true)) }

        whenever { tap(UiIntent.OpenReconfigure) }
        then {
            screen<Layer.Joined> { joined ->
                joined.settings()?.range?.let {
                    it.shareOn && it.receiveOn && it.saveToAlbum && it.preset == RangeChoice.EVENT_START &&
                        it.from == joined.membership.sharesFrom && it.until == joined.membership.sharesUntil
                } == true
            }
        }
    }

    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "Event settings change as they are made, without leaving, until the event's photos are deleted",
        scenario = "A change applies without a Save",
    )
    fun the_album_turned_on_stays_on_after_closing_and_reopening() = behaviour {
        given {
            joinedEvent(Choices(saveToAlbum = false))
            settingsOpen()
        }

        whenever {
            tap(UiIntent.SaveToAlbum(true))
            tap(UiIntent.CancelReconfigure)
        }
        then { screen<Layer.Joined> { it.surface == JoinedSurface.Status && it.membership.savesToAlbum } }

        whenever { tap(UiIntent.OpenReconfigure) }
        then { screen<Layer.Joined> { it.settings()?.range?.saveToAlbum == true } }
    }

    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "Event settings change as they are made, without leaving, until the event's photos are deleted",
        scenario = "Closing changes nothing further",
    )
    fun closing_untouched_settings_leaves_the_membership_as_it_was() = behaviour {
        lateinit var before: Seen<Layer.Joined>
        given {
            joinedEvent()
            step("the joined screen read", act = { os.foreground() }, until = {
                before = screen<Layer.Joined>()
                settled { true }
            })
        }

        whenever {
            tap(UiIntent.OpenReconfigure)
            tap(UiIntent.CancelReconfigure)
        }
        then {
            screen<Layer.Joined> { it.surface == JoinedSurface.Status && it.membership == before.value.membership }
            neverScreen<Layer.Joined> { it.membership != before.value.membership }
            never<WorldEvent.ManifestPublished>()
            settled { true }
        }
    }

    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "Event settings change as they are made, without leaving, until the event's photos are deleted",
        scenario = "The last of several quick changes stands",
    )
    fun the_last_of_three_quick_flips_of_receiving_stands() = behaviour {
        given {
            joinedEvent()
            settingsOpen()
        }

        whenever {
            tap(UiIntent.ReceiveOn(false), UiIntent.ReceiveOn(true), UiIntent.ReceiveOn(false))
            tap(UiIntent.CancelReconfigure)
        }
        then {
            screen<Layer.Joined> { it.surface == JoinedSurface.Status && !it.membership.receives }
            settled { true }
        }

        whenever {
            publicBackend.foreignDevice(DEV_F, listOf("FQ"))
            os.foreground()
        }
        then {
            never<WorldEvent.DownloadStarted>()
            settled { true }
        }
    }

    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "Event settings change as they are made, without leaving, until the event's photos are deleted",
        scenario = "Both directions may be off",
    )
    fun a_receive_only_member_turning_receiving_off_stays_in_the_event_doing_nothing() = behaviour {
        given { joinedEvent(Choices(direction = Direction.DownloadOnly)) }

        whenever {
            reconfigure(Choices(direction = Direction.Neither))
            os.foreground()
            advance(STATUS_POLL)
        }
        then {
            screen<Layer.Joined> { !it.membership.shares && !it.membership.receives && it.health == SyncHealth.Inactive }
            never<WorldEvent.MemberLeft>()
            neverScreen<Layer.CreateEvent>()
        }
    }

    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "Event settings change as they are made, without leaving, until the event's photos are deleted",
        scenario = "A range narrower than the event shows as custom",
    )
    fun a_range_starting_after_the_event_opens_as_custom() = behaviour {
        given { joinedEvent(Choices(cutoff = LATER)) }

        whenever { tap(UiIntent.OpenReconfigure) }
        then {
            screen<Layer.Joined> { joined ->
                joined.settings()?.range?.let {
                    it.preset == RangeChoice.CUSTOM && it.from.date.toString() == LATER_DAY && it.until == it.windowEnd
                } == true
            }
        }
    }

    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "Event settings change as they are made, without leaving, until the event's photos are deleted",
        scenario = "A start before the event's start is kept",
    )
    fun a_start_before_the_event_is_kept_and_said() = behaviour(startAt = DURING_THE_SHORT_EVENT) {
        given { joinedEvent(startsAt = SHORT_START, endsAt = SHORT_END) }

        whenever { reconfigure(Choices(cutoff = BEFORE_SHORT_START)) }
        then { screen<Layer.Joined> { it.membership.sharesFrom.date.toString() == BEFORE_SHORT_START_DAY } }

        whenever { tap(UiIntent.OpenReconfigure) }
        then { screen<Layer.Joined> { it.settings()?.range?.outside == OutsideReach.BEFORE } }
    }

    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "Event settings change as they are made, without leaving, until the event's photos are deleted",
        scenario = "Settings change offline",
    )
    fun a_setting_changed_offline_applies_and_the_member_stays() = behaviour {
        given {
            joinedEvent()
            step("offline", act = { network(NetworkState.OFFLINE) }, until = { settled { true } })
        }

        whenever { reconfigure(Choices(direction = Direction.UploadOnly)) }
        then {
            screen<Layer.Joined> { !it.membership.receives && it.membership.shares }
            never<WorldEvent.MemberLeft>()
            neverScreen<Layer.CreateEvent>()
            settled { true }
        }
    }

    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "Event settings change as they are made, without leaving, until the event's photos are deleted",
        scenario = "Settings still change after the end",
    )
    fun turning_receiving_on_after_the_end_brings_the_events_photos() = behaviour(startAt = DURING_THE_SHORT_EVENT) {
        given {
            joinedEvent(Choices(direction = Direction.UploadOnly), startsAt = SHORT_START, endsAt = SHORT_END)
            fellowMember(DEV_F, "FQ")
            laterInThePocket(AFTER_THE_SHORT_END)
            inFront()
        }

        whenever { reconfigure(Choices(direction = Direction.Both)) }
        then { event<WorldEvent.DownloadStarted>() }

        whenever { downloadSession.finish() }
        then { times(1) { event<WorldEvent.LibraryAssetCreated> { it.asset.startsWith(imported("FQ")) } } }
    }

    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "Event settings change as they are made, without leaving, until the event's photos are deleted",
        scenario = "A deleted event offers no settings",
    )
    fun a_deleted_event_opens_no_settings() = behaviour {
        given { joinedEvent().also { deletedOnBackend(it) } }

        whenever {
            tap(UiIntent.OpenReconfigure)
            advance(1.seconds)
        }
        then {
            neverScreen<Layer.Joined> { it.surface is JoinedSurface.Reconfigure }
            settled { true }
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** The event's settings open over the joined screen. */
    private suspend fun Given.settingsOpen() = step(
        "the settings open",
        act = { tap(UiIntent.OpenReconfigure) },
        until = {
            screen<Layer.Joined> { it.surface is JoinedSurface.Reconfigure }
            settled { true }
        },
    )

    /** The settings surface, when it is open. */
    private fun Layer.Joined.settings(): JoinedSurface.Reconfigure? = surface as? JoinedSurface.Reconfigure

    private companion object {

        const val WINDOW_START_DAY = "2026-05-15"

        /** A start inside the default window, later than its first day. */
        const val LATER = "2026-05-25T00:00:00Z"
        const val LATER_DAY = "2026-05-25"

        /** A start between the window's first day and [LATER]. */
        const val EARLIER = "2026-05-18T00:00:00Z"
        const val EARLIER_DAY = "2026-05-18"

        /** Between [EARLIER] and [LATER]: shared only once the start moves to [EARLIER]. */
        const val TAKEN_BETWEEN = "2026-05-20T10:00:00Z"

        /** A short event, and a start five days before it. */
        const val SHORT_START = "2026-05-25T00:00:00"
        const val SHORT_END = "2026-05-29T00:00:00"
        const val BEFORE_SHORT_START = "2026-05-20T00:00:00Z"
        const val BEFORE_SHORT_START_DAY = "2026-05-20"
        const val DURING_THE_SHORT_EVENT = "2026-05-26T12:00:00Z"

        /** Two days past [SHORT_END], before the event may be deleted. */
        const val AFTER_THE_SHORT_END = "2026-05-31T12:00:00Z"

        /** Long enough for the joined screen's status poll (every 2 s) to read again. */
        val STATUS_POLL = 5.seconds
    }
}
