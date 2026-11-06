package app.snapsync.services.membership

import app.snapsync.data.crypto.EventKeyIds
import app.snapsync.data.crypto.EventKeyStore
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.domain.event.KeyFact
import app.snapsync.domain.event.KeyToKeep
import app.snapsync.domain.membership.ConfirmedNotJoined
import app.snapsync.domain.membership.MembershipRead
import app.snapsync.feature.support.SharedStore
import app.snapsync.mock.fakeCrypto
import app.snapsync.mock.inMemorySecureStore
import app.snapsync.testids.testEventId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * The membership's services the old zones still hold (`PENDING_CLEANUPS.md`): the join-in-progress a sweep of a failed
 * leave's leftovers waits out, and the key a join adopts.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MembershipServicesTest {

    /** What a read of a store holding no membership proves. */
    private fun none(): ConfirmedNotJoined =
        (MembershipRepository(SharedStore().db).read() as MembershipRead.NotJoined).evidence

    // ---- a join in progress ----

    @Test
    fun `a sweep runs when no join is keeping its membership, and waits out none`() = runTest {
        val transition = MembershipTransition()
        var swept = 0
        transition.end() // a join that never began ends nothing
        transition.unlessJoining { swept++ }

        transition.begin()
        transition.unlessJoining { swept++ }
        assertEquals(1, swept, "a join in progress keeps what a sweep would take")

        transition.end()
        transition.unlessJoining { swept++ }
        assertEquals(2, swept)
    }

    @Test
    fun `a join waits for a sweep already running`() = runTest {
        val transition = MembershipTransition()
        val sweeping = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val sweep = launch {
            transition.unlessJoining {
                sweeping.complete(Unit)
                release.await()
            }
        }
        sweeping.await()
        val join = launch { transition.begin() }
        runCurrent()
        assertFalse(join.isCompleted, "the join waits")
        release.complete(Unit)
        sweep.join()
        join.join()
        transition.end()
    }

    // ---- the key a failed leave left ----

    @Test
    fun `a leftover key is forgotten unless a join is keeping one`() = runTest {
        val ids = EventKeyIds(fakeCrypto())
        val keys = EventKeyStore(inMemorySecureStore(), ids)
        val transition = MembershipTransition()
        keys.keep(KeyToKeep.Key(ids.mint()), testEventId("E"))

        transition.begin()
        transition.unlessJoining { keys.forget(none()) }
        assertIs<KeyFact.Kept>(keys.state.value, "the join's own key stays")

        transition.end()
        transition.unlessJoining { keys.forget(none()) }
        assertEquals(KeyFact.Absent, keys.state.value)
    }
}
