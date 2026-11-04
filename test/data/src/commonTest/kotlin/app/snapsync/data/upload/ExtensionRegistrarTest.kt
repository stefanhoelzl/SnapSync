package app.snapsync.data.upload

import app.snapsync.data.gallery.FullGrantRead
import app.snapsync.data.gallery.GrantRead
import app.snapsync.data.membership.active
import app.snapsync.domain.membership.ConfirmedNotJoined
import app.snapsync.domain.membership.LeftMembership
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.membership.MintsConfirmedNotJoined
import app.snapsync.domain.membership.MintsLeftMembership
import app.snapsync.domain.upload.ExtensionSwitch
import app.snapsync.domain.upload.FullGrant
import app.snapsync.domain.upload.MintsFullGrant
import app.snapsync.ports.ExtensionRegistry
import app.snapsync.ports.LibraryGrant
import app.snapsync.ports.RegistrationAnswer
import app.snapsync.ports.RegistrationState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The upload extension's registration: registered only with a full grant in hand, by the disable → enable toggle that
 * repairs a stale record; deregistered only once a membership's uploads end; every write reported at what it did.
 */
@OptIn(MintsFullGrant::class, MintsLeftMembership::class, MintsConfirmedNotJoined::class)
class ExtensionRegistrarTest {

    private class Registry(var answers: ArrayDeque<RegistrationAnswer> = ArrayDeque()) : ExtensionRegistry {
        val writes = mutableListOf<Boolean>()
        override val supported = true

        override suspend fun setEnabled(enabled: Boolean): RegistrationAnswer {
            writes += enabled
            return answers.removeFirstOrNull() ?: RegistrationAnswer.Answered(ok = true, domain = null, code = null)
        }

        override fun isEnabled(): RegistrationState = RegistrationState.REGISTERED
    }

    private val registry = Registry()
    private val registrar = ExtensionRegistrar(registry)

    private fun refused(code: Long?) = RegistrationAnswer.Answered(
        ok = false,
        domain = "PHPhotosErrorDomain",
        code = code,
    )

    @Test
    fun `a register is a disable then an enable, never a bare enable`() = runTest {
        registrar.register(FullGrant())

        assertEquals(listOf(false, true), registry.writes)
    }

    @Test
    fun `the rig's switch turned off deregisters the extension at once, and on touches nothing`() = runTest {
        registrar.followSwitch(ExtensionSwitch.ON)
        assertEquals(emptyList(), registry.writes)
        registrar.followSwitch(ExtensionSwitch.OFF)
        assertEquals(listOf(false), registry.writes)
    }

    @Test
    fun `a deregister is the disable alone, for each way a membership's uploads end`() = runTest {
        registrar.deregister(LeftMembership(active().event.id))
        registrar.deregister(ConfirmedNotJoined())
        registrar.deregister(Membership.Deleted(active().event, active().share))

        assertEquals(listOf(false, false, false), registry.writes)
    }

    @Test
    fun `every answer the platform gives is reported, a refusal and a failure included`() = runTest {
        registry.answers = ArrayDeque(
            listOf(refused(3201), refused(3311), refused(3311), refused(3202), RegistrationAnswer.Unsupported),
        )

        registrar.register(FullGrant())
        registrar.register(FullGrant())
        registrar.deregister(ConfirmedNotJoined())

        assertEquals(listOf(false, true, false, true, false), registry.writes)
    }

    @Test
    fun `only a full grant mints the evidence a register requires`() {
        val grant = MutableStateFlow(LibraryGrant.GRANTED)
        val read = GrantRead(grant)
        assertIs<FullGrantRead.Full>(read.full())
        LibraryGrant.entries.filter { it != LibraryGrant.GRANTED }.forEach {
            grant.value = it
            assertEquals(FullGrantRead.NotFull, read.full(), "$it")
        }
    }
}
