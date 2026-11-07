package app.snapsync.data.gallery

import app.snapsync.domain.report.GrantStanding
import app.snapsync.ports.LibraryGrant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

/** The grant as a bug report names it: each of the platform's answers, read now. */
class GrantStandingTest {
    @Test
    fun `each grant reads as its standing - a whole library as full`() {
        val grant = MutableStateFlow(LibraryGrant.NOT_DETERMINED)
        val read = GrantRead(grant)
        val standings = LibraryGrant.entries.map {
            grant.value = it
            read.standing()
        }
        assertEquals(
            listOf(GrantStanding.NOT_DETERMINED, GrantStanding.DENIED, GrantStanding.LIMITED, GrantStanding.FULL),
            standings,
        )
    }
}
