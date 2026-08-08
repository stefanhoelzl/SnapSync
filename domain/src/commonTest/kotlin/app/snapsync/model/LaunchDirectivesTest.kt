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
    fun `SNAPSYNC_EXPORT_LOGS is triggered by presence whatever the value`() {
        // Presence, like every sibling flag: `dvt launch --env SNAPSYNC_EXPORT_LOGS=` yields a blank
        // string the OS still reports as present, so a value check would make the most natural
        // invocation silently inert.
        for (value in listOf("1", "", "0", "false", "anything")) {
            assertTrue(
                LaunchDirectives.from(env("SNAPSYNC_EXPORT_LOGS" to value)).exportLogs,
                "a present SNAPSYNC_EXPORT_LOGS=$value must trigger the export",
            )
        }
        assertFalse(LaunchDirectives.from { null }.exportLogs)
    }

    @Test
    fun `the log export is independent of every membership trigger`() {
        // It mutates no membership, so it takes no part in the reset -> leave -> create -> event-link
        // ordering, and a launch that only exports must leave the membership exactly alone.
        val directives = LaunchDirectives.from(env("SNAPSYNC_EXPORT_LOGS" to "1"))
        assertTrue(directives.exportLogs)
        assertFalse(directives.resetState)
        assertFalse(directives.leave)
        assertEquals(null, directives.createEvent)
        assertEquals(null, directives.eventLink)
    }

    @Test
    fun `each SNAPSYNC_WIPE_GALLERY scope selects exactly what it names`() {
        // The scope IS the whole decision — the shell reads these two booleans and switches on nothing.
        // `albums` deleting no asset is the load-bearing half: deleting a collection never deletes its
        // members, so that scope must leave every photo in place.
        val all = LaunchDirectives.from(env("SNAPSYNC_WIPE_GALLERY" to "all")).wipeGallery
        assertTrue(all.includesAssets)
        assertTrue(all.includesAlbums)

        val assets = LaunchDirectives.from(env("SNAPSYNC_WIPE_GALLERY" to "assets")).wipeGallery
        assertTrue(assets.includesAssets)
        assertFalse(assets.includesAlbums)

        val albums = LaunchDirectives.from(env("SNAPSYNC_WIPE_GALLERY" to "albums")).wipeGallery
        assertFalse(albums.includesAssets)
        assertTrue(albums.includesAlbums)
    }

    @Test
    fun `a scope token is matched trimmed and case-insensitively`() {
        for (value in listOf("ALL", " all", "all ", "All")) {
            assertEquals(
                WipeRequest.Wipe(WipeScope.ALL),
                LaunchDirectives.from(env("SNAPSYNC_WIPE_GALLERY" to value)).wipeGallery,
                "a shell-quoted or capitalized `$value` names the same scope",
            )
        }
    }

    @Test
    fun `an unrecognized SNAPSYNC_WIPE_GALLERY value wipes nothing and says so`() {
        // The trigger is irreversible, so — alone among these variables — presence is NOT the trigger:
        // a typo (`asset`), a leftover `1`, and the blank string `--env SNAPSYNC_WIPE_GALLERY=` produces
        // must all refuse rather than pick a scope on the operator's behalf.
        for (value in listOf("", " ", "1", "true", "asset", "everything")) {
            val request = LaunchDirectives.from(env("SNAPSYNC_WIPE_GALLERY" to value)).wipeGallery
            assertEquals(WipeRequest.Unrecognized(value), request, "`$value` is not a scope")
            assertFalse(request.wipesAnything, "`$value` must delete nothing")
            // "Set but unusable" and "unset" are different answers, and the log line is where the
            // difference lands — an operator who typed a scope must not read silence as success.
            assertTrue(value in request.plan && "wiping nothing" in request.plan, request.plan)
        }
    }

    @Test
    fun `an absent SNAPSYNC_WIPE_GALLERY wipes nothing`() {
        val request = LaunchDirectives.from { null }.wipeGallery
        assertEquals(WipeRequest.None, request)
        assertFalse(request.wipesAnything)
    }

    @Test
    fun `the wipe is independent of every membership trigger`() {
        // It mutates no membership; it is ordered against the membership triggers by the shell (the whole
        // photo-library path completes before they run), not by anything parsed here.
        val directives = LaunchDirectives.from(env("SNAPSYNC_WIPE_GALLERY" to "all"))
        assertTrue(directives.wipeGallery.wipesAnything)
        assertFalse(directives.resetState)
        assertFalse(directives.leave)
        assertEquals(null, directives.createEvent)
        assertEquals(null, directives.eventLink)
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
