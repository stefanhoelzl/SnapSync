package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The upload-mechanism resolver's **cells**, exhaustively (capability `upload-lifecycle`, "The upload
 * mechanism is resolved, never selected").
 *
 * This file is the successor to `CompositionModeTest`, and the reason it is bigger than the resolver it
 * replaces is that the resolver gained the two inputs that make the mechanism a runtime fact rather than
 * a device fact. It is exhaustive rather than example-based because the risk moved when the
 * exactly-one-started invariant went back to being structural: a wrong cell no longer starts two
 * producers, it hands back a mechanism whose registration selector does not exist on the running OS,
 * which traps and aborts the process. That failure has no other guard, so the table is walked in full.
 */
class UploadMechanismTest {

    private val permissions = PermissionStatus.entries
    private val overrides: List<UploadMechanism?> = listOf(null) + UploadMechanism.entries
    private val osFacts = listOf(true, false)

    /** Every (os × permission × override) cell, which is the whole input space. */
    private fun cells(): List<Triple<Boolean, PermissionStatus, UploadMechanism?>> =
        buildList {
            for (os in osFacts) for (p in permissions) for (o in overrides) add(Triple(os, p, o))
        }

    @Test
    fun `no cell yields a mechanism the OS cannot run`() {
        // THE safety property. Below iOS 26.1 `setUploadJobExtensionEnabled` does not exist, so a cell
        // yielding PHOTOKIT there is not a wrong choice — it is an unrecognized selector and a dead process.
        for ((os, permission, override) in cells()) {
            val resolved = resolveUploadMechanism(os, permission, override)
            if (!os) {
                assertNotEquals(
                    UploadMechanism.PHOTOKIT,
                    resolved,
                    "os-driven mechanism resolved on an OS without it: permission=$permission override=$override",
                )
            }
        }
    }

    @Test
    fun `resolution is total`() {
        // No empty cells: every input yields a mechanism, so a trigger always has somewhere to go and an
        // OS completion handler is never stranded (`upload-lifecycle`, "A mechanism is always resolved").
        assertEquals(2 * PermissionStatus.entries.size * (UploadMechanism.entries.size + 1), cells().size)
        for ((os, permission, override) in cells()) {
            // The call itself is the assertion: a non-null return for every combination.
            resolveUploadMechanism(os, permission, override)
        }
    }

    @Test
    fun `unusable access resolves idle under every OS and every override`() {
        for (permission in listOf(PermissionStatus.NOT_DETERMINED, PermissionStatus.DENIED)) {
            for (os in osFacts) for (override in overrides) {
                assertEquals(
                    UploadMechanism.IDLE,
                    resolveUploadMechanism(os, permission, override),
                    "os=$os permission=$permission override=$override",
                )
            }
        }
    }

    @Test
    fun `the device table without an override`() {
        // The four cells a shipped build can ever be in, since production supplies no override.
        assertEquals(UploadMechanism.PHOTOKIT, resolveUploadMechanism(true, PermissionStatus.GRANTED))
        // A partial grant runs the app-driven mechanism on the SAME OS: the OS never invokes the
        // extension under `.limited` (measured — `ios-photokit-upload`).
        assertEquals(UploadMechanism.URL_SESSION, resolveUploadMechanism(true, PermissionStatus.LIMITED))
        assertEquals(UploadMechanism.URL_SESSION, resolveUploadMechanism(false, PermissionStatus.GRANTED))
        assertEquals(UploadMechanism.URL_SESSION, resolveUploadMechanism(false, PermissionStatus.LIMITED))
    }

    @Test
    fun `an override pins either real mechanism where the OS can run it`() {
        // The restored tier force. Pinning the app-driven mechanism under a full grant on a >=26.1 device
        // is what the deleted SNAPSYNC_FORCE_URLSESSION_UPLOAD used to do.
        assertEquals(
            UploadMechanism.URL_SESSION,
            resolveUploadMechanism(true, PermissionStatus.GRANTED, UploadMechanism.URL_SESSION),
        )
        // And the other direction, which a Boolean could not express: pinning the OS-driven mechanism so a
        // run does not depend on what the host would have resolved by itself.
        assertEquals(
            UploadMechanism.PHOTOKIT,
            resolveUploadMechanism(true, PermissionStatus.LIMITED, UploadMechanism.PHOTOKIT),
        )
    }

    @Test
    fun `an override for an absent mechanism is ignored rather than obeyed`() {
        // Ignored rather than honoured, because honouring it would trap. Falls back to what the device
        // resolves, which is the only runnable answer there.
        for (permission in listOf(PermissionStatus.GRANTED, PermissionStatus.LIMITED)) {
            assertEquals(
                UploadMechanism.URL_SESSION,
                resolveUploadMechanism(false, permission, UploadMechanism.PHOTOKIT),
                "permission=$permission",
            )
        }
    }

    @Test
    fun `an absent override resolves exactly as the device would`() {
        // Absence has one meaning — "nothing pinned a mechanism" — and it is the production case.
        for (os in osFacts) for (permission in permissions) {
            assertEquals(
                resolveUploadMechanism(os, permission),
                resolveUploadMechanism(os, permission, override = null),
                "os=$os permission=$permission",
            )
        }
    }

    @Test
    fun `resolution is pure`() {
        // Same inputs, same answer, every time: no ambient state, no environment read, no clock. Stated
        // because the resolver this one absorbed once read parsed launch directives, and re-resolution
        // makes purity load-bearing in a way a once-per-process call did not.
        for ((os, permission, override) in cells()) {
            val first = resolveUploadMechanism(os, permission, override)
            repeat(3) { assertEquals(first, resolveUploadMechanism(os, permission, override)) }
        }
    }

    @Test
    fun `idle is reachable only through unusable access or an explicit pin`() {
        // Guards against IDLE becoming a silent catch-all: with usable access and no override, a real
        // mechanism always runs.
        for (os in osFacts) for (permission in listOf(PermissionStatus.GRANTED, PermissionStatus.LIMITED)) {
            assertTrue(
                resolveUploadMechanism(os, permission) != UploadMechanism.IDLE,
                "usable access resolved IDLE: os=$os permission=$permission",
            )
        }
    }
}
