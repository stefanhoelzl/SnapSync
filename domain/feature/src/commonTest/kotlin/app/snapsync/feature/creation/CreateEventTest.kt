package app.snapsync.feature.creation

import app.snapsync.domain.event.EventId
import app.snapsync.domain.event.OpenUntil
import app.snapsync.domain.service.ServiceFault
import app.snapsync.feature.creation.readmodel.CreationFailureReason
import app.snapsync.feature.creation.readmodel.CreationStatus
import app.snapsync.model.CreateOutcome
import app.snapsync.model.TimestampText
import app.snapsync.services.backend.EventCreation
import app.snapsync.testids.testEventId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class CreateEventTest {

    private val eventId = "11111111-1111-4111-8111-111111111111"

    /** The event's start date, already canonical — the caller converts the local pick, not this layer. */
    private val startsAt = "2026-07-14T18:00:00Z"

    /** The event's end date, already canonical — passed through the interactive create alongside startsAt. */
    private val endsAt = "2026-07-21T18:00:00Z"

    private class FakeClient(private val outcome: CreateOutcome) : EventCreation {
        var lastName: String? = null
        var lastStartsAt: String? = null
        var lastEndsAt: String? = null
        var lastOpenUntil: String? = null
        override suspend fun create(
            name: String,
            startsAt: String,
            endsAt: String?,
            keyId: String?,
            openUntil: String?,
        ): CreateOutcome {
            lastName = name
            lastStartsAt = startsAt
            lastEndsAt = endsAt
            lastOpenUntil = openUntil
            return outcome
        }
    }

    @Test
    fun `success routes the minted event to the gate and returns to idle without a success status`() = runTest {
        val client = FakeClient(CreateOutcome.Created(testEventId(eventId)))
        val status = MutableStateFlow<CreationStatus>(CreationStatus.Idle)
        var provisioned: EventId? = null
        val useCase = CreateEvent(client, status, onMinted = { eventId, _ -> provisioned = eventId }, minting = null)

        useCase.create("  My Party  ", startsAt, endsAt, openUntil = null)

        assertEquals("My Party", client.lastName) // trimmed before the call
        assertEquals(startsAt, client.lastStartsAt) // start date passed through VERBATIM, never re-derived
        assertEquals(endsAt, client.lastEndsAt) // end date passed through VERBATIM too
        assertEquals(testEventId(eventId), provisioned) // handed to the join-gate routing hook
        assertEquals(CreationStatus.Idle, status.value) // no success state
    }

    @Test
    fun `the open-until date of an event created after its end is passed through verbatim`() = runTest {
        val client = FakeClient(CreateOutcome.Created(testEventId(eventId)))
        val status = MutableStateFlow<CreationStatus>(CreationStatus.Idle)
        val useCase = CreateEvent(client, status, onMinted = { _, _ -> }, minting = null)

        useCase.create("Party", startsAt, endsAt, openUntil = OpenUntil(TimestampText.parse("2026-07-28T18:00:00Z")))

        assertEquals("2026-07-28T18:00:00Z", client.lastOpenUntil)
    }

    @Test
    fun `a second create while one is in flight mints nothing`() = runTest {
        // A double tap reaches the lane twice; the first mint is out when the second arrives. Two events used to be
        // minted (B13). The second is refused before it suspends, so nothing can come between the check and the set.
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val client = object : EventCreation {
            override suspend fun create(
                name: String,
                startsAt: String,
                endsAt: String?,
                keyId: String?,
                openUntil: String?,
            ): CreateOutcome {
                calls++
                gate.await()
                return CreateOutcome.Created(testEventId(eventId))
            }
        }
        val useCase =
            CreateEvent(
                client,
                MutableStateFlow<CreationStatus>(CreationStatus.Idle),
                onMinted = { _, _ -> },
                minting = null,
            )

        val first = launch { useCase.create("Party", startsAt, endsAt, openUntil = null) }
        runCurrent()
        useCase.create("Party", startsAt, endsAt, openUntil = null)
        gate.complete(Unit)
        first.join()

        assertEquals(1, calls)
    }

    @Test
    fun `an invalid name fails with the invalid-name reason and does not provision`() = runTest {
        val status = MutableStateFlow<CreationStatus>(CreationStatus.Idle)
        var provisioned: EventId? = null
        val useCase = CreateEvent(
            FakeClient(CreateOutcome.InvalidName),
            status,
            onMinted = { eventId, _ -> provisioned = eventId },
            minting = null,
        )

        useCase.create("x", startsAt, endsAt, openUntil = null)

        assertEquals(CreationStatus.Failed(CreationFailureReason.INVALID_NAME), status.value)
        assertNull(provisioned)
    }

    @Test
    fun `a refused date range fails with the invalid-window reason and does not provision`() = runTest {
        val status = MutableStateFlow<CreationStatus>(CreationStatus.Idle)
        var provisioned: EventId? = null
        val useCase = CreateEvent(
            FakeClient(CreateOutcome.InvalidWindow),
            status,
            onMinted = { eventId, _ -> provisioned = eventId },
            minting = null,
        )

        useCase.create("x", startsAt, endsAt, openUntil = null)

        assertEquals(CreationStatus.Failed(CreationFailureReason.INVALID_WINDOW), status.value)
        assertNull(provisioned)
    }

    @Test
    fun `a transient failure fails with the server reason and does not provision`() = runTest {
        val status = MutableStateFlow<CreationStatus>(CreationStatus.Idle)
        var provisioned: EventId? = null
        val useCase = CreateEvent(
            FakeClient(CreateOutcome.Transient()),
            status,
            onMinted = { eventId, _ -> provisioned = eventId },
            minting = null,
        )

        useCase.create("x", startsAt, endsAt, openUntil = null)

        assertEquals(CreationStatus.Failed(CreationFailureReason.SERVER, ServiceFault.UNREACHABLE), status.value)
        assertNull(provisioned)
    }

    @Test
    fun `a create refused for this phone's credential fails as unverified and does not provision`() = runTest {
        val status = MutableStateFlow<CreationStatus>(CreationStatus.Idle)
        var provisioned: EventId? = null
        val useCase = CreateEvent(
            FakeClient(CreateOutcome.Unverified),
            status,
            onMinted = { eventId, _ -> provisioned = eventId },
            minting = null,
        )

        useCase.create("x", startsAt, endsAt, openUntil = null)

        assertEquals(CreationStatus.Failed(CreationFailureReason.UNVERIFIED), status.value)
        assertNull(provisioned)
    }
}
