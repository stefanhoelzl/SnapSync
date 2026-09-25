package app.snapsync.world

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * When the composed app publishes its push registration (capability `receiving-photos`, "Registration timing —
 * launch, join, and rotation"; capability `sync-status`, "Push registration is started by the shared
 * composition"): installed as the graph is composed — on every cold start, a background one that never assembles
 * the host included — once per process, and publishing a delivered token only when it differs from the last one
 * the backend accepted. The join and fresh-credential triggers are `:test:integration`'s
 * (`PushRegistrationIntegrationTest`), which drives them through the control protocol.
 */
class PushRegistrationWorldTest {

    @Test
    fun a_background_cold_start_installs_the_registration_and_publishes_a_rotation() = worldTest {
        val w = World(this)
        // The host is never touched: this process is a background wake (a silent push, a transfer relaunch).
        w.pushTokens.deliver("TOKEN1")
        awaitRegistrations(w, 1)

        // Process death, then another background cold start: the OS re-delivers the same token.
        w.relaunch()
        w.pushTokens.deliver("TOKEN1")
        settle()
        assertEquals(1, w.registerPushCount, "an unchanged token is not re-published at launch")

        // A rotation learned in that background wake is published from it, not deferred to a foreground.
        w.pushTokens.deliver("TOKEN2")
        awaitRegistrations(w, 2)
    }

    @Test
    fun the_registration_is_installed_once_per_process() = worldTest {
        val w = World(this)
        // A background start that later assembles its host, and any other path reaching the installer again.
        w.statusHost
        w.core.installPushRegistration()
        w.core.installPushRegistration()

        w.pushTokens.deliver("TOKEN1")
        awaitRegistrations(w, 1)
        settle()
        assertEquals(1, w.registerPushCount, "one collector, so one delivery publishes once")
    }

    @Test
    fun an_unchanged_token_at_a_later_entry_publishes_nothing() = worldTest {
        val w = World(this)
        w.pushTokens.deliver("TOKEN1")
        awaitRegistrations(w, 1)

        w.pushTokens.deliver("TOKEN1") // the next foreground entry's answer
        settle()
        assertEquals(1, w.registerPushCount)
    }

    /**
     * Awaited on the world's count of LANDED registrations, never by polling the backend store — see
     * `PushRecordWorldTest.registerToken` for why.
     */
    private suspend fun awaitRegistrations(w: World, count: Int) {
        withTimeout(5_000) { while (w.registerPushCount < count) yield() }
    }

    /** Long enough for a publish the collector would make to land; the assertions after it are about absence. */
    private suspend fun settle() = delay(SETTLE_MILLIS)

    private companion object {
        const val SETTLE_MILLIS = 300L
    }
}
