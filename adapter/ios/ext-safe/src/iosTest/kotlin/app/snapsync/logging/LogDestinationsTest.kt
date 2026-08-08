package app.snapsync.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Where each process puts its log (capability `diagnostic-logging`).
 *
 * A test binary is an unusually honest place to assert this: it has no `application-groups`
 * entitlement, so `appGroupDirectory()` answers `null` — which is exactly the condition the
 * extension's **fallback** exists for, and the only condition under which it can be exercised at all.
 * On a healthy device the fallback never runs, so without this it is code that has never executed
 * anywhere, guarding the failure mode ("no log at all, indistinguishable from a process that never
 * ran") that is hardest to diagnose after the fact.
 *
 * **Deliberately not covered here**: `removeStaleExtensionDocumentsLog` and the copying half of
 * `exportExtensionLogToDocuments`. Both *delete and overwrite* files under this process's
 * `Documents/`, and a bundle-less Kotlin/Native binary resolves that to the real home directory of
 * whoever is running the tests — a CI runner or a developer's Mac. A test that removes
 * `~/Documents/debug.log` to prove a removal works is not a test worth having. Their arguments are
 * pure branches on inputs this file does assert (`fellBackToDocuments`, `requested`), and the
 * behaviour itself is evidenced by the runbook's `SNAPSYNC_EXPORT_LOGS` pull.
 */
class LogDestinationsTest {

    /**
     * These two names are an interface with the operator runbook, not internal detail: every
     * `pymobiledevice3 apps pull app.snapsync Documents/<name>` in `CLAUDE.md` names them literally,
     * and a rename makes every one of those commands fail while the app looks perfectly healthy.
     */
    @Test
    fun `the log file names are the ones the runbook pulls`() {
        assertEquals("debug.log", APP_LOG_FILE_NAME)
        assertEquals("ext-debug.log", EXTENSION_LOG_FILE_NAME)
    }

    @Test
    fun `the app writes into its own Documents directory`() {
        val destination = appLogDestination()

        val path = assertNotNull(destination.path, "a process can always reach its own container")
        assertTrue(path.endsWith("/Documents/$APP_LOG_FILE_NAME"), "unexpected app destination: $path")
        assertFalse(destination.fellBackToDocuments, "the app's own Documents is its home, not a fallback")
    }

    /**
     * The branch a device never takes. With no App Group the extension must still write *somewhere*:
     * a writer resolving to nothing produces no log, which reads as "the extension never ran" — the
     * usual suspect it would be confused with.
     */
    @Test
    fun `the extension falls back to Documents when the App Group is unavailable`() {
        assertEquals(null, appGroupDirectory(), "a test binary holds no application-groups entitlement")

        val destination = extensionLogDestination()

        assertTrue(destination.fellBackToDocuments, "the fallback must be recorded as such")
        assertEquals(
            appLogDestination().path,
            destination.path,
            "the fallback is this process's own Documents log — the one place it can always write",
        )
    }

    /**
     * And it must be *said*. A fallback log is invisible to a diagnostic dump (the dump reads the App
     * Group file), so a reader who is not told will conclude the extension produced nothing.
     */
    @Test
    fun `the banner names the fallback rather than hiding it`() {
        val banner = LogDestination("/tmp/x/debug.log", fellBackToDocuments = true).bannerLine

        assertTrue("FALLBACK" in banner, "unexpected banner: $banner")
        assertTrue("/tmp/x/debug.log" in banner, "the banner must name the file that was chosen: $banner")
    }

    @Test
    fun `the banner says NONE when nothing writable resolved`() {
        val banner = LogDestination(null, fellBackToDocuments = false).bannerLine

        assertTrue("NONE" in banner, "unexpected banner: $banner")
    }

    @Test
    fun `an ordinary destination is announced without a warning`() {
        val banner = LogDestination("/tmp/x/ext-debug.log", fellBackToDocuments = false).bannerLine

        assertEquals("[boot] log destination = /tmp/x/ext-debug.log", banner)
    }

    /**
     * The export takes its trigger as a parameter rather than being called conditionally, so the shell
     * that invokes it holds no branch (`module-architecture`, "Shells are wiring only"). An
     * unrequested export must therefore write nothing at all.
     */
    @Test
    fun `an unrequested export writes nothing`() {
        assertEquals(emptyList(), exportExtensionLogToDocuments(requested = false))
    }

    /**
     * A requested export with no App Group container reports **nothing exported**, rather than a path
     * it did not write. The return value is the caller's boot line, and an export that claims a file
     * it never produced sends the reader to look for it over USB.
     */
    @Test
    fun `a requested export with no container reports nothing rather than claiming success`() {
        assertEquals(emptyList(), exportExtensionLogToDocuments(requested = true))
    }

    @Test
    fun `this process can resolve a Documents directory at all`() {
        val documents = assertNotNull(documentsDirectory(), "the whole fallback rests on this")
        assertTrue(documents.isNotBlank())
    }
}
