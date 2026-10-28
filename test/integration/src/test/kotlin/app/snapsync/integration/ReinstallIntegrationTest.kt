package app.snapsync.integration

import app.snapsync.control.Choices
import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Behaviour
import app.snapsync.control.behaviour.Expectations
import app.snapsync.control.behaviour.Given
import app.snapsync.control.behaviour.behaviour
import app.snapsync.domain.sharing.Direction
import app.snapsync.feature.status.NetworkWatch
import app.snapsync.levers.NetworkState
import app.snapsync.levers.PhotoGrant
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.SyncHealth
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Deleting the app and installing it again: the download record goes with the app, and the SnapSync mark on each
 * received photo's name is what the rejoin recognises it by.
 *
 * `device/reinstall` deletes the app's files, databases, user defaults and photo grant and keeps what outlives it —
 * the Keychain, the photo library, the backend — then launches the app again, unjoined.
 */
class ReinstallIntegrationTest {

    @Test
    @Verifies(
        spec = "receiving-photos",
        requirement = "A deleted received photo never comes back",
        scenario = "A reinstall recognises received photos",
    )
    @Verifies(
        spec = "receiving-photos",
        requirement = "Received photos are never shared back",
        scenario = "A reinstall does not share received photos back",
    )
    fun a_rejoin_after_a_reinstall_neither_receives_nor_shares_back_what_the_library_still_holds() = behaviour {
        val event = given {
            extensionUploadsOnly()
            joinedEvent().also { received("FA", "FB") }
        }
        given {
            step("FA deleted from the library", act = { library.remove(imported("FA", OTHER)) }, until = {
                settled { it.state.libraryPhotos == listOf(imported("FB", OTHER)) }
            })
            reinstalled()
            rejoined(event)
        }

        // The photo still held is not received again; the deleted one may be — an accepted gap ("A reinstall may receive
        // again"), so nothing here says whether it is.
        downloadsAll {
            never<WorldEvent.LibraryAssetCreated> { it.asset.startsWith(imported("FB", OTHER)) }
            settled { imported("FB", OTHER) in it.state.libraryPhotos.orEmpty() }
        }

        // No received photo is shared back as the member's own, and nothing of them lands on the backend.
        whenever { cycle() }
        then {
            never<WorldEvent.UploadJobCreated>()
            never<WorldEvent.BytesStored>()
            settled { true }
        }
    }

    @Test
    @Verifies(
        spec = "receiving-photos",
        requirement = "Received photos are never shared back",
        scenario = "A share-only rejoin does not share received photos back",
    )
    fun a_share_only_rejoin_after_a_reinstall_does_not_share_received_photos_back() = behaviour {
        given {
            extensionUploadsOnly()
            val event = joinedEvent().also { received("FA", "FB") }
            reinstalled()
            rejoined(event, Choices(direction = Direction.UploadOnly))
        }

        // A share-only member shares none of the photos it received.
        whenever { cycle() }
        then {
            never<WorldEvent.UploadJobCreated>()
            never<WorldEvent.BytesStored>()
            settled { true }
        }
    }

    @Test
    @Verifies(
        spec = "receiving-photos",
        requirement = "A deleted received photo never comes back",
        scenario = "A reinstall recognises received photos",
    )
    fun an_android_rejoin_recognises_the_received_photos_filed_into_the_event_album() = behaviour {
        // An Android phone's albums are folders, and its event album's folder is no sharing candidate — so the
        // library read that recognises received photos must reach into it.
        given {
            androidLibrary()
            val (event, _) = joinedSavingToAlbum()
            received("FA", "FB")
            reinstalled()
            rejoined(event, Choices(saveToAlbum = true))
        }

        // The album's received photos are recognised, not received again.
        downloadsAll {
            never<WorldEvent.LibraryAssetCreated>()
            settled { it.state.libraryPhotos?.size == 2 }
        }
    }

    @Test
    @Verifies(
        spec = "receiving-photos",
        requirement = "Received photos are never shared back",
        scenario = "On Android a reinstall never shares the album's photos back",
    )
    @Verifies(
        spec = "photo-sharing",
        requirement = "On Android under full access, only photos in the DCIM folder are shared",
        scenario = "An own photo moved into the event album is shared",
    )
    fun an_android_reinstall_shares_no_received_album_photo_back_but_an_own_photo_put_there() = behaviour {
        // The event album's folder is a folder of the default gallery like any other (capability `photo-sharing`): a
        // received photo there is kept out by its mark after a reinstall, as on iPhone — not by its folder — and a photo
        // the member put there themselves is shared.
        given {
            extensionUploadsOnly()
            androidLibrary()
            val (event, album) = joinedSavingToAlbum()
            received("FA", "FB")
            step("both in the album", act = { os.foreground() }, until = {
                settled { it.state.albums?.singleOrNull()?.photos?.size == 2 }
            })
            reinstalled()
            step("an own photo put in the album", act = {
                takePhoto("OWN")
                library.placeIn(album, "OWN")
            }, until = { settled { true } })
            rejoined(event, Choices(saveToAlbum = true))
        }

        whenever { uploadAll() }
        then {
            event<WorldEvent.BytesStored> { it.asset == "OWN" }
            never<WorldEvent.BytesStored> { it.asset != "OWN" }
            settled { true }
        }
    }

