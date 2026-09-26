package app.snapsync.fake

import app.snapsync.model.RegistrationAnswer
import app.snapsync.model.RegistrationState
import app.snapsync.model.ScheduleResult
import app.snapsync.model.WakeId
import app.snapsync.model.WakeTrigger
import app.snapsync.ports.WakeHandlers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * The in-memory wake and registration doubles' own vocabulary, beyond their contracts: a platform without a kind of wake,
 * a registration listened to but never fired, and a platform without the upload extension.
 */
class InMemoryWakeTest {

    @Test
    fun `a wake the platform lacks is unsupported and queues nothing`() {
        val pending = MutableStateFlow<Map<WakeId, WakeTrigger>>(emptyMap())
        val wake = inMemoryWake(pending, supported = setOf(WakeId.Heartbeat))
        var woken = 0
        wake.listen(WakeHandlers(onWake = { _, _ -> woken++ }))
        assertEquals(ScheduleResult.Unsupported, wake.schedule(WakeId.LibraryChanged, WakeTrigger.LibraryChange(60.seconds)))
        assertEquals(ScheduleResult.Scheduled, wake.schedule(WakeId.Heartbeat, WakeTrigger.After(60.seconds, true)))
        assertEquals(setOf(WakeId.Heartbeat), pending.value.keys)
        assertEquals(0, woken, "nothing in memory fires a wake on its own")
    }

    @Test
    fun `a platform without the extension answers unsupported and one with it keeps the record`() = runTest {
        val none = inMemoryExtensionRegistry()
        assertEquals(RegistrationAnswer.Unsupported, none.setEnabled(true))
        assertEquals(RegistrationState.UNSUPPORTED, none.isEnabled())
        val record = MutableStateFlow(false)
        val registry = inMemoryExtensionRegistry(record)
        assertEquals(RegistrationAnswer.Answered(ok = true, domain = null, code = null), registry.setEnabled(true))
        assertEquals(RegistrationState.REGISTERED, registry.isEnabled())
        registry.setEnabled(false)
        assertEquals(RegistrationState.NOT_REGISTERED, registry.isEnabled())
    }
}
