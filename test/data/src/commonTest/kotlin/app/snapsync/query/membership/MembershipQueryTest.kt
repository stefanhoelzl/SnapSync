package app.snapsync.query.membership

import app.snapsync.data.membership.MembershipRepository
import app.snapsync.data.membership.active
import app.snapsync.data.membership.enrolled
import app.snapsync.data.membership.sharedOver
import app.snapsync.data.store.SharedDatabase
import app.snapsync.domain.membership.Membership
import app.snapsync.mock.DatabasesMock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The membership held, as a screen reads it: the one entered, and none while none is or none can be read. */
@OptIn(ExperimentalCoroutinesApi::class)
class MembershipQueryTest {

    private val device = DatabasesMock()
    private val repository = MembershipRepository(sharedOver(device))
    private val query = MembershipQuery(repository)

    @Test
    fun `the membership held follows the store and tells each change once`() = runTest {
        assertEquals(listOf(null), query.joined.replayCache)
        val seen = mutableListOf<Membership?>()
        backgroundScope.launch { query.joined.collect { seen += it } }
        runCurrent()
        repository.reload() // a fresh read that still finds none: nothing new to tell
        runCurrent()
        repository.enter(enrolled())
        runCurrent()
        assertEquals(active(), query.joined.value)
        assertEquals(listOf<Membership?>(null, active()), seen)
    }

    @Test
    fun `a membership that cannot be read is none held`() {
        val legacy = MembershipQuery(MembershipRepository(SharedDatabase(device.port(), legacy = null)))
        assertNull(legacy.joined.value)
    }
}
