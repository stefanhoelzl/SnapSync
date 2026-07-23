package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Parsing of the developer/test launch-environment triggers (capability `ios-app-shell`).
 *
 * Runs on JVM **and** `iosSimulatorArm64`, which is the point of lifting the parse out of the untested
 * shell: the variables are only ever injected by a developer launch, so a parse bug would otherwise be
 * discoverable only on a device.
 */
class LaunchDirectivesTest {

    private fun env(vararg pairs: Pair<String, String>): (String) -> String? =
        { name -> pairs.toMap()[name] }

    @Test
    fun `an empty environment yields NONE`() {
        assertEquals(LaunchDirectives.NONE, LaunchDirectives.from { null })
    }

    @Test
    fun `SNAPSYNC_RESET_STATE is triggered by presence whatever the value`() {
        // Presence — not a value — is the trigger, like SNAPSYNC_LEAVE. `dvt launch --env X=` yields a
        // blank string, which the OS still reports as present, so a value check would make the most
        // natural invocation silently inert.
        for (value in listOf("1", "", "0", "false", "anything")) {
            assertTrue(
                LaunchDirectives.from(env("SNAPSYNC_RESET_STATE" to value)).resetState,
                "a present SNAPSYNC_RESET_STATE=$value must trigger the reset",
            )
        }
    }

    @Test
    fun `an absent SNAPSYNC_RESET_STATE does not trigger the reset`() {
        assertFalse(LaunchDirectives.from { null }.resetState)
        assertFalse(LaunchDirectives.from(env("SNAPSYNC_LEAVE" to "1")).resetState)
    }

    @Test
    fun `reset is independent of the other membership triggers`() {
        // Each trigger contributes only itself: a launch may set any subset, and the coordinator's
        // ordering is what composes them.
        val directives = LaunchDirectives.from(
            env(
                "SNAPSYNC_RESET_STATE" to "1",
                "SNAPSYNC_LEAVE" to "1",
                "SNAPSYNC_CREATE_EVENT" to "payload",
                "SNAPSYNC_EVENT_LINK" to "https://x/join#d",
            ),
        )
        assertTrue(directives.resetState)
        assertTrue(directives.leave)
        assertEquals("payload", directives.createEvent)
        assertEquals("https://x/join#d", directives.eventLink)
    }
}