    @Test
    @Verifies(
        spec = "receiving-photos",
        requirement = "Received photos are never shared back",
        scenario = "A rejoin that cannot reach the event shares nothing yet",
    )
    fun a_rejoin_that_cannot_read_the_event_shares_nothing_until_it_can() = behaviour {
        val event = given {
            extensionUploadsOnly()
            joinedEvent().also {
                received("FA", "FB")
                reinstalled()
                step("an own photo taken, and the event's photos unreadable", act = {
                    takePhoto("OWN")
                    backend.failUnion(true)
                }, until = { settled { true } })
                rejoinedUnrecognised(it)
            }
        }

        // Nothing is shared before the event's photos have been read, nothing is announced to the others, and the
        // status stays on its neutral line.
        whenever {
            cycle()
            advance(STATUS_POLL)
        }
        then {
            never<WorldEvent.UploadJobCreated>()
            never<WorldEvent.ManifestPublished> { it.device != OTHER && it.added.isNotEmpty() }
            neverScreen<Layer.Joined> { it.health != SyncHealth.Loading }
            settled { true }
        }

        whenever {
            backend.failUnion(false)
            os.foreground()
        }
        then {
            event<WorldEvent.BackendReceived> { it.operation == UNION }
            settled { true }
        }

        // Only the own photo.
        whenever { uploadAll() }
        then {
            event<WorldEvent.BytesStored> { it.event == event && it.asset == "OWN" }
            never<WorldEvent.BytesStored> { it.asset != "OWN" }
            settled { true }
        }
    }

    @Test
    @Verifies(
        spec = "receiving-photos",
        requirement = "Received photos are never shared back",
        scenario = "A rejoin that cannot reach the event shares nothing yet",
    )
    fun a_background_wake_retries_the_read_a_rejoin_could_not_make() = behaviour {
        given {
            extensionUploadsOnly()
            val event = joinedEvent().also { received("FA") }
            reinstalled()
            step("the event's photos unreadable", act = { backend.failUnion(true) }, until = {})
            rejoinedUnrecognised(event)
            step("in the background, and the event readable again", act = {
                os.background()
                backend.failUnion(false)
            }, until = { settled { true } })
        }

        whenever { heartbeat() }
        then {
            event<WorldEvent.BackendReceived> { it.operation == UNION }
            event<WorldEvent.CompletionReleased>()
            settled { true }
        }

        // The received photo is recognised, not shared.
        whenever { cycle() }
        then {
            never<WorldEvent.UploadJobCreated>()
            settled { true }
        }
    }

    @Test
    @Verifies(
        spec = "receiving-photos",
        requirement = "Received photos are never shared back",
        scenario = "A rejoin that cannot reach the event shares nothing yet",
    )
    fun the_network_returning_retries_the_read_a_rejoin_could_not_make() = behaviour {
        given {
            extensionUploadsOnly()
            val event = joinedEvent().also { received("FA") }
            reinstalled()
            step("the event's photos unreadable", act = { backend.failUnion(true) }, until = {})
            rejoinedUnrecognised(event)
            step("offline", act = { network.set(NetworkState.OFFLINE) }, until = {})
            // A missing network is published only once it has lasted the watch's grace.
            step("told so", act = { advance(NetworkWatch.DEFAULT_GRACE) }, until = {
                screen<Layer.Joined> { it.health is SyncHealth.NoNetwork }
                settled { true }
            })
            step("the event readable again", act = { backend.failUnion(false) }, until = {})
        }

        whenever { network.set(NetworkState.ONLINE) }
        then {
            event<WorldEvent.BackendReceived> { it.operation == UNION }
            settled { true }
        }

        // The received photo is recognised, not shared.
        whenever { cycle() }
        then {
            never<WorldEvent.UploadJobCreated>()
            settled { true }
        }
    }

