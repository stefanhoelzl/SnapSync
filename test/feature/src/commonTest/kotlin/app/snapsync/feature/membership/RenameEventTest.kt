@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.snapsync.feature.membership

import app.snapsync.model.Direction
import app.snapsync.model.EventConfig
import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.model.eventStart
import app.snapsync.feature.support.ConfigWrites
import app.snapsync.services.backend.EventRename
import app.snapsync.model.RenameOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import app.snapsync.feature.membership.readmodel.MutableRenameStatusSource
import app.snapsync.feature.membership.readmodel.RenameFailureReason
import app.snapsync.feature.membership.readmodel.RenameStatus

private val FIXTURE_CEILING = captureCeiling("2099-01-01T00:00:00Z")

class RenameEventTest {

    /** The membership persisted before the rename runs. */
    private class Membership(val config: EventConfig?)

    /** Records what was sent and answers with a scripted outcome. */
    private class FakeRename(private val outcome: RenameOutcome) : EventRename {
        var sentEventId: String? = null
        var sentName: String? = null
        var calls = 0
        override suspend fun rename(eventId: String, name: String): RenameOutcome {
            sentEventId = eventId
            sentName = name
            calls++
            return outcome
        }
    }

    private fun current(eventId: String = "E1", name: String = "Weekend") = EventConfig(
        eventId = eventId,
        name = name,
        minPhotoDate = captureCutoff("2026-07-06T12:00:00Z"),
        startsAt = eventStart("2026-07-06T12:00:00Z"),
        maxPhotoDate = FIXTURE_CEILING,
        direction = Direction.Both,
        saveToAlbum = true,
    )

    /** Drive the fire-and-forget command to completion on the test scheduler. */
    private suspend fun TestScope.drive(
        source: Membership,
        store: ConfigWrites,
        client: EventRename,
        status: MutableRenameStatusSource,
        eventId: String = "E1",
        name: String = "Ana's 30th",
    ): RenameEvent {
        val useCase = RenameEvent(store.service(source.config), client, status)
        useCase.rename(eventId, name)
        runCurrent()
        return useCase
    }

    @Test
    fun `a successful rename saves the whole config exactly once — with only the name changed`() = runTest {
        val store = ConfigWrites()
        val status = MutableRenameStatusSource()
        drive(Membership(current()), store, FakeRename(RenameOutcome.Renamed("Ana's 30th")), status)

        val saved = store.saved!!
        assertEquals(1, store.saveCount)
        assertEquals("Ana's 30th", saved.name)
        // Everything else survives — the whole-object save with one field replaced.
        assertEquals(current().copy(name = "Ana's 30th"), saved)
        assertEquals(RenameStatus.Succeeded, status.renameStatus.value)
    }

    @Test
    fun `the ECHOED name is persisted — not the submitted one`() = runTest {
        // The backend trims and its echo is authoritative; persisting the input would let the stored name
        // drift from the marker by exactly the whitespace the backend removed.
        val store = ConfigWrites()
        val client = FakeRename(RenameOutcome.Renamed("Ana's 30th"))
        drive(Membership(current()), store, client, MutableRenameStatusSource(), name = "  Ana's 30th  ")

        assertEquals("  Ana's 30th  ".trim(), client.sentName) // the client is sent a trimmed value…
        assertEquals("Ana's 30th", store.saved!!.name) // …but what lands is the echo
    }

    @Test
    fun `an echo equal to the current name saves nothing`() = runTest {
        val store = ConfigWrites()
        val status = MutableRenameStatusSource()
        drive(
            Membership(current(name = "Weekend")),
            store,
            FakeRename(RenameOutcome.Renamed("Weekend")),
            status,
        )
        assertEquals(0, store.saveCount) // no needless write, no woken observers
        assertEquals(RenameStatus.Succeeded, status.renameStatus.value)
    }

    @Test
    fun `a result for a no-longer-current event persists nothing`() = runTest {
        // A rename landing after a switch or a leave describes someone else's membership.
        val store = ConfigWrites()
        drive(
            Membership(current(eventId = "E2")),
            store,
            FakeRename(RenameOutcome.Renamed("Ana's 30th")),
            MutableRenameStatusSource(),
            eventId = "E1",
        )
        assertNull(store.saved)
        assertEquals(0, store.saveCount)
    }

    @Test
    fun `a rename with no membership persists nothing`() = runTest {
        val store = ConfigWrites()
        drive(Membership(null), store, FakeRename(RenameOutcome.Renamed("Ana's 30th")), MutableRenameStatusSource())
        assertNull(store.saved)
    }

    @Test
    fun `an invalid name fails with the invalid-name reason and persists nothing`() = runTest {
        val store = ConfigWrites()
        val status = MutableRenameStatusSource()
        drive(Membership(current()), store, FakeRename(RenameOutcome.InvalidName), status)

        assertEquals(RenameStatus.Failed(RenameFailureReason.INVALID_NAME), status.renameStatus.value)
        assertEquals(0, store.saveCount)
    }

    @Test
    fun `a transient failure fails with the server reason and persists nothing`() = runTest {
        val store = ConfigWrites()
        val status = MutableRenameStatusSource()
        drive(Membership(current()), store, FakeRename(RenameOutcome.Transient), status)

        assertEquals(RenameStatus.Failed(RenameFailureReason.SERVER), status.renameStatus.value)
        assertEquals(0, store.saveCount)
    }

    @Test
    fun `NO failure path is destructive — the membership survives every outcome`() = runTest {
        // The single-witness rule (capability `manage-membership`): a 404 reaches this use-case as Transient,
        // and no outcome here may clear the config. There is exactly one door to the teardown, and it is
        // MembershipRefresh's two-witness path — not this one.
        for (outcome in listOf(RenameOutcome.InvalidName, RenameOutcome.Transient)) {
            val store = ConfigWrites()
            drive(Membership(current()), store, FakeRename(outcome), MutableRenameStatusSource())
            assertTrue(!store.cleared, "$outcome cleared the membership config")
            assertNull(store.saved, "$outcome wrote to the membership config")
        }
    }

    @Test
    fun `the status sequence is InFlight then a terminal value`() = runTest {
        val seen = mutableListOf<RenameStatus>()
        val status = MutableRenameStatusSource()
        val client = object : EventRename {
            override suspend fun rename(eventId: String, name: String): RenameOutcome {
                seen += status.renameStatus.value // observed from inside the request
                return RenameOutcome.Renamed("Ana's 30th")
            }
        }
        drive(Membership(current()), ConfigWrites(), client, status)

        assertEquals(listOf<RenameStatus>(RenameStatus.InFlight), seen.toList())
        assertEquals(RenameStatus.Succeeded, status.renameStatus.value)
    }

    @Test
    fun `reset clears the terminal latch back to Idle`() = runTest {
        val status = MutableRenameStatusSource()
        val useCase =
            drive(Membership(current()), ConfigWrites(), FakeRename(RenameOutcome.Transient), status)
        assertEquals(RenameStatus.Failed(RenameFailureReason.SERVER), status.renameStatus.value)

        useCase.reset()
        assertEquals(RenameStatus.Idle, status.renameStatus.value)
    }

    @Test
    fun `the trimmed name and the event id are what reach the client`() = runTest {
        val client = FakeRename(RenameOutcome.Renamed("Ana's 30th"))
        drive(Membership(current()), ConfigWrites(), client, MutableRenameStatusSource(), name = "  Ana's 30th ")

        assertEquals("E1", client.sentEventId)
        assertEquals("Ana's 30th", client.sentName)
        assertEquals(1, client.calls)
    }
}
