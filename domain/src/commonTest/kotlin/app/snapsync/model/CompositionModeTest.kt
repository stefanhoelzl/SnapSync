package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The pure composition-mode resolver's precedence (spec `module-architecture`, "One shared
 * composition"; decision record `establish-target-architecture` D5). The load-bearing case is the
 * **forge×link** interaction bug that shipped: a forge-state launch that ALSO carried an event link
 * must render the forged frame and boot **no** live stack — pinned here so the resolver can never
 * regress into provisioning a real event from a process rendering a screenshot.
 */
class CompositionModeTest {

    // A recognized-forge-state stub standing in for `presentation::isForgeState` (recognition is not
    // model/'s to make): only "in_sync" is recognized here.
    private val recognizes: (String) -> Boolean = { it == "in_sync" }

    // The only OS input the resolver takes: whether the ≥26.1 background-upload API is present.
    private val modernOs = true
    private val oldOs = false

    @Test
    fun `forge wins over a co-present event link - the shipped forge×link bug`() {
        val directives = LaunchDirectives.NONE.copy(
            forgeState = "in_sync",
            eventLink = "https://snapsync.stho.net/join#v=3&d=abc",
        )

        val mode = resolveComposition(directives, modernOs, recognizes)

        // Forge, not Live — the event link is ignored while forging, so the live stack is never booted.
        assertEquals(CompositionMode.Forge("in_sync"), mode)
    }

    @Test
    fun `an unrecognized forge state falls through to the live stack`() {
        val directives = LaunchDirectives.NONE.copy(forgeState = "nonsense")

        val mode = resolveComposition(directives, modernOs, recognizes)

        // Exactly as `SNAPSYNC_FORGE_STATE=nonsense` renders the live production stack today.
        assertIs<CompositionMode.Live>(mode)
    }

    @Test
    fun `no directives on a modern device resolves to the PhotoKit tier`() {
        val mode = resolveComposition(LaunchDirectives.NONE, modernOs, recognizes)

        assertEquals(CompositionMode.Live(UploadTier.PHOTOKIT), mode)
    }

    @Test
    fun `an old OS resolves to the URLSession tier`() {
        val mode = resolveComposition(LaunchDirectives.NONE, oldOs, recognizes)

        assertEquals(CompositionMode.Live(UploadTier.URL_SESSION), mode)
    }

    @Test
    fun `the force flag selects the URLSession tier even on a modern OS`() {
        val directives = LaunchDirectives.NONE.copy(forceUrlSessionUpload = true)

        val mode = resolveComposition(directives, modernOs, recognizes)

        assertEquals(CompositionMode.Live(UploadTier.URL_SESSION), mode)
    }

    @Test
    fun `directives parse from an environment reader`() {
        val env = mapOf(
            "SNAPSYNC_EVENT_LINK" to "https://x/join#d=1",
            "SNAPSYNC_CREATE_EVENT" to "eyJuYW1lIjoiWCJ9",
            "SNAPSYNC_LEAVE" to "1",
            "SNAPSYNC_SEED_PHOTOS" to "4000",
            "SNAPSYNC_SEED_POLICY" to "20",
            "SNAPSYNC_POLICY_PROBE" to "2026-07-01T00:00:00Z",
            "SNAPSYNC_FORGE_STATE" to "in_sync",
            "SNAPSYNC_FORCE_URLSESSION_UPLOAD" to "",
        )

        val d = LaunchDirectives.from { env[it] }

        assertEquals("https://x/join#d=1", d.eventLink)
        assertEquals("eyJuYW1lIjoiWCJ9", d.createEvent)
        assertEquals(true, d.leave)
        assertEquals(4000, d.seedPhotos)
        assertEquals(20, d.seedPolicy)
        assertEquals("2026-07-01T00:00:00Z", d.policyProbe)
        assertEquals("in_sync", d.forgeState)
        // Presence (even empty) is the trigger.
        assertEquals(true, d.forceUrlSessionUpload)
    }

    @Test
    fun `SNAPSYNC_LEAVE presence with an empty value still triggers`() {
        assertEquals(true, LaunchDirectives.from { if (it == "SNAPSYNC_LEAVE") "" else null }.leave)
        assertEquals(false, LaunchDirectives.from { null }.leave)
    }

    @Test
    fun `a non-positive or non-integer seed count parses to null`() {
        assertEquals(null, LaunchDirectives.from { if (it == "SNAPSYNC_SEED_PHOTOS") "0" else null }.seedPhotos)
        assertEquals(null, LaunchDirectives.from { if (it == "SNAPSYNC_SEED_POLICY") "-5" else null }.seedPolicy)
        assertEquals(null, LaunchDirectives.from { if (it == "SNAPSYNC_SEED_PHOTOS") "abc" else null }.seedPhotos)
    }

    @Test
    fun `an empty environment is NONE`() {
        assertEquals(LaunchDirectives.NONE, LaunchDirectives.from { null })
    }
}