    @Test
    @Verifies(
        spec = "receiving-photos",
        requirement = "A deleted received photo never comes back",
        scenario = "A rejoin that cannot reach the event receives nothing twice",
    )
    fun a_rejoin_that_cannot_read_the_event_receives_nothing_again_once_it_can() = behaviour {
        given {
            val event = joinedEvent().also { received("FA", "FB") }
            reinstalled()
            step("the event's photos unreadable", act = { backend.failUnion(true) }, until = {})
            rejoinedUnrecognised(event)
        }

        // The event is read at last, and whatever that read started has finished: the photos still in the library are
        // recognised, not saved again.
        whenever {
            backend.failUnion(false)
            os.foreground()
        }
        then {
            event<WorldEvent.BackendReceived> { it.operation == UNION }
            settled { true }
        }
        downloadsAll {
            never<WorldEvent.LibraryAssetCreated>()
            settled { it.state.libraryPhotos == listOf(imported("FA", OTHER), imported("FB", OTHER)) }
        }
    }

    @Test
    @Verifies(
        spec = "receiving-photos",
        requirement = "Received photos are never shared back",
        scenario = "A received photo from before the event is recognised after a reinstall",
    )
    fun a_received_photo_from_before_the_event_is_not_shared_by_a_rejoin_whose_range_covers_it() = behaviour(
        startAt = DURING,
    ) {
        // FA was taken the day before the event (the fellow member's photos carry `Rig.PHOTO_DATE`); the rejoin's range
        // starts that morning, and the member's own photo of that evening shows the range really covers the day.
        given {
            extensionUploadsOnly()
            val event = joinedEvent(startsAt = START, endsAt = END).also { received("FA") }
            reinstalled()
            photoTaken("OWN", date = THE_EVENING_BEFORE)
            rejoined(event, Choices(cutoff = THE_MORNING_BEFORE))
        }

        whenever { uploadAll() }
        then {
            event<WorldEvent.BytesStored> { it.asset == "OWN" }
            never<WorldEvent.BytesStored> { it.asset != "OWN" }
            never<WorldEvent.ManifestPublished> { it.device != OTHER && it.added.any { asset -> asset != "OWN" } }
            settled { true }
        }
    }

    @Test
    @Verifies(
        spec = "receiving-photos",
        requirement = "Received photos are never shared back",
        scenario = "A received photo from before the event added to a limited selection is not shared back",
    )
    fun a_received_photo_from_before_the_event_added_to_a_limited_selection_is_not_shared() = behaviour(
        startAt = DURING,
    ) {
        given {
            val event = joinedEvent(startsAt = START, endsAt = END).also { received("FA") }
            reinstalled()
            step("an own photo taken", act = { takePhoto("OWN") }, until = { settled { true } })
            gateOpenOn(event)
            step("rejoined", act = { confirmJoin(Choices(cutoff = THE_MORNING_BEFORE)) }, until = {
                screen<Layer.Joined>()
                settled { true }
            })
            step("a partial grant, both uploaders on", act = {
                library.permission(PhotoGrant.LIMITED)
                devControls.pin(app = true, extension = true)
            }, until = { settled { true } })
            step("OWN selected, and the app in front", act = {
                library.changeSelection(listOf("OWN"))
                os.foreground()
            }, until = {
                event<WorldEvent.SessionTransferCreated> { it.key == primaryKey("OWN") }
                settled { true }
            })
        }

        // The member adds the received photo from before the event, still carrying its mark, and opens the app.
        whenever {
            library.changeSelection(listOf("OWN", imported("FA", OTHER)))
            os.foreground()
        }
        then {
            never<WorldEvent.SessionTransferCreated>()
            never<WorldEvent.UploadJobCreated>()
            never<WorldEvent.ManifestPublished> { it.device != OTHER && it.added.any { asset -> asset != "OWN" } }
            settled { it.state.inFlight?.sessionTransfers == listOf(primaryKey("OWN")) }
        }
    }

    @Test
    @Verifies(
        spec = "receiving-photos",
        requirement = "Received photos are never shared back",
        scenario = "Received photos added to a limited selection are not shared back",
    )
    fun received_photos_added_to_a_limited_selection_later_are_recognised_not_shared() = behaviour {
        given {
            val event = joinedEvent().also { received("FA", "FB") }
            // Reinstalled, and rejoined under a partial grant that selects only the member's own photo.
            reinstalled()
            step("an own photo taken", act = { takePhoto("OWN") }, until = { settled { true } })
            gateOpenOn(event)
            step("rejoined", act = { confirmJoin() }, until = {
                screen<Layer.Joined>()
                settled { true }
            })
            step("a partial grant, both uploaders on", act = {
                library.permission(PhotoGrant.LIMITED)
                devControls.pin(app = true, extension = true)
            }, until = { settled { true } })
            step("OWN selected, and the app in front", act = {
                library.changeSelection(listOf("OWN"))
                os.foreground()
            }, until = {
                event<WorldEvent.SessionTransferCreated> { it.key == primaryKey("OWN") }
                event<WorldEvent.ManifestPublished> { "OWN" in it.added }
                settled { true }
            })
        }

        // Later the member adds the received photos — still carrying their mark — to the selection, and the app reads it.
        whenever { library.changeSelection(listOf("OWN", imported("FA", OTHER), imported("FB", OTHER))) }
        then {
            never<WorldEvent.SessionTransferCreated>()
            never<WorldEvent.ManifestPublished> { it.added.isNotEmpty() }
            settled { true }
        }

        // Only the own photo is shared: no received photo became a transfer, and none is announced to the others.
        whenever { os.foreground() }
        then {
            never<WorldEvent.SessionTransferCreated>()
            never<WorldEvent.UploadJobCreated>()
            never<WorldEvent.ManifestPublished> { it.added.isNotEmpty() }
            settled { it.state.inFlight?.sessionTransfers == listOf(primaryKey("OWN")) }
        }
    }

