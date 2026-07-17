package app.snapsync.feature.creation

import app.snapsync.ports.CreateOutcome
import app.snapsync.ports.EventCreation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class CreateEventTest {

    private val eventId = "11111111-1111-4111-8111-111111111111"

    /** The event's start date, already canonical — the caller converts the local pick, not this layer. */
    private val startsAt = "2026-07-14T18:00:00Z"

    private class FakeClient(private val outcome: CreateOutcome) : EventCreation {
        var lastName: String? = null
        var lastStartsAt: String? = null
        override suspend fun create(name: String, startsAt: String): CreateOutcome {
            lastName = name
            lastStartsAt = startsAt
            return outcome
        }
    }

    @Test
    fun `success routes the minted event to the gate and returns to idle without a success status`() = runTest {
        val client = FakeClient(CreateOutcome.Created(eventId))
        val status = MutableCreationStatusSource()
        var provisioned: String? = null
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val useCase = CreateEvent(client, status, onMinted = { eventId -> provisioned = eventId }, scope = scope)

        useCase.create("  My Party  ", startsAt)

        assertEquals("My Party", client.lastName) // trimmed before the call
        assertEquals(startsAt, client.lastStartsAt) // start date passed through VERBATIM, never re-derived
        assertEquals(eventId, provisioned) // handed to the join-gate routing hook
        assertEquals(CreationStatus.Idle, status.creationStatus.value) // no success state
    }

    @Test
    fun `an invalid name fails with the invalid-name reason and does not provision`() = runTest {
        val status = MutableCreationStatusSource()
        var provisioned: String? = null
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val useCase = CreateEvent(
            FakeClient(CreateOutcome.InvalidName), status, onMinted = { eventId -> provisioned = eventId }, scope = scope,
        )

        useCase.create("x", startsAt)

        assertEquals(CreationStatus.Failed(CreationFailureReason.INVALID_NAME), status.creationStatus.value)
        assertNull(provisioned)
    }

    @Test
    fun `a transient failure fails with the server reason and does not provision`() = runTest {
        val status = MutableCreationStatusSource()
        var provisioned: String? = null
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val useCase = CreateEvent(
            FakeClient(CreateOutcome.Transient), status, onMinted = { eventId -> provisioned = eventId }, scope = scope,
        )

        useCase.create("x", startsAt)

        assertEquals(CreationStatus.Failed(CreationFailureReason.SERVER), status.creationStatus.value)
        assertNull(provisioned)
    }
}
