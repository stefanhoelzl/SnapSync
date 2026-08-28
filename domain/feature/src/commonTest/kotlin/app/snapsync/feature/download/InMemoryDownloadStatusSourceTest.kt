package app.snapsync.feature.download

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The settable [DownloadStatusSource] the harness and the inert default wiring stand on.
 *
 * Its default is the whole point and is not tidiness: the joined screen's direction arrows are
 * **conjunctive** — "In sync" shows exactly when both are hidden — and the download arrow hides when
 * `downloaded >= total`. A placeholder `(0, 0)` marked *read* satisfies that on its own, so an un-read
 * download projection would carry the screen to a settled checkmark over a device that has collected
 * nothing (the shape behind `SNAPSYNC-14`/`SNAPSYNC-16`). [DownloadProgress.UNREAD] is a different value
 * from a counted-empty union, and this pins that the default is the former.
 */
class InMemoryDownloadStatusSourceTest {

    @Test
    fun `the default is un-read rather than a counted-empty union`() {
        val source = InMemoryDownloadStatusSource()

        assertEquals(DownloadProgress.UNREAD, source.progress.value)
        assertFalse(source.progress.value.read, "an un-read projection must not present as a real count")
    }

    @Test
    fun `a stated progress replaces the default and reads as counted`() {
        val source = InMemoryDownloadStatusSource()

        source.set(DownloadProgress(downloaded = 2, total = 5))

        assertEquals(DownloadProgress(downloaded = 2, total = 5), source.progress.value)
        assertTrue(source.progress.value.read)
    }

    @Test
    fun `refresh is inert so a stated value survives it`() = runTest {
        // The harness states progress; nothing re-reads it behind the operator's back.
        val source = InMemoryDownloadStatusSource(DownloadProgress(downloaded = 1, total = 3))

        source.refresh()

        assertEquals(DownloadProgress(downloaded = 1, total = 3), source.progress.value)
    }
}
