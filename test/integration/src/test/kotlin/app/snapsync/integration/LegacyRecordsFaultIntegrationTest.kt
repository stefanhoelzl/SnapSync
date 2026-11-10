package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Acts
import app.snapsync.control.behaviour.Behaviour
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

    // Whatever the membership file 0.4 kept holds — corruption, a damaged backup — an update never takes it for a leave:
    // the event is kept, and the file is left for a later opening. Each test plays one kind of damage over the update.

    @Test
    fun an_older_membership_file_that_is_no_envelope_of_this_version_never_drops_the_event() = behaviour {
        val event = given { fromTheOldestInstall() }
        keptThrough(
            listOf(
                byteArrayOf(0xC3.toByte(), 0x28),
                "".encodeToByteArray(),
                "not json at all".encodeToByteArray(),
                "[1]".encodeToByteArray(),
                """{"payload":{}}""".encodeToByteArray(),
                """{"v":"1","payload":{}}""".encodeToByteArray(),
                """{"v":{},"payload":{}}""".encodeToByteArray(),
                """{"v":1.5,"payload":{}}""".encodeToByteArray(),
                """{"v":2,"payload":{"something":"newer"}}""".encodeToByteArray(),
                """{"v":1}""".encodeToByteArray(),
                """{"v":1,"payload":[]}""".encodeToByteArray(),
                "{${required(event)}}".encodeToByteArray(),
            ),
        )
    }

    @Test
    fun an_older_membership_file_missing_a_required_field_never_drops_the_event() = behaviour {
        val event = given { fromTheOldestInstall() }
        val fields = listOf("eventId", "name", "minPhotoDate", "maxPhotoDate", "endsAt", "deletesAt")
        keptThrough(
            fields.map { left ->
                envelope(required(event).split(",").filterNot { it.startsWith("\"$left\":") }.joinToString(","))
            },
        )
    }

    @Test
    fun an_older_membership_file_holding_a_field_of_the_wrong_kind_never_drops_the_event() = behaviour {
        val event = given { fromTheOldestInstall() }
        val wrong = listOf(
            """"startsAt":"soon"""",
            """"startsAt":7""",
            """"direction":"Sideways"""",
            """"direction":7""",
            """"saveToAlbum":"true"""",
            """"saveToAlbum":{}""",
            """"saveToAlbum":null""",
            """"deleted":"no"""",
            """"openUntil":"soon"""",
            """"keyId":7""",
            """"keyId":{}""",
        )
        val numbered = listOf("name", "endsAt", "maxPhotoDate", "deletesAt").map { field ->
            envelope(
                required(
                    event,
                ).split(",").joinToString(",") { if (it.startsWith("\"$field\":")) "\"$field\":7" else it },
            )
        }
        keptThrough(
            wrong.map { envelope("${required(event)},$it") } + numbered +
                envelope(required(event).replace(event, "nope")),
        )
    }

    @Test
    fun an_older_membership_file_with_broken_member_counts_never_drops_the_event() = behaviour {
        val event = given { fromTheOldestInstall() }
        keptThrough(
            listOf(
                """"members":[]""",
                """"members":{"active":3}""",
                """"members":{"settled":1}""",
                """"members":{"active":"3","settled":1}""",
                """"members":{"active":3,"settled":"1"}""",
                """"members":{"active":{},"settled":1}""",
                """"members":{"active":3,"settled":1,"waiting":"2"}""",
            ).map { envelope("${required(event)},$it") },
        )
    }

    @Test
    fun an_older_membership_file_holding_every_field_it_may_is_copied_by_the_update() = behaviour {
        val event = given { fromTheOldestInstall() }

        whenever {
            olderInstall.membershipFile(
                envelope(
                    "${required(event)}," +
                        """"startsAt":"2026-05-15T00:00:00Z","direction":"Both","saveToAlbum":false,"deleted":false,""" +
                        """"members":{"active":1,"settled":0,"waiting":null},"openUntil":"2026-06-14T00:00:00Z",""" +
                        """"keyId":null""",
                ),
            )
            updated()
        }
        then { screen<Layer.Joined> { it.membership.eventId == event } }
    }

    /** Each of [files] in turn the membership file over an update, and the event kept through every one. */
    private suspend fun Behaviour.keptThrough(files: List<ByteArray>) {
        for (bytes in files) {
            whenever {
                olderInstall.membershipFile(bytes)
                updated()
            }
            then {
                neverScreen<Layer.CreateEvent>()
                settled { true }
            }
        }
    }

    /** The fields of [event]'s membership 0.4 always wrote, as its file spells them. */
    private fun required(event: String) =
        """"eventId":"$event","name":"${Defaults.EVENT_NAME}","minPhotoDate":"2026-05-15T00:00:00Z",""" +
            """"maxPhotoDate":"2026-06-14T00:00:00Z","endsAt":"2026-06-14T00:00:00Z",""" +
            """"deletesAt":"2026-07-14T00:00:00Z""""

    /** The file 0.4 wrote around [payload]: its envelope, this build's version. */
    private fun envelope(payload: String) = """{"v":1,"payload":{$payload}}""".encodeToByteArray()

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
