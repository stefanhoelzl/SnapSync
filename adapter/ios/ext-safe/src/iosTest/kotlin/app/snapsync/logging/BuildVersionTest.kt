package app.snapsync.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The boot banner's version string (capability `diagnostic-logging`, D5).
 *
 * The banner is the first line of every device log and the thing an operator reads to answer "which
 * build produced this?" — a question every other line's meaning depends on. The decision it embeds is
 * what an **absent** `Info.plist` key becomes, and the wrong answer there is not a crash but a
 * plausible-looking lie: Kotlin's default rendering of a null is the string `null`, so a banner would
 * read `null(null)` — which looks like a key that was read and found empty, rather than one that was
 * never there. `?` says the second thing, and the two lead a reader to different places.
 *
 * The process's own `NSBundle` cannot be substituted, so the formatting is asserted directly and the
 * live call is checked for shape.
 */
class BuildVersionTest {

    @Test
    fun `an absent key prints a question mark and never the string null`() {
        assertEquals("?(?)", formatBuildVersion(short = null, build = null))
        assertEquals("0.2(?)", formatBuildVersion(short = "0.2", build = null))
        assertEquals("?(512)", formatBuildVersion(short = null, build = "512"))
    }

    @Test
    fun `a fully-configured build reads as short version then build number`() {
        assertEquals("0.2(512)", formatBuildVersion(short = "0.2", build = "512"))
    }

    /**
     * The live read, which must at least never produce the word `null` — the failure this format
     * exists to avoid, in the one place it could still be reintroduced (the two `as? String` casts).
     */
    @Test
    fun `the running process reports a version in the banner's shape`() {
        val version = appBuildVersion()

        assertTrue(Regex("""^[^(]+\(.+\)$""").matches(version), "unexpected banner version: $version")
        assertTrue("null" !in version, "an absent key must read as ? rather than null: $version")
    }
}
