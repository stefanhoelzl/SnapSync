package app.snapsync.journeys.device

import app.snapsync.journeys.device.PhotoMigrations.State
import kotlin.test.Test
import kotlin.test.assertEquals

/** `assetsd`'s own log lines, verbatim from run 37004211450 (`log … --style compact`), trimmed only where they ran on. */
class PhotoMigrationsTest {

    @Test
    fun the_measured_run_waits_until_the_system_library_completes() {
        val before = arrayOf(STAT, SUBMIT_SYSTEM, SUBMIT_SYNDICATION, RESUBMIT_SYSTEM, EXPIRE_SYNDICATION)
        assertEquals(State.WAITING, after(*before))
        assertEquals(State.SETTLED, after(*before, COMPLETE_SYSTEM))
    }

    @Test
    fun a_cancelled_and_resubmitted_task_is_not_a_completion() {
        assertEquals(State.WAITING, after(STAT, SUBMIT_SYSTEM, RESUBMIT_SYSTEM))
    }

    @Test
    fun the_syndication_library_is_never_awaited() {
        assertEquals(State.SETTLED, after(STAT, SUBMIT_SYSTEM, SUBMIT_SYNDICATION, COMPLETE_SYSTEM))
    }

    @Test
    fun a_completion_seen_before_its_submission_still_counts() {
        // `log show` and `log stream` overlap; the order they are read in is not the order they happened.
        assertEquals(State.SETTLED, after(COMPLETE_SYSTEM, STAT, SUBMIT_SYSTEM))
    }

    @Test
    fun nothing_submitted_is_nothing_pending() {
        assertEquals(State.SETTLED, after())
        assertEquals(State.SETTLED, after(STAT))
    }

    @Test
    fun a_system_library_that_submitted_nothing_is_settled() {
        assertEquals(State.SETTLED, after(STAT, SUBMIT_SYNDICATION))
    }

    @Test
    fun submissions_with_no_upgraded_library_are_each_awaited() {
        // A settled device restored from CI's cache: its system library is current, so nothing is upgraded on this boot.
        assertEquals(State.WAITING, after(SUBMIT_SYSTEM, SUBMIT_SYNDICATION))
        assertEquals(State.WAITING, after(SUBMIT_SYSTEM, SUBMIT_SYNDICATION, COMPLETE_SYSTEM))
        val completeSyndication = COMPLETE_SYSTEM.replace(SYSTEM, SYNDICATION)
        assertEquals(State.SETTLED, after(SUBMIT_SYSTEM, SUBMIT_SYNDICATION, COMPLETE_SYSTEM, completeSyndication))
    }

    @Test
    fun two_upgraded_libraries_fail_rather_than_guess() {
        assertEquals(State.UNKNOWN, after(STAT, STAT.replace("st_ino=3098355", "st_ino=4000000"), SUBMIT_SYSTEM))
    }

    @Test
    fun a_repeated_stat_line_is_one_library() {
        // The upgrade logs its stat line once per schema stage: five times in the measured run.
        assertEquals(State.WAITING, after(STAT, STAT, STAT, SUBMIT_SYSTEM))
    }

    private fun after(vararg lines: String): State = PhotoMigrations().apply { lines.forEach(::feed) }.verdict().state

    private companion object {
        const val SYSTEM = "com.apple.assetsd.migration.16777230.3098355"
        const val SYNDICATION = "com.apple.assetsd.migration.16777230.3140993"
        const val STAT = "2026-10-02 12:07:49.039 Df assetsd[17291:c9a9] [com.apple.photos.backend:Migration] " +
            "tag:37AE79AF:sc      stat: st_dev=16777230 st_ino=3098355 st_mode=16893 (drwxrwxr-x) st_nlink=6 st_uid=501"
        const val SUBMIT_SYSTEM = "2026-10-02 12:07:53.681 Df assetsd[17291:c9a9] [com.apple.photos.backend:Backend] " +
            "Submitting task request for background migration with identifier $SYSTEM"
        const val SUBMIT_SYNDICATION = "2026-10-02 12:08:32.594 Df assetsd[17291:d0a1] " +
            "[com.apple.photos.backend:Backend] Submitting task request for background migration with identifier " +
            SYNDICATION
        const val RESUBMIT_SYSTEM = "2026-10-02 12:10:23.473 Df assetsd[17291:f46e] " +
            "[com.apple.BackgroundSystemTasks:BGSTFramework] Cancelling and resubmitting $SYSTEM due to a failed launch " +
            "acknowledgment"
        const val EXPIRE_SYNDICATION = "2026-10-02 12:10:23.952 Df assetsd[17291:f190] " +
            "[com.apple.BackgroundSystemTasks:BGSTFramework] Client requested expiration of task " +
            "<BGNonRepeatingSystemTask: $SYNDICATION>"
        const val COMPLETE_SYSTEM = "2026-10-02 12:12:03.270 Df assetsd[17291:f190] " +
            "[com.apple.BackgroundSystemTasks:BGSTFramework] Marking task <BGNonRepeatingSystemTask: $SYSTEM> complete"
    }
}
