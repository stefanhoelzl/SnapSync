package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Given
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.PhotoGrant
import app.snapsync.levers.UnionEntry
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.Layer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **The two uploaders of an iPhone** (capability `delivery`): the app's own, on every iOS, and iOS's own background
 * uploader on 26.1 and later, which the app registers from join to leave. The JVM host plays both OSes —
 * `extension-registry.carried` is whether the phone carries iOS's uploader ([iosUploaderCarried]) — and invokes iOS's
 * uploader as the OS does ([cycle]); a photo "shared" is one that left the device: a transfer of the app's session, or a
 * job of iOS's queue.
 *
 * The force-quit is Apple's documented behaviour (decision records `2026-07-04-add-url-session-upload`,
 * `2026-07-05-push-notification-infra`), played by `played-os.forceQuit`. That iOS still invokes its uploader under
 * limited access, from a registration a full grant made, was measured on the SE2 (iOS 26.6, 2026-09-21: `process()`
 * ran 4 s after a photo joined the selection) — ⏰ re-measure at the next iOS major; one device, one probe.
 */
class OsUploaderIntegrationTest {

    @Test
    @Verifies(
        spec = "delivery",
        requirement = "Photos travel without the app being opened",
        scenario = "Uploads work below iOS 26.1",
    )
    fun below_26_1_the_app_uploads_on_its_own_wakes() = behaviour {
        given {
            createdEvent()
            // The OS carries no uploader of its own, so the join registers none.
            step("joined", act = { confirmJoin() }, until = {
                screen<Layer.Joined>()
                settled { true }
                never<WorldEvent.ExtensionRegistered>()
            })
            inBackground()
        }
        whenever {
            takePhoto("P1")
            heartbeat()
        }
        then {
            event<WorldEvent.SessionTransferCreated> { it.key == primaryKey("P1") }
            never<WorldEvent.UploadJobCreated>()
        }
    }

    @Test
    @Verifies(
        spec = "delivery",
        requirement = "A new event takes effect for background uploads at once",
        scenario = "iOS's uploader follows a switch",
    )
    fun ios_uploader_follows_a_switch() = behaviour {
        val (old, new) = given {
            iosUploaderCarried()
            val new = registeredEvent(name = "Anna's Wedding")
            val old = joinedEvent()
            step(
                "iOS's uploader shares a photo to it",
                act = {
                    takePhoto("A")
                    cycle()
                    uploadQueue.completeJob()
                },
                until = { settled { true } },
            )
            step(
                "switched to the other event",
                act = { openLink(inviteLink(new, autoJoin = true)) },
                until = {
                    screen<Layer.Joined> { it.membership.eventId == new }
                    settled { true }
                },
            )
            old to new
        }

        whenever {
            takePhoto("B")
            cycle()
        }
        then { event<WorldEvent.UploadJobCreated> { it.job == primaryJob("B") } }

        var inOld = emptyList<String>()
        var inNew = emptyList<String>()
        whenever { uploadQueue.completeJob() }
        then { settled { true } }
        whenever {
            inOld = publicBackend.objects(event = old)
            inNew = publicBackend.objects(event = new)
        }
        then { settled { true } }
        assertTrue(primaryKey("B") in inNew, "the next background upload went to the new event: $inNew")
        assertFalse(primaryKey("B") in inOld, "and none to the old one: $inOld")
    }

    @Test
    @Verifies(
        spec = "delivery",
        requirement = "A force-quit pauses the app's own background uploading only until the next opening",
        scenario = "Reopening after a force-quit resumes uploading",
    )
    fun reopening_after_a_force_quit_resumes_uploading() = behaviour {
        given {
            joinedEvent()
            photos("P0")
            step(
                "P0 on its way",
                act = { os.foreground() },
                until = {
                    event<WorldEvent.SessionTransferCreated> { it.key == primaryKey("P0") }
                    settled { true }
                },
            )
            inBackground()
        }

        // P0's transfer is cancelled with the process; P1 is taken while the app is launched for nothing.
        whenever {
            playedOs.forceQuit()
            takePhoto("P1")
        }
        then {
            event<WorldEvent.ForceQuit>()
            never<WorldEvent.SessionTransferCreated>()
        }

        whenever { os.foreground() }
        then {
            event<WorldEvent.SessionTransferCreated> { it.key == primaryKey("P0") }
            event<WorldEvent.SessionTransferCreated> { it.key == primaryKey("P1") }
        }

        // ...and background uploading continues after the app is left again.
        whenever {
            os.background()
            takePhoto("P2")
            heartbeat()
        }
        then { event<WorldEvent.SessionTransferCreated> { it.key == primaryKey("P2") } }
    }

