package app.snapsync.fake

import app.snapsync.contracts.BackgroundTimeContract
import app.snapsync.contracts.BackgroundTimeState
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.Entered
import app.snapsync.contracts.currentHost
import app.snapsync.contracts.verify
import app.snapsync.ports.BackgroundTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The honest [BackgroundTime], held to the contract `IosBackgroundTime` satisfies — and to the system's table. */
class BackgroundTimeContractBindingTest {

    private val binding = object : Binding<BackgroundTimeState, BackgroundTime> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(BackgroundTimeState.TIME_REMAINS)

        override fun create(state: BackgroundTimeState, clauseId: String): Entered<BackgroundTime> =
            Entered.Ready(inMemoryBackgroundTime(MutableStateFlow(emptyList())))
    }

    @Test
    fun `the honest background time satisfies the contract`() = verify(BackgroundTimeContract, binding)

    @Test
    fun `the table holds exactly the holds not yet ended and an end removes only its own`() {
        val held = MutableStateFlow<List<HeldBackgroundTime>>(emptyList())
        val time = inMemoryBackgroundTime(held)
        val first = time.begin("first") {}
        val second = time.begin("second") {}
        assertEquals(listOf("first", "second"), held.value.map { it.label })
        first.end()
        first.end()
        assertEquals(listOf("second"), held.value.map { it.label }, "a repeated end removes nothing else")
        second.end()
        assertTrue(held.value.isEmpty())
    }

    @Test
    fun `the operating system's expiry runs the handler once and leaves the hold to its holder`() {
        val held = MutableStateFlow<List<HeldBackgroundTime>>(emptyList())
        var expiries = 0
        val hold = inMemoryBackgroundTime(held).begin("expiring") { expiries++ }
        held.value.single().expire()
        held.value.single().expire()
        assertEquals(1, expiries, "the handler runs at most once")
        assertEquals(1, held.value.size, "an expiry does not end the hold — the holder must")
        hold.end()
        assertTrue(held.value.isEmpty())
    }
}
