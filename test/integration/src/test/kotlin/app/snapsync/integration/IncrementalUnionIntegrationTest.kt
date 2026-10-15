package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.WorldEvent
import kotlin.test.Test

/**
 * **A push reads only what is new, and a withdrawn photo stops coming** (decision record `changes/incremental-union`,
 * D6–D7), counted where the outside can see it: the backend's union reads, and the photo library.
 */
class IncrementalUnionIntegrationTest {

    @Test
    fun a_burst_of_pushes_for_what_was_already_read_reads_the_union_once() = behaviour {
        val event = given {
            joinedEvent().also {
                pushToken("DEADBEEF") // a registered member is woken
                fellowMember(DEV_F, "FA") // the backend wakes this member, naming the new position
            }
        }

        whenever { os.silentPush(event) }
        then { times(1) { event<WorldEvent.BackendReceived> { it.operation == UNION_READ } } }

        // The pushes after the first announce nothing it has not read.
        whenever {
            os.silentPush(event)
            os.silentPush(event)
        }
        then { never<WorldEvent.BackendReceived> { it.operation == UNION_READ } }
    }

    @Test
    @Verifies(spec = "photo-sharing", requirement = "Deleting or no longer sharing a photo withdraws it")
    fun a_photo_withdrawn_before_it_arrived_never_arrives_and_one_received_stays() = behaviour {
        given {
            joinedEvent()
            fellowMember(DEV_F, "KEPT", "RECEIVED")
            step("both planned", act = { os.foreground() }, until = {
                times(2) { event<WorldEvent.DownloadStarted>() }
            })
            step("both arrived", act = { downloadSession.finish() }, until = {
                event<WorldEvent.LibraryAssetCreated> { it.asset == received("KEPT") }
                event<WorldEvent.LibraryAssetCreated> { it.asset == received("RECEIVED") }
                settled { true }
            })
            // RECEIVED withdrawn, LATE shared — and the reconcile plans LATE.
            fellowMember(DEV_F, "KEPT", "LATE")
            step("LATE planned", act = { os.foreground() }, until = {
                event<WorldEvent.DownloadStarted> { it.names("LATE") }
                settled { true }
            })
        }

        // LATE withdrawn before it arrived: the opening's full read drops it.
        whenever {
            publicBackend.foreignDevice(DEV_F, listOf("KEPT"))
            os.foreground()
        }
        then { settled { true } }

        whenever { downloadSession.finish() }
        then {
            // LATE never arrived, and RECEIVED stays in the library.
            never<WorldEvent.LibraryAssetCreated>()
            settled { it.state.libraryPhotos?.toSet() == setOf(received("KEPT"), received("RECEIVED")) }
        }
    }

    /** Whether this download is of the fellow member's photo [asset]. */
    private fun WorldEvent.DownloadStarted.names(asset: String) = description.split("\n").getOrNull(1) == asset

    private companion object {
        /** The union read's operation. */
        const val UNION_READ = "eventFiles"

        /** The library's id of the fellow member's photo [asset], once imported. */
        fun received(asset: String) = "imported-$DEV_F-$asset"
    }
}
