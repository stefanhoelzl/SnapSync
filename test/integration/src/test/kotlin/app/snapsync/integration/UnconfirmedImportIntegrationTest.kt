package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Acts
import app.snapsync.control.behaviour.Given
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.PhotoGrant
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.DirectionCount
import app.snapsync.ui.state.Layer
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * **What the next process makes of an import a dead process never confirmed** (decision records
 * `changes/archive/2026-08-09-gate-absence-on-unreported-imports`, `2026-08-29-settle-imports-on-consumed-bytes`), read
 * off the photo library: a photo the whole library shows was never created is imported again — once; one a partial
 * grant cannot see is left alone, never imported a second time. `InterruptedImportIntegrationTest` holds the import
 * the library did create.
 */
@Verifies(spec = "receiving-photos", requirement = "Nothing arrives twice")
class UnconfirmedImportIntegrationTest {

    @Test
    @Verifies(spec = "receiving-photos", requirement = "Nothing arrives twice", scenario = "Killed while saving")
    fun an_import_killed_before_the_library_created_the_photo_is_imported_once_by_the_next_process() = behaviour {
        given {
            joinedEvent()
            downloadStarted()
            killedWhileSaving(createdFirst = false)
        }

        whenever { relaunched() }
        then {
            times(1) { event<WorldEvent.LibraryAssetCreated> { it.asset.startsWith(imported()) } }
            screen<Layer.Joined> { it.counts?.received == SETTLED }
        }
    }

    @Test
    fun an_unconfirmed_import_a_partial_grant_cannot_see_is_never_imported_again() = behaviour {
        given {
            joinedEvent()
            downloadStarted()
            killedWhileSaving(createdFirst = true)
            photoAccess(PhotoGrant.LIMITED)
        }

        // The next process's selection holds nothing: the photo the dead process created is out of sight, not gone.
        whenever {
            host.relaunch()
            os.foreground()
            library.changeSelection(emptyList())
            os.foreground()
        }
        then {
            never<WorldEvent.LibraryAssetCreated>()
            neverScreen<Layer.Joined> { it.counts?.received == SETTLED }
            settled { true }
        }
    }

    /**
     * The transfer's bytes staged and their import parked — before the library creates the photo, or once it has
     * ([createdFirst]) — and never confirmed: what a process killed while saving leaves.
     */
    private suspend fun Given.killedWhileSaving(createdFirst: Boolean) = step(
        "its bytes staged, the import parked ${if (createdFirst) "after" else "before"} the library creates the photo",
        act = {
            library.suspendNextImport(afterCommit = createdFirst)
            downloadSession.finish()
            advance(SCREEN_REREAD)
        },
        until = {
            event<WorldEvent.LibraryImportParked>()
            screen<Layer.Joined> { it.counts?.received == SHORT }
            settled { true }
        },
    )

    /** Process death and a cold launch, then the operating system brings the new app to the front — which assembles it. */
    private suspend fun Acts.relaunched() {
        host.relaunch()
        os.foreground()
        advance(SCREEN_REREAD)
    }

    private companion object {
        /** The one photo planned, not yet imported. */
        val SHORT = DirectionCount.Progress(0, 1)

        /** The one photo planned, imported. */
        val SETTLED = DirectionCount.Progress(1, 1)

        /** Long enough for the screen to re-read what changed, on mocked time. */
        val SCREEN_REREAD = 5.seconds
    }
}
