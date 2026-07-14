package app.snapsync.join

import app.snapsync.config.ConfigSource
import app.snapsync.config.Direction
import app.snapsync.config.EventConfig
import app.snapsync.deviceid.DeviceIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val EVENT_A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
private const val EVENT_B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
private const val DEVICE = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"

/** Every membership carries a cutoff (capability `photo-date-cutoff`); there is no "no cutoff" join. */
private const val CUTOFF = "2026-07-06T14:32:11Z"

/**
 * The event's start date — the FLOOR under every membership's cutoff (capability `photo-date-cutoff`).
 * Deliberately earlier than [CUTOFF], so the clamp is a no-op for the tests that predate it and they go
 * on asserting exactly what they always did. The clamp's own behavior is pinned separately, below.
 */
private const val STARTS_AT = "2026-07-01T09:00:00Z"

private class FakeConfigSource(initial: EventConfig?) : ConfigSource {
    val state = MutableStateFlow(initial)
    override val config: StateFlow<EventConfig?> = state
}

private class FakeEnroller(private val result: Boolean) : DeviceEnroller {
    val calls = mutableListOf<Pair<String, String>>()
    override suspend fun enroll(eventId: String, deviceId: String): Boolean {
        calls += eventId to deviceId
        return result
    }
}

private class FakeDetails(private val result: EventDetails) : EventDetailsSource {
    override suspend fun fetch(eventId: String): EventDetails = result
}

private fun joinEvent(
    config: EventConfig?,
    enrollResult: Boolean = true,
    details: EventDetails = EventDetails.Found("Anna's Wedding", STARTS_AT),
    provisioned: MutableList<EventConfig> = mutableListOf(),
    enroller: FakeEnroller = FakeEnroller(enrollResult),
) = JoinEvent(
    configSource = FakeConfigSource(config),
    deviceIdentity = object : DeviceIdentity { override fun deviceId() = DEVICE },
    details = FakeDetails(details),
    enroller = enroller,
    provision = { provisioned += it },
)

class JoinEventTest {

    @Test
    fun `join enrolls then provisions on success`() = runTest {
    val provisioned = mutableListOf<EventConfig>()
    val enroller = FakeEnroller(result = true)
    val outcome = joinEvent(config = null, enroller = enroller, provisioned = provisioned)
        .join(EVENT_A, "Anna's Wedding", STARTS_AT, CUTOFF, Direction.Both, false)

    assertEquals(JoinOutcome.Committed, outcome)
    assertEquals(listOf(EVENT_A to DEVICE), enroller.calls)
    assertEquals(listOf(EventConfig(EVENT_A, "Anna's Wedding", CUTOFF, STARTS_AT)), provisioned)
}

@Test
fun `a failed enrollment commits nothing`() = runTest {
    val provisioned = mutableListOf<EventConfig>()
    val outcome = joinEvent(config = null, enrollResult = false, provisioned = provisioned)
        .join(EVENT_A, "Anna's Wedding", STARTS_AT, CUTOFF, Direction.Both, false)

    assertEquals(JoinOutcome.EnrollFailed, outcome)
    assertTrue(provisioned.isEmpty(), "no config should be provisioned on a failed enrollment")
}

@Test
fun `re-joining the current event is a no-op that skips enrollment`() = runTest {
    val provisioned = mutableListOf<EventConfig>()
    val enroller = FakeEnroller(result = true)
    val outcome = joinEvent(config = EventConfig(EVENT_A, "Anna's Wedding", CUTOFF), enroller = enroller, provisioned = provisioned)
        .join(EVENT_A, "Anna's Wedding", STARTS_AT, CUTOFF, Direction.Both, false)

    assertEquals(JoinOutcome.AlreadyJoined, outcome)
    assertTrue(enroller.calls.isEmpty(), "the already-joined event must not be re-enrolled")
    assertTrue(provisioned.isEmpty())
}

@Test
fun `switching to a different event enrolls`() = runTest {
    val enroller = FakeEnroller(result = true)
    val outcome = joinEvent(config = EventConfig(EVENT_A, "Old", CUTOFF), enroller = enroller)
        .join(EVENT_B, "New", STARTS_AT, CUTOFF, Direction.Both, false)

    assertEquals(JoinOutcome.Committed, outcome)
    assertEquals(listOf(EVENT_B to DEVICE), enroller.calls)
}

@Test
fun `loadDetails surfaces found not-found and failed distinctly`() = runTest {
    assertEquals(
        EventDetails.Found("N", STARTS_AT),
        joinEvent(config = null, details = EventDetails.Found("N", STARTS_AT)).loadDetails(EVENT_A),
    )
    assertEquals(EventDetails.NotFound, joinEvent(config = null, details = EventDetails.NotFound).loadDetails(EVENT_A))
    assertEquals(EventDetails.Failed, joinEvent(config = null, details = EventDetails.Failed).loadDetails(EVENT_A))
}

    @Test
    fun `join commits the loaded name`() = runTest {
        val provisioned = mutableListOf<EventConfig>()
        joinEvent(config = null, provisioned = provisioned).join(EVENT_A, "Anna's Wedding", STARTS_AT, CUTOFF, Direction.Both, false)
        assertEquals("Anna's Wedding", provisioned.single().name)
    }