    @Test
    @Verifies(
        spec = "delivery",
        requirement = "A force-quit pauses the app's own background uploading only until the next opening",
        scenario = "iOS's uploader keeps sharing after a force-quit",
    )
    fun ios_uploader_keeps_sharing_after_a_force_quit() = behaviour {
        given {
            iosUploaderCarried()
            joinedEvent()
            inBackground()
            step("force-quit", act = { playedOs.forceQuit() }, until = { settled { true } })
        }
        whenever {
            takePhoto("P1")
            cycle()
        }
        then {
            event<WorldEvent.UploadJobCreated> { it.job == primaryJob("P1") }
            never<WorldEvent.SessionTransferCreated>()
        }
    }

    @Test
    @Verifies(
        spec = "delivery",
        requirement = "A photo is never lost on the way",
        scenario = "A photo both uploaders pick up arrives once",
    )
    fun a_photo_both_uploaders_pick_up_arrives_once() = behaviour {
        given {
            iosUploaderCarried()
            joinedEvent()
            inBackground()
        }
        // The app's own wake and iOS's uploader each look at the library after the photo is taken.
        whenever {
            takePhoto("P1")
            heartbeat()
            cycle()
        }
        then {
            event<WorldEvent.Transfer> {
                (it is WorldEvent.SessionTransferCreated && it.key == primaryKey("P1")) ||
                    (it is WorldEvent.UploadJobCreated && it.job == primaryJob("P1"))
            }
            settled { true }
        }

        var union = emptyList<UnionEntry>()
        whenever {
            uploadQueue.completeJob()
            uploadSession.complete()
        }
        then { settled { true } }
        whenever { union = publicBackend.union() }
        then { settled { true } }
        assertEquals(1, union.count { it.asset == "P1" }, "the other members receive it once: $union")
    }

    @Test
    @Verifies(
        spec = "photo-access",
        requirement = "Limited access is a working membership whose scope is the selection",
        scenario = "Narrowing access after joining keeps unselected photos private",
    )
    fun narrowing_access_after_joining_keeps_unselected_photos_private() = behaviour {
        given {
            iosUploaderCarried()
            createdEvent()
            step("joined with full access", act = { confirmJoin() }, until = {
                event<WorldEvent.ExtensionRegistered> { it.on }
                settled { true }
            })
            // iOS refuses to deregister under a partial grant, so the registration a full grant made survives.
            step("narrowed to an empty selection", act = { library.permission(PhotoGrant.LIMITED) }, until = {
                settled { true }
                never<WorldEvent.ExtensionRegistered> { !it.on }
            })
            inBackground()
        }
        // iOS keeps invoking its uploader, and the app wakes: neither may take a photo outside the selection.
        whenever {
            takePhoto("B")
            heartbeat()
            cycle()
        }
        then {
            never<WorldEvent.SessionTransferCreated> { it.key == primaryKey("B") }
            never<WorldEvent.UploadJobCreated> { it.job == primaryJob("B") }
        }
    }

    /**
     * The phone runs iOS 26.1 or later, which carries iOS's own background uploader: a fact the app reads at launch, so the
     * process is started again over it, in front, as a member opening the app finds it.
     */
    private suspend fun Given.iosUploaderCarried() = step(
        "iOS's own uploader carried",
        act = {
            extensionRegistry.carried(true)
            host.relaunch(scene = true)
        },
        until = { settled { true } },
    )

    /** The app in front once, then left for the background, and whatever both did done. */
    private suspend fun Given.inBackground() {
        inFront()
        step("in the background", act = { os.background() }, until = { settled { true } })
    }
}
