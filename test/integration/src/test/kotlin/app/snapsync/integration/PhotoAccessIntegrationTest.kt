package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Given
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.PhotoGrant
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.DirectionCount
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.SyncHealth
import app.snapsync.ui.state.UiIntent
import kotlin.test.Test

/**
 * **Photo access** (`openspec/specs/photo-access/spec.md`), one scenario per test, over the real composed stack.
 *
 * A photo is *shared* here when it **leaves the device**: the app hands it to an upload — its own session's transfer,
 * tagged with the photo's upload key ([primaryKey]), or the operating system's job ([primaryJob]). That the backend then
 * holds it is the delivery spec's, and no event names which photo the backend stored yet; these tests claim only which
 * photos the app puts in scope, which is what this spec decides.
 */
class PhotoAccessIntegrationTest {

    // ---- missing access never hides or blocks the event -----------------------------------------------------------

    @Test
    @Verifies(
        spec = "photo-access",
        requirement = "Missing access never hides or blocks the event",
        scenario = "Denied access keeps the event usable",
    )
    fun a_refused_member_keeps_every_membership_action() = behaviour {
        // The explanation's words ("Nothing is shared", "Nothing is received") are the screen's, worded from this same
        // NeedsAccess: StatusScreenTest pins them.
        given {
            refused()
            createdEvent()
            step("joined", act = { confirmJoin() }, until = {
                screen<Layer.Joined> { it.health == SyncHealth.NeedsAccess(canAsk = false) && it.inviteUrl != null }
            })
        }

        whenever { tap(UiIntent.ShareInvite) }
        then { event<WorldEvent.Shared>() }

        whenever { tap(UiIntent.OpenSettings) }
        then { event<WorldEvent.SettingsOpened>() }

        whenever { rename("Renamed") }
        then { screen<Layer.Joined> { it.membership.name == "Renamed" } }

        whenever { tap(UiIntent.LeaveEvent) }
        then {
            event<WorldEvent.BackendReceived> { it.operation == "leaveEvent" }
            screen<Layer.CreateEvent>()
        }
    }

    @Test
    @Verifies(
        spec = "photo-access",
        requirement = "Missing access never hides or blocks the event",
        scenario = "Granting access later starts sharing",
    )
    fun granting_access_from_the_access_line_starts_sharing() = behaviour {
        given {
            joinedEvent()
            // Undecided again, as a privacy reset in the phone's Settings leaves it: the access line may ask.
            step(
                "access undecided again",
                act = {
                    library.permission(PhotoGrant.NOT_DETERMINED)
                    library.requestAnswer(PhotoGrant.GRANTED)
                    os.foreground()
                },
                until = { screen<Layer.Joined> { it.health == SyncHealth.NeedsAccess(canAsk = true) } },
            )
            photos("A")
        }

        whenever { tap(UiIntent.RequestPermission) }
        then {
            event<WorldEvent.LibraryAccessAsked>()
            screen<Layer.Joined> { it.health !is SyncHealth.NeedsAccess }
            event<WorldEvent.SessionTransferCreated> { it.key == primaryKey("A") }
        }
    }

    // ---- limited access is a working membership whose scope is the selection --------------------------------------

    @Test
    @Verifies(
        spec = "photo-access",
        requirement = "Limited access is a working membership whose scope is the selection",
        scenario = "Selected photos share through the ordinary rules",
    )
    fun only_the_selected_photos_inside_the_range_leave_the_device() = behaviour {
        given {
            limitedMember()
            step("photos inside and before the range", act = {
                takePhoto("IN")
                library.add("BEFORE", date = BEFORE_THE_RANGE)
            }, until = { settled { true } })
        }

        whenever { library.changeSelection(listOf("IN", "BEFORE")) }
        then {
            event<WorldEvent.SessionTransferCreated> { it.key == primaryKey("IN") }
            never<WorldEvent.SessionTransferCreated> { it.key == primaryKey("BEFORE") }
            never<WorldEvent.UploadJobCreated> { it.job == primaryJob("BEFORE") }
        }
    }

