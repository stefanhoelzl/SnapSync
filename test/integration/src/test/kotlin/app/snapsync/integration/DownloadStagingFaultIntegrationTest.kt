package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Acts
import app.snapsync.control.behaviour.Expectations
import app.snapsync.control.behaviour.Given
import app.snapsync.control.behaviour.Seen
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.FileOp
import app.snapsync.levers.FilePlace
import app.snapsync.levers.WorldEvent
import kotlin.test.Test

/**
 * **A disk that fails a received photo on its way into the library** (`files.fail` on the download staging area): a
 * download whose bytes the phone could not take over, or whose sealed bytes it could not open, is fetched again and
 * arrives; one that keeps failing to open is reported once; and bytes a failed delete left behind are reclaimed by a
 * later opening — never while the staging directory cannot be read.
 */
class DownloadStagingFaultIntegrationTest {

    @Test
    @Verifies(
        spec = "delivery",
        requirement = "Download problems delay photos, never corrupt or lose them",
        scenario = "A broken download is retried",
    )
    fun a_received_photo_the_disk_failed_to_take_over_or_open_is_downloaded_again_and_arrives() = behaviour {
        given {
            joinedEvent()
            downloadStarted()
        }

        // The transfer's file not taken over; then, taken over, its sealed bytes not read; then its plaintext not
        // written — each one a download that failed, started again by the next opening.
        for (op in listOf(FileOp.MOVE, FileOp.READ, FileOp.WRITE)) {
            whenever { finishedFailing(op) }
            then { notArrived(op) }
            whenever { os.foreground() }
            then {
                event<WorldEvent.DownloadStarted>()
                settled { true }
            }
        }

        whenever { downloadSession.finish() }
        then { event<WorldEvent.LibraryAssetCreated> { it.asset == imported() } }
    }

    @Test
    fun a_received_photo_that_fails_to_open_again_and_again_is_reported_once() = behaviour {
        given {
            joinedEvent()
            downloadStarted()
            repeat(FAILURES_BEFORE_THE_REPORT) {
                step("its sealed bytes unreadable, and fetched again", act = {
                    finishedFailing(FileOp.READ)
                    os.foreground()
                }, until = {
                    event<WorldEvent.DownloadStarted>()
                    settled { true }
                })
            }
        }

        // The fourth failure in a row reaches the crash reporter…
        whenever { finishedFailing(FileOp.READ) }
        then {
            times(1) { event<WorldEvent.EventReceived>() }
            settled { true }
        }

        // …and only that one: the fifth goes unreported, and the photo still arrives once the disk reads.
        whenever {
            os.foreground()
            finishedFailing(FileOp.READ)
        }
        then {
            never<WorldEvent.EventReceived>()
            settled { true }
        }
        whenever {
            os.foreground()
            downloadSession.finish()
        }
        then { event<WorldEvent.LibraryAssetCreated> { it.asset == imported() } }
    }

    @Test
    @Verifies(spec = "receiving-photos", requirement = "Received photos do not take up space twice")
    fun a_received_photo_s_bytes_a_failed_delete_left_behind_are_reclaimed_at_the_next_opening() = behaviour {
        val before = given { leftBehind() }

        whenever { os.foreground() }
        then { settled { it.storage() <= before } }
    }

    @Test
    @Verifies(spec = "receiving-photos", requirement = "Received photos do not take up space twice")
    fun bytes_left_behind_stay_while_the_staging_directory_cannot_be_read_and_go_once_it_can() = behaviour {
        val before = given { leftBehind() }

        whenever {
            files.fail(FilePlace.DOWNLOAD_STAGING, FileOp.LIST)
            os.foreground()
        }
        then {
            event<WorldEvent.FilesFaulted> { it.place == FilePlace.DOWNLOAD_STAGING && it.op == FileOp.LIST }
            settled { it.storage() > before }
        }

        whenever { os.foreground() }
        then { settled { it.storage() <= before } }
    }

    /**
     * A plain event's photo received, while the delete that frees its staged bytes once imported fails: the photo is in
     * the library, its bytes still on the disk. Answers the storage the app took before they arrived.
     */
    private suspend fun Given.leftBehind(): Long {
        creatingPlain()
        joinedEvent()
        val before = downloadStarted().storage()
        step("it arrives, and its staged bytes' delete fails", act = {
            files.fail(FilePlace.DOWNLOAD_STAGING, FileOp.DELETE)
            downloadSession.finish()
        }, until = {
            event<WorldEvent.LibraryAssetCreated> { it.asset == imported() }
            event<WorldEvent.FilesFaulted> { it.op == FileOp.DELETE }
            settled { it.storage() > before }
        })
        return before
    }

    /** The next call of [op] on the download staging area fails, and the operating system finishes the download. */
    private suspend fun Acts.finishedFailing(op: FileOp) {
        files.fail(FilePlace.DOWNLOAD_STAGING, op)
        downloadSession.finish()
    }

    /** The fault was spent on the download, and the photo did not arrive. */
    private fun Expectations.notArrived(op: FileOp): Seen<WorldEvent.Settled> {
        event<WorldEvent.FilesFaulted> { it.place == FilePlace.DOWNLOAD_STAGING && it.op == op }
        never<WorldEvent.LibraryAssetCreated>()
        return settled { true }
    }

    /** The storage the app takes, as the device's storage page shows it. */
    private fun WorldEvent.Settled.storage(): Long = checkNotNull(state.appStorageBytes)

    private companion object {
        /** The failures in a row an opening tells nobody of; the next one is reported (`OpenFailures.REPORT_AFTER`). */
        const val FAILURES_BEFORE_THE_REPORT = 3
    }
}