    /** The app's files deleted, what outlives it kept, and the app launched again, unjoined. */
    private suspend fun Given.reinstalled() =
        step("reinstalled", act = { host.reinstall() }, until = { settled { true } })

    /** [OTHER] shares [assets], and each arrives in the library. */
    private suspend fun Given.received(vararg assets: String) = receivedFrom(OTHER, *assets)

    /** The join gate open on [event], through its invite, ready to join. */
    private suspend fun Given.gateOpenOn(event: String) {
        val link = inviteFor(event)
        step("its join gate open", act = { openLink(link) }, until = {
            screen<Layer.JoiningEvent> { it.eventId == event && it.phase is JoinPhase.Detailed }
        })
    }

    /**
     * Open the event's link and confirm its gate with [choices], as a member rejoining after a reinstall does: the
     * confirm raises the photo-access dialog (the reinstall reset the grant), the join goes ahead without waiting for
     * it, and the member answers it only afterwards — the order measured on the SE2.
     */
    private suspend fun Given.rejoined(event: String, choices: Choices = Choices()) {
        gateOpenOn(event)
        step("rejoined", act = { confirmJoin(choices) }, until = {
            screen<Layer.Joined>()
            settled { true }
        })
        // The downloads the rejoin planned finish while the dialog is still open, as they did on the phone.
        step("the rejoin's downloads finished", act = { downloadSession.finish() }, until = { settled { true } })
        // Closing the dialog makes the app active: iOS delivers the foreground right after the grant, and on the SE2
        // its import ran while the grant's adoption was still reading names (2026-10-01). Every import drain now waits
        // for the membership's adoption (`DownloadControllerTest` pins that order). The grant's adoption is the app's
        // answer to it, and it runs before the uploads are armed: the settle is that pass done, so a cycle forced next
        // (as a test may) cannot overtake it, as none can on a phone.
        step("photo access granted, and the app in front", act = {
            library.permission(PhotoGrant.GRANTED)
            os.foreground()
        }, until = { settled { true } })
    }

    /**
     * The rejoin of [rejoined], when its recognition cannot read the event: nothing announced, and the status on its
     * neutral line. The foreground's tail is settled: its import asks for recognition too, and one still running when a
     * test lets the event be read again would recognise the photos itself — leaving the pass the test triggers next
     * nothing to do.
     */
    private suspend fun Given.rejoinedUnrecognised(event: String) {
        gateOpenOn(event)
        step("rejoined", act = { confirmJoin() }, until = {
            screen<Layer.Joined>()
            settled { true }
        })
        step("photo access granted, and the app in front", act = {
            library.permission(PhotoGrant.GRANTED)
            os.foreground()
        }, until = {
            event<WorldEvent.BackendReceived> { it.operation == UNION }
            screen<Layer.Joined> { it.health == SyncHealth.Loading }
            never<WorldEvent.ManifestPublished> { it.device != OTHER && it.added.isNotEmpty() }
            settled { true }
        })
    }

    /** The foreground's reconcile, and then every download it started finished: [outcome] is what that led to. */
    private suspend fun Behaviour.downloadsAll(outcome: Expectations.() -> Unit) {
        whenever { os.foreground() }
        then { settled { true } }
        whenever { downloadSession.finish() }
        then(outcome)
    }

    private companion object {
        const val OTHER = DEV_F

        /** The event's union read (its OpenAPI `operationId`). */
        const val UNION = "eventFiles"

        /** Long enough for the joined screen's status poll (every 2 s) to read again. */
        val STATUS_POLL = 5.seconds

        /** An event that starts the day after `Rig.PHOTO_DATE`, the capture date of every fellow member's photo. */
        const val START = "2026-06-02T00:00:00"
        const val END = "2026-06-07T00:00:00"
        const val DURING = "2026-06-03T12:00:00Z"

        /** A range starting the day before the event, ahead of `Rig.PHOTO_DATE` by hours, as the rig reads a cutoff. */
        const val THE_MORNING_BEFORE = "2026-06-01T02:00:00Z"
        const val THE_EVENING_BEFORE = "2026-06-01T19:30:00Z"
    }
}