    @Test
    fun `join persists the chosen saveToAlbum`() = runTest {
        val provisioned = mutableListOf<EventConfig>()
        joinEvent(config = null, provisioned = provisioned).join(EVENT_A, "Anna's Wedding", STARTS_AT, CUTOFF, Direction.Both, true)
        assertEquals(true, provisioned.single().saveToAlbum)
    }

    @Test
    fun `join persists the chosen capture-date cutoff`() = runTest {
        val provisioned = mutableListOf<EventConfig>()
        joinEvent(config = null, provisioned = provisioned)
            .join(EVENT_A, "Anna's Wedding", STARTS_AT, "2026-07-04T18:00:00Z", Direction.Both, false)
        assertEquals("2026-07-04T18:00:00Z", provisioned.single().minPhotoDate)
    }

    @Test
    fun `join persists the chosen participation direction`() = runTest {
        for (direction in Direction.entries) {
            val provisioned = mutableListOf<EventConfig>()
            joinEvent(config = null, provisioned = provisioned)
                .join(EVENT_A, "Anna's Wedding", STARTS_AT, CUTOFF, direction, false)
            assertEquals(direction, provisioned.single().direction)
        }
    }

    // ── the event-start floor (capability `photo-date-cutoff`) ────────────────────────────────────

    @Test
    fun `a cutoff below the event start is clamped up to it`() = runTest {
        val provisioned = mutableListOf<EventConfig>()
        joinEvent(config = null, provisioned = provisioned)
            .join(EVENT_A, "Anna's Wedding", STARTS_AT, "2026-06-01T00:00:00Z", Direction.Both, false)

        // The member asked for June 1st; the event began July 1st. Their photos from June are NOT the
        // event's, and never become uploadable.
        assertEquals(STARTS_AT, provisioned.single().minPhotoDate)
        assertEquals(STARTS_AT, provisioned.single().startsAt)
    }

    @Test
    fun `a cutoff above the event start is persisted unchanged`() = runTest {
        val provisioned = mutableListOf<EventConfig>()
        joinEvent(config = null, provisioned = provisioned)
            .join(EVENT_A, "Anna's Wedding", STARTS_AT, CUTOFF, Direction.Both, false)

        // The floor NARROWS; it never widens. A member who joins late keeps their own, later cutoff.
        assertEquals(CUTOFF, provisioned.single().minPhotoDate)
        assertEquals(STARTS_AT, provisioned.single().startsAt)
    }

    @Test
    fun `a hostile deeplink cutoff cannot lower the membership below the event start`() = runTest {
        // The attack this closes: `minPhotoDate` is decoded from ANY `snapsync://` URL, so a QR carrying
        // `autoJoin=true` + a distant-past cutoff would auto-confirm a join at near-whole-library scope
        // WITHOUT A TAP. The clamp lives in the use-case precisely so the autoJoin path cannot skip it.
        val provisioned = mutableListOf<EventConfig>()
        joinEvent(config = null, provisioned = provisioned)
            .join(EVENT_A, "Anna's Wedding", STARTS_AT, "2001-01-01T00:00:00Z", Direction.Both, false)

        assertEquals(STARTS_AT, provisioned.single().minPhotoDate, "the 2001 cutoff must not survive")
    }

    @Test
    fun `a future event start clamps the cutoff into the future so nothing can qualify`() = runTest {
        // "Nothing syncs before the event starts" is a THEOREM, not a gate: a photo's capture date cannot
        // be in the future, so a future cutoff admits nothing. No branch in the upload cycle enforces it.
        val future = "2099-12-31T23:59:59Z"
        val provisioned = mutableListOf<EventConfig>()
        joinEvent(config = null, provisioned = provisioned)
            .join(EVENT_A, "NYE", future, CUTOFF, Direction.Both, false)

        assertEquals(future, provisioned.single().minPhotoDate)
        assertTrue(
            "2026-07-06T14:32:11Z" < provisioned.single().minPhotoDate,
            "no photo taken today satisfies `creationDate >= cutoff` for a 2099 cutoff",
        )
    }

    @Test
    fun `every provisioned config satisfies minPhotoDate greater or equal startsAt`() = runTest {
        // The floor invariant, stated as such: it holds for EVERY join, whatever was chosen.
        for (chosen in listOf("2001-01-01T00:00:00Z", STARTS_AT, CUTOFF, "2099-01-01T00:00:00Z")) {
            val provisioned = mutableListOf<EventConfig>()
            joinEvent(config = null, provisioned = provisioned)
                .join(EVENT_A, "Anna's Wedding", STARTS_AT, chosen, Direction.Both, false)
            val saved = provisioned.single()
            assertTrue(saved.minPhotoDate >= saved.startsAt, "floor violated for chosen=$chosen")
        }
    }

    @Test
    fun `a download-only join still enrolls the device`() = runTest {
        val provisioned = mutableListOf<EventConfig>()
        val enroller = FakeEnroller(result = true)
        val outcome = joinEvent(config = null, enroller = enroller, provisioned = provisioned)
            .join(EVENT_A, "Anna's Wedding", STARTS_AT, CUTOFF, Direction.DownloadOnly, false)

        assertEquals(JoinOutcome.Committed, outcome)
        assertEquals(listOf(EVENT_A to DEVICE), enroller.calls, "download-only must still enroll (empty manifest)")
        assertEquals(Direction.DownloadOnly, provisioned.single().direction)
    }
}
