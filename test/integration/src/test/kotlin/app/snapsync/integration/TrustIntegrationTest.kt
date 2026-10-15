package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Acts
import app.snapsync.control.behaviour.Seen
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.AttestationRefusal
import app.snapsync.levers.PhotoGrant
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.step
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The trust promises a member relies on without seeing them, over the REAL composed core, driven through the control
 * protocol: who may change an event, that a credential problem loses no photo, that an outdated app neither shares
 * nor receives until it is updated, and that a reinstalled phone stays the same device.
 */
class TrustIntegrationTest {

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "Only a genuine SnapSync app can change an event",
        scenario = "An Android phone with an unlocked bootloader",
    )
    fun a_phone_the_service_refuses_cannot_join_and_the_event_is_unchanged() = behaviour {
        val event = given {
            registeredEvent().also { event ->
                step("a fellow member shares", act = {
                    publicBackend.foreignDevice(
                        OTHER,
                        listOf("FA"),
                        event = event,
                    )
                }, until = {
                    event<WorldEvent.UnionChanged> { it.asset == "FA" }
                    settled { true }
                })
                step("the service refuses this phone as modified", act = {
                    backend.refuseAttestation(AttestationRefusal.DEVICE_MODIFIED)
                }, until = {})
                step("its link opened", act = { openLink(inviteLink(event)) }, until = {
                    screen<Layer.JoiningEvent> { it.phase is JoinPhase.Detailed }
                })
            }
        }

        whenever { confirmJoin() }
        then {
            screen<Layer.JoiningEvent> { it.phase.step == JoinPhase.Detailed.Step.DeviceRefused }
            // The event the refused phone tried to join is unchanged…
            never<WorldEvent.MemberJoined>()
            never<WorldEvent.ManifestPublished>()
            never<WorldEvent.UnionChanged>()
            never<WorldEvent.EventRenamed> { it.event == event }
            // …and the phone is in no event.
            neverScreen<Layer.Joined>()
        }
    }

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "A verification problem never loses a photo",
        scenario = "The proof expires while the phone sits unused",
    )
    fun a_photo_whose_upload_met_an_expired_proof_is_shared_once_the_app_is_verified_again() = behaviour {
        given {
            extensionUploadsOnly()
            joinedEvent()
            photos("P1")
            // The system takes the photo's upload in hand with the proof the app holds…
            step("its upload taken in hand", act = { cycle() }, until = {
                event<WorldEvent.UploadJobCreated> { it.job == primaryJob("P1") }
                settled { true }
            })
        }

        // …and performs it only after the member has left the app unopened for longer than the proof lasts (30 days).
        // Refused for its expired proof: the job is still held, never dropped (a landed one is acknowledged and gone).
        whenever {
            advanceTo("2026-07-15T12:00:00Z")
            uploadQueue.completeJob()
            cycle()
        }
        then {
            never<WorldEvent.BytesStored>()
            never<WorldEvent.UploadJobEnded>()
            settled { primaryJob("P1") in it.state.inFlight?.jobs.orEmpty() } // the refused upload is kept, not dropped
        }

        // The member opens the app: it is verified again, and the photo it kept is shared.
        whenever {
            os.foreground()
            uploadAll()
            landsOnRetry()
        }
        then { event<WorldEvent.BytesStored> { it.asset == "P1" } }
    }

    @Test
    @Verifies(
        spec = "app-update-required",
        requirement = "Nothing is shared or received while the app is outdated",
        scenario = "Photos wait for the update",
    )
    fun photos_taken_while_outdated_are_shared_after_the_update() = behaviour {
        given {
            extensionUploadsOnly()
            joinedEvent()
            step("the app outdated", act = {
                backend.minAppVersion("0.4")
                build.declare("0.3")
                os.foreground()
            }, until = {
                screen<Layer.UpdateRequired>()
                settled { true }
            })
            photos("P1")
            // A fellow member shares meanwhile (through the backend's public surface, as a build the backend serves).
            step("a fellow member shares", act = {
                build.declare("0.4")
                publicBackend.foreignDevice(OTHER, listOf("FA"))
                build.declare("0.3")
            }, until = {
                event<WorldEvent.UnionChanged> { it.asset == "FA" }
                settled { true }
            })
        }

        // An outdated app receives nothing, and shares nothing.
        whenever {
            uploadAll()
            os.foreground()
        }
        then {
            never<WorldEvent.BytesStored>()
            never<WorldEvent.DownloadStarted>()
            never<WorldEvent.LibraryAssetCreated>()
        }

        // Updated: the photo it kept is shared, and the fellow member's photo arrives once it is served again.
        whenever {
            build.declare("0.4")
            os.foreground()
        }
        then {
            screen<Layer.Joined>()
            event<WorldEvent.DownloadStarted>()
            settled { true }
        }
        whenever {
            uploadAll()
            landsOnRetry()
        }
        then { event<WorldEvent.BytesStored> { it.asset == "P1" } }
        whenever { downloadSession.finish() }
        then {
            event<WorldEvent.LibraryAssetCreated> { it.asset == "imported-$OTHER-FA" }
            settled { it.state.libraryPhotos?.size == 2 }
        }
    }

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "No account and no personal identity",
        scenario = "Reinstalling on the same phone",
    )
    fun a_reinstalled_phone_is_known_by_the_same_device_identifier() = behaviour {
        lateinit var first: Seen<WorldEvent.BytesStored>
        val event = given {
            extensionUploadsOnly()
            joinedEvent().also {
                photos("P1")
                // The first install's photo is filed under this phone's identifier.
                step("P1 uploaded", act = { uploadAll() }, until = {
                    first = event<WorldEvent.BytesStored> { it.asset == "P1" }
                    settled { true }
                })
            }
        }

        whenever { host.reinstall() }
        then { settled { true } }
        whenever { openLink(inviteLink(event)) }
        then { screen<Layer.JoiningEvent> { it.eventId == event && it.phase.step == JoinPhase.Detailed.Step.Ready } }
        whenever { confirmJoin() }
        then {
            screen<Layer.Joined>()
            settled { true }
        }
        whenever {
            library.permission(PhotoGrant.GRANTED)
            os.foreground()
            takePhoto("P2")
        }
        then { settled { true } }

        // The second install's photo lands beside the first install's, so the phone is the same device to its event.
        lateinit var second: Seen<WorldEvent.BytesStored>
        whenever { uploadAll() }
        then { second = event<WorldEvent.BytesStored> { it.asset == "P2" } }
        assertEquals(
            first.value.device,
            second.value.device,
            "the reinstalled phone presents the identifier it had before",
        )
    }

    /**
     * The job the backend refused on its last try ended failed, and the cycle that heard so made it again: the OS
     * performs that one, and the next cycle records it.
     */
    private suspend fun Acts.landsOnRetry() {
        uploadQueue.completeJob()
        cycle()
    }

    private companion object {
        const val OTHER = DEV_F
    }
}