    @Test
    @Verifies(
        spec = "photo-access",
        requirement = "Limited access is a working membership whose scope is the selection",
        scenario = "A photo outside the selection never leaves the device",
    )
    fun a_photo_outside_the_selection_stays_until_it_is_selected() = behaviour {
        given {
            limitedMember()
            photos("A")
            selected("A")
        }

        // A new photo, outside the selection: the app in front, then a heartbeat and the extension in the background.
        whenever {
            takePhoto("B")
            os.foreground()
            os.background()
            heartbeat()
            cycle()
        }
        then {
            never<WorldEvent.SessionTransferCreated> { it.key == primaryKey("B") }
            never<WorldEvent.UploadJobCreated> { it.job == primaryJob("B") }
        }

        // Added to the selection, it leaves like any selected photo — the never above watched a live app.
        whenever { library.changeSelection(listOf("A", "B")) }
        then { event<WorldEvent.SessionTransferCreated> { it.key == primaryKey("B") } }
    }

    @Test
    @Verifies(
        spec = "photo-access",
        requirement = "Limited access is a working membership whose scope is the selection",
        scenario = "Up to date over the selection",
    )
    fun every_selected_photo_shared_and_every_received_one_arrived_is_up_to_date() = behaviour {
        given {
            limitedMember()
            photos("A", "UNSELECTED")
            selected("A")
            aMembersPhotoArrived()
        }

        whenever {
            uploadSession.complete()
            os.foreground()
        }
        then { screen<Layer.Joined> { it.health == SyncHealth.InSync } }
    }

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "The joined screen counts what was shared and received",
        scenario = "Limited access shows counts",
    )
    fun up_to_date_under_limited_access_counts_the_selection_beside_the_choice_to_widen() = behaviour {
        given {
            limitedMember()
            photos("A", "B", "C", "D", "E", "F", "UNSELECTED")
            // The upload session takes a few at a time: the first ones land, and the app hands it the rest.
            step("six selected", act = { library.changeSelection(listOf("A", "B", "C", "D", "E", "F")) }, until = {
                settled { true }
            })
            step("the first uploads landed", act = {
                uploadSession.complete()
                os.foreground()
            }, until = { settled { true } })
        }

        whenever {
            uploadSession.complete()
            os.foreground()
        }
        then {
            screen<Layer.Joined> {
                it.health == SyncHealth.InSync &&
                    it.counts?.shared == DirectionCount.Progress(done = 6, total = 6) &&
                    it.canChoosePhotos
            }
        }
    }

    // ---- the app never raises iOS's limited-library prompt itself --------------------------------------------------

    @Test
    @Verifies(
        spec = "photo-access",
        requirement = "The app never raises iOS's limited-library prompt itself",
        scenario = "Normal use under limited access",
    )
    fun sharing_and_receiving_under_limited_access_presents_no_picker_until_the_member_taps() = behaviour {
        // A host proxy: iOS's own prompt is the platform's (both processes opt out of it in their Info.plists), so this holds
        // what the app can do — it never presents the picker or asks for access itself, in front or behind.
        given {
            limitedMember()
            photos("A", "B")
            selected("A")
        }

        whenever {
            uploadSession.complete()
            publicBackend.foreignDevice(DEV_F, listOf(FOREIGN_ASSET))
            os.foreground()
            downloadSession.finish()
            library.changeSelection(listOf("A", "B"))
            os.background()
            heartbeat()
            cycle()
        }
        then {
            event<WorldEvent.LibraryAssetCreated>()
            event<WorldEvent.SessionTransferCreated> { it.key == primaryKey("B") }
            never<WorldEvent.LibraryPickerShown>()
            never<WorldEvent.LibraryAccessAsked>()
        }

        // Only the member's own tap on "Choose more photos" presents it — the never above watched a live read.
        whenever { tap(UiIntent.ChoosePhotos) }
        then { event<WorldEvent.LibraryPickerShown>() }
    }

    // ---- receive-only with limited access is never nagged ---------------------------------------------------------

    @Test
    @Verifies(
        spec = "photo-access",
        requirement = "Receive-only with limited access is never nagged",
        scenario = "An empty selection still receives",
    )
    fun an_empty_selection_receives_uploads_nothing_and_is_not_asked_to_pick() = behaviour {
        given {
            limitedMember()
            photos("A")
        }

        whenever {
            library.changeSelection(emptyList())
            publicBackend.foreignDevice(DEV_F, listOf(FOREIGN_ASSET))
            os.foreground()
        }
        then {
            event<WorldEvent.DownloadStarted>()
            never<WorldEvent.SessionTransferCreated>()
            never<WorldEvent.UploadJobCreated>()
            never<WorldEvent.LibraryPickerShown>()
            neverScreen<Layer.Joined> { it.health is SyncHealth.NeedsAccess }
        }

        whenever { downloadSession.finish() }
        then {
            event<WorldEvent.LibraryAssetCreated>()
            never<WorldEvent.SessionTransferCreated>()
            never<WorldEvent.LibraryPickerShown>()
            neverScreen<Layer.Joined> { it.health is SyncHealth.NeedsAccess }
        }
    }

    // ---- the app offers its own routes to widen limited access ----------------------------------------------------

    @Test
    @Verifies(
        spec = "photo-access",
        requirement = "The app offers its own routes to widen limited access",
        scenario = "Choosing more photos shares them",
    )
    fun choosing_more_photos_presents_the_picker_and_the_added_photos_leave() = behaviour {
        given {
            limitedMember()
            photos("A", "B")
            selected("A")
        }

        whenever { tap(UiIntent.ChoosePhotos) }
        then { event<WorldEvent.LibraryPickerShown>() }

        // The person adds B in the picker; the platform delivers the widened selection.
        whenever { library.changeSelection(listOf("A", "B")) }
        then {
            event<WorldEvent.SessionTransferCreated> { it.key == primaryKey("B") }
            never<WorldEvent.SessionTransferCreated> { it.key == primaryKey("A") }
        }
    }

    @Test
    @Verifies(
        spec = "photo-access",
        requirement = "The app offers its own routes to widen limited access",
        scenario = "Allow full access goes straight to Settings",
    )
    fun allow_full_access_opens_settings_and_asks_nothing() = behaviour {
        given { limitedMember() }

        whenever { tap(UiIntent.OpenSettings) } // "Allow full access" is the Settings route (JoinedExplanation)
        then {
            event<WorldEvent.SettingsOpened>()
            never<WorldEvent.LibraryAccessAsked>()
            never<WorldEvent.LibraryPickerShown>()
        }
    }

    @Test
    @Verifies(
        spec = "photo-access",
        requirement = "The app offers its own routes to widen limited access",
        scenario = "Full access shows neither choice",
    )
    fun neither_choice_is_offered_under_full_access_or_none() = behaviour {
        given { limitedMember() }

        whenever {
            library.permission(PhotoGrant.GRANTED)
            os.foreground()
        }
        then { screen<Layer.Joined> { !it.canChoosePhotos && it.health !is SyncHealth.NeedsAccess } }

        whenever {
            library.permission(PhotoGrant.DENIED)
            os.foreground()
        }
        then { screen<Layer.Joined> { !it.canChoosePhotos && it.health is SyncHealth.NeedsAccess } }
    }

    // ---- access changes made in the phone's Settings are picked up on return --------------------------------------

    @Test
    @Verifies(
        spec = "photo-access",
        requirement = "Access changes made in the phone's Settings are picked up on return",
        scenario = "Revoking access in Settings",
    )
    fun access_turned_off_in_settings_asks_for_it_on_return() = behaviour {
        given { joinedEvent() }

        whenever {
            library.permission(PhotoGrant.DENIED)
            os.foreground()
        }
        then { screen<Layer.Joined> { it.health == SyncHealth.NeedsAccess(canAsk = false) } }
    }

    @Test
    @Verifies(
        spec = "photo-access",
        requirement = "Access changes made in the phone's Settings are picked up on return",
        scenario = "Granting access in Settings",
    )
    fun access_turned_on_in_settings_clears_the_line_and_starts_sharing_on_return() = behaviour {
        given {
            refused()
            createdEvent()
            step("joined", act = { confirmJoin() }, until = {
                screen<Layer.Joined> { it.health is SyncHealth.NeedsAccess }
            })
            photos("A")
        }

        whenever {
            library.permission(PhotoGrant.GRANTED)
            os.foreground()
        }
        then {
            screen<Layer.Joined> { it.health !is SyncHealth.NeedsAccess }
            event<WorldEvent.SessionTransferCreated> { it.key == primaryKey("A") }
        }
    }

    // ---- switching between full and limited access ------------------------------------------------------------------

    @Test
    @Verifies(
        spec = "photo-access",
        requirement = "Switching between full and limited access re-uploads only what was de-selected",
        scenario = "Widening to full access",
    )
    fun widening_to_full_access_sends_only_the_newly_in_scope_photos() = behaviour {
        given {
            limitedMember()
            photos("A", "B")
            selected("A")
            step("A shared", act = { uploadSession.complete() }, until = { settled { true } })
        }

        // The app's uploader first, settled before the extension runs: the extension's `process()` answers on the
        // operating system's own thread, outside the host's loop, so one act holding both would race them for B.
        whenever {
            library.permission(PhotoGrant.GRANTED)
            os.foreground()
        }
        then {
            event<WorldEvent.SessionTransferCreated> { it.key == primaryKey("B") }
            never<WorldEvent.SessionTransferCreated> { it.key == primaryKey("A") }
        }

        whenever { cycle() }
        then {
            never<WorldEvent.SessionTransferCreated> { it.key == primaryKey("A") }
            never<WorldEvent.UploadJobCreated> { it.job == primaryJob("A") }
        }
    }

    // ---- the dialog is raised only by a deliberate tap --------------------------------------------------------------

    @Test
    @Verifies(
        spec = "photo-access",
        requirement = "The system's access dialog is raised only by a deliberate tap",
        scenario = "Opening the app does not prompt",
    )
    @Verifies(
        spec = "sync-status",
        requirement = "Only missing access and a blocked network make the status line tappable",
        scenario = "Never asked",
    )
    fun opening_the_app_with_access_undecided_asks_nothing_until_the_access_line_is_tapped() = behaviour {
        given {
            joinedEvent()
            step("access never decided", act = { library.permission(PhotoGrant.NOT_DETERMINED) }, until = {})
        }

        whenever { os.foreground() }
        then {
            screen<Layer.Joined> { it.health == SyncHealth.NeedsAccess(canAsk = true) }
            never<WorldEvent.LibraryAccessAsked>()
        }

        whenever { tap(UiIntent.RequestPermission) }
        then { event<WorldEvent.LibraryAccessAsked>() }
    }

    @Test
    @Verifies(
        spec = "photo-access",
        requirement = "The system's access dialog is raised only by a deliberate tap",
        scenario = "A refused member is taken to Settings",
    )
    @Verifies(
        spec = "sync-status",
        requirement = "Only missing access and a blocked network make the status line tappable",
        scenario = "Previously refused",
    )
    fun a_refused_member_tapping_the_access_line_is_taken_to_settings_and_asked_nothing() = behaviour {
        given {
            joinedEvent()
            step(
                "access refused",
                act = {
                    library.permission(PhotoGrant.DENIED)
                    os.foreground()
                },
                // The line offers Settings, not the dialog: what it sends then is the screens' own (`JoinedLayer`).
                until = { screen<Layer.Joined> { it.health == SyncHealth.NeedsAccess(canAsk = false) } },
            )
        }

        whenever { tap(UiIntent.OpenSettings) }
        then {
            event<WorldEvent.SettingsOpened>()
            never<WorldEvent.LibraryAccessAsked>()
        }
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    /** The member refused photo access before joining. */
    private suspend fun Given.refused() =
        step("access refused", act = { library.permission(PhotoGrant.DENIED) }, until = {})

    /** A fellow member's photo, downloaded and saved in this member's library. */
    private suspend fun Given.aMembersPhotoArrived() {
        step(
            "a member's photo on its way",
            act = {
                publicBackend.foreignDevice(DEV_F, listOf(FOREIGN_ASSET))
                os.foreground()
            },
            until = {
                event<WorldEvent.DownloadStarted>()
                settled { true }
            },
        )
        step("it arrived", act = { downloadSession.finish() }, until = {
            event<WorldEvent.LibraryAssetCreated>()
            settled { true }
        })
    }

    private companion object {
        /** A capture time before the default event's range (`Defaults.WINDOW_START`). */
        const val BEFORE_THE_RANGE = "2026-05-01T10:00:00Z"
        const val FOREIGN_ASSET = "FQ"
    }
}
