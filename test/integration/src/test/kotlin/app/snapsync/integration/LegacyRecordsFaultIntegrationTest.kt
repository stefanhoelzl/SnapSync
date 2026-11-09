package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Acts
import app.snapsync.control.behaviour.Given
import app.snapsync.control.behaviour.Seen
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.FileOp
import app.snapsync.levers.FilePlace
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.Layer
import kotlin.test.Test

/**
 * **An update from the oldest install, over a disk that fails its older records** (`files.fail` on the files 0.4 kept
 * its membership, manifest record and pending leaves in): records the update cannot read yet are copied by a later
 * opening, and the event is kept throughout; records it copied but could not delete are deleted by a later opening.
 */
@Verifies(
    spec = "manage-membership",
    requirement = "A membership survives updates, restarts, and a locked phone",
    scenario = "An app update keeps the event",
)
class LegacyRecordsFaultIntegrationTest {

    @Test
    fun an_update_whose_older_records_cannot_be_read_yet_keeps_the_event_and_copies_them_later() = behaviour {
        val event = given { fromTheOldestInstall() }

        whenever {
            files.fail(FilePlace.LEGACY_RECORDS, FileOp.READ)
            updated()
        }
        then {
            event<WorldEvent.FilesFaulted> { it.place == FilePlace.LEGACY_RECORDS && it.op == FileOp.READ }
            neverScreen<Layer.CreateEvent>()
            settled { true }
        }

        whenever { updated() }
        then { screen<Layer.Joined> { it.membership.eventId == event } }
    }

    @Test
    fun older_records_an_update_copied_but_could_not_delete_are_deleted_at_the_next_opening() = behaviour {
        val event = given { fromTheOldestInstall() }

        lateinit var copied: Seen<WorldEvent.Settled>
        whenever {
            files.fail(FilePlace.LEGACY_RECORDS, FileOp.DELETE)
            updated()
        }
        then {
            event<WorldEvent.FilesFaulted> { it.place == FilePlace.LEGACY_RECORDS && it.op == FileOp.DELETE }
            screen<Layer.Joined> { it.membership.eventId == event }
            copied = settled { true }
        }

        whenever { updated() }
        then {
            screen<Layer.Joined> { it.membership.eventId == event }
            settled { it.state.appStorageBytes!! < copied.value.state.appStorageBytes!! }
        }
    }

    /**
     * A device joined to a plain event, as the oldest build the migration chain still builds left it — its shared records
     * in their legacy files. Answers the event.
     */
    private suspend fun Given.fromTheOldestInstall(): String {
        creatingPlain()
        val event = joinedEvent()
        step("the device as 0.4 left it", act = {
            olderInstall.installedAt(ledgerBehind = olderInstall.installedAt().olderLedgers)
        }, until = {})
        return event
    }

    /** The relaunch an update is, and the member opening the app after it. */
    private suspend fun Acts.updated() {
        host.relaunch()
        os.foreground()
    }
}
