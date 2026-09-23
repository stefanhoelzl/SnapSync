package app.snapsync.contracts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The photo contracts share one library per run, and a clause reads only its own capture window
 * ([PhotoLibrary.window]). Two clauses that hashed to the same day would read each other's seeds, so this
 * asserts that no two windows collide, and that each window is what it claims to be.
 */
class PhotoLibraryWindowsTest {

    private val photoContracts: List<Contract<*, *>> = PhotoLibrary.contracts

    @Test
    fun `no two photo clauses share a capture window`() {
        val byDay = photoContracts
            .flatMap { contract -> contract.clauses.map { "${contract.name}/${it.id}" to PhotoLibrary.window(contract.name, it.id).epochDay } }
            .groupBy({ it.second }, { it.first })
            .filterValues { it.size > 1 }
        assertTrue(byDay.isEmpty(), "clauses sharing a window: $byDay")
    }

    @Test
    fun `a window is one day with its seed inside it`() {
        val window = CaptureWindow(epochDay = 3652)
        assertEquals("1980-01-01T00:00:00Z", window.start)
        assertEquals("1980-01-02T00:00:00Z", window.end)
        assertEquals("1980-01-01T12:00:00Z", window.seedDate)
        assertTrue(window.seedDate in window)
        assertTrue(window.end !in window)
    }

    @Test
    fun `the civil date is right across a leap day`() {
        assertEquals("1984-02-29T00:00:00Z", CaptureWindow(epochDay = 5172).start)
        assertEquals("1984-03-01T00:00:00Z", CaptureWindow(epochDay = 5173).start)
    }
}
