package app.snapsync.join

import app.snapsync.config.ConfigSource
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
    details: EventDetails = EventDetails.Found("Anna's Wedding", "2026-07-04T18:00:00Z"),
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
        .join(EVENT_A, "Anna's Wedding", null)

    assertEquals(JoinOutcome.Committed, outcome)
    assertEquals(listOf(EVENT_A to DEVICE), enroller.calls)
    assertEquals(listOf(EventConfig(EVENT_A, "Anna's Wedding")), provisioned)
}

@Test
fun `a failed enrollment commits nothing`() = runTest {
    val provisioned = mutableListOf<EventConfig>()
    val outcome = joinEvent(config = null, enrollResult = false, provisioned = provisioned)
        .join(EVENT_A, "Anna's Wedding", null)

    assertEquals(JoinOutcome.EnrollFailed, outcome)
    assertTrue(provisioned.isEmpty(), "no config should be provisioned on a failed enrollment")
}

@Test
fun `re-joining the current event is a no-op that skips enrollment`() = runTest {
    val provisioned = mutableListOf<EventConfig>()
    val enroller = FakeEnroller(result = true)
    val outcome = joinEvent(config = EventConfig(EVENT_A, "Anna's Wedding"), enroller = enroller, provisioned = provisioned)
        .join(EVENT_A, "Anna's Wedding", null)

    assertEquals(JoinOutcome.AlreadyJoined, outcome)
    assertTrue(enroller.calls.isEmpty(), "the already-joined event must not be re-enrolled")
    assertTrue(provisioned.isEmpty())
}

@Test
fun `switching to a different event enrolls`() = runTest {
    val enroller = FakeEnroller(result = true)
    val outcome = joinEvent(config = EventConfig(EVENT_A, "Old"), enroller = enroller)
        .join(EVENT_B, "New", null)

    assertEquals(JoinOutcome.Committed, outcome)
    assertEquals(listOf(EVENT_B to DEVICE), enroller.calls)
}

@Test
fun `loadDetails surfaces found not-found and failed distinctly`() = runTest {
    assertEquals(EventDetails.Found("N", null), joinEvent(config = null, details = EventDetails.Found("N", null)).loadDetails(EVENT_A))
    assertEquals(EventDetails.NotFound, joinEvent(config = null, details = EventDetails.NotFound).loadDetails(EVENT_A))
    assertEquals(EventDetails.Failed, joinEvent(config = null, details = EventDetails.Failed).loadDetails(EVENT_A))
}

    @Test
    fun `join commits a null name`() = runTest {
        val provisioned = mutableListOf<EventConfig>()
        joinEvent(config = null, provisioned = provisioned).join(EVENT_A, null, null)
        assertNull(provisioned.single().name)
    }

    @Test
    fun `join persists the chosen capture-date cutoff`() = runTest {
        val provisioned = mutableListOf<EventConfig>()
        joinEvent(config = null, provisioned = provisioned)
            .join(EVENT_A, "Anna's Wedding", "2026-07-04T18:00:00Z")
        assertEquals("2026-07-04T18:00:00Z", provisioned.single().minPhotoDate)
    }
}
