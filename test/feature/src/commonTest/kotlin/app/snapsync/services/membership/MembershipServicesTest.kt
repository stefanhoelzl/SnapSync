package app.snapsync.services.membership

import app.snapsync.data.crypto.EventKeyIds
import app.snapsync.data.crypto.EventKeyStore
import app.snapsync.data.membership.MembershipRead
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.event.KeyFact
import app.snapsync.domain.event.KeyToKeep
import app.snapsync.domain.membership.ConfirmedNotJoined
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.sharing.Direction
import app.snapsync.feature.support.SharedStore
import app.snapsync.format.TimestampText
import app.snapsync.mock.fakeCrypto
import app.snapsync.mock.fixedClock
import app.snapsync.mock.inMemorySecureStore
import app.snapsync.model.EventConfig
import app.snapsync.testids.testEventId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The membership's services the old zones still hold (`PENDING_CLEANUPS.md`): the projection they read it through, the
 * join-in-progress a sweep of a failed leave's leftovers waits out, and the key a join adopts.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MembershipServicesTest {

    private val joined = EventConfig(
        eventId = testEventId("E"),
        name = "Party",
        minPhotoDate = CaptureCutoff(TimestampText.parse("2026-07-06T12:00:00Z")),
        startsAt = EventStart(TimestampText.parse("2026-07-06T12:00:00Z")),
        endsAt = EventEnd(TimestampText.parse("2026-07-13T12:00:00Z")),
        maxPhotoDate = CaptureCeiling(TimestampText.parse("2026-07-13T12:00:00Z")),
        deletesAt = DeletesAt(TimestampText.parse("2026-08-05T12:00:00Z")),
    )

    /** The membership [store] holds, projected for the old zones at [now]. */
    private fun projection(store: SharedStore, now: String = "2026-07-10T00:00:00Z") =
        MembershipProjection(MembershipRepository(store.db), fixedClock(Instant.parse(now)))

    /** What a read of a store holding no membership proves. */
    private fun none(): ConfirmedNotJoined =
        (MembershipRepository(SharedStore().db).read() as MembershipRead.NotJoined).evidence

    // ---- the projection ----

    @Test
    fun `the projection follows the membership and emits each change once`() = runTest {
        val store = SharedStore()
        val config = projection(store)
        assertEquals(listOf(null), config.config.replayCache)
        val seen = mutableListOf<EventConfig?>()
        backgroundScope.launch { config.config.collect { seen += it } }
        runCurrent()
        config.reload() // a fresh read that still finds none: nothing new to emit
        runCurrent()
        store.persist(joined)
        config.reload()
        runCurrent()
        assertEquals(listOf(null, joined), seen)
    }

    @Test
    fun `a membership another process wrote is read fresh before the next reload`() {
        val store = SharedStore()
        val config = projection(store)
        assertNull(config.config.value)
        store.persist(joined)
        assertNull(config.config.value, "the state is re-read only at a reload")
        assertEquals(joined, config.joinedOrRead())
        config.reload()
        assertEquals(joined, config.joinedOrRead())
    }

    @Test
    fun `a membership that cannot be read is none to the old zones`() {
        val store = SharedStore().apply { membershipUnreadable() }
        val config = projection(store)
        assertNull(config.joinedOrRead())
        assertFalse(config.freshReadShareHasEnded())
    }

    @Test
    fun `downloads run only for a membership that receives and is not deleted`() {
        val store = SharedStore()
        val config = projection(store)
        assertNull(config.downloadsEnabled(), "no membership: unknown, never a yes")
        for ((membership, enabled) in listOf(
            joined to true,
            joined.copy(direction = Direction.UploadOnly) to false,
            joined.copy(deleted = true) to false,
        )) {
            store.persist(membership)
            config.reload()
            assertEquals(enabled, config.downloadsEnabled(), "$membership")
        }
    }

    @Test
    fun `a fresh read says whether the share's range has ended`() {
        val store = SharedStore()
        assertFalse(projection(store).freshReadShareHasEnded(), "no membership")
        store.persist(joined)
        assertFalse(projection(store).freshReadShareHasEnded())
        assertTrue(projection(store, now = "2026-07-14T00:00:00Z").freshReadShareHasEnded())
    }

    @Test
    fun `the server's word heard by a publish marks the joined event deleted, and nothing else`() {
        val store = SharedStore()
        val config = projection(store)
        config.markOver(testEventId("E"))
        assertNull(config.joinedOrRead(), "no membership: nothing to mark")

        store.persist(joined)
        config.markOver(testEventId("F"))
        assertEquals(joined, config.joinedOrRead(), "another event's word marks nothing")

        config.markOver(testEventId("E"))
        config.reload()
        assertEquals(joined.copy(deleted = true), config.joinedOrRead(), "the app never leaves an event on its own")
    }

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
