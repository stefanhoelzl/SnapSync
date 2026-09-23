package app.snapsync.world

import app.snapsync.compose.extensionEntries
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The extension's in-memory device token is re-read at every OS invocation (capability `device-attestation`).
 *
 * The app renews into the Keychain item both processes share, and the extension's copy cannot see that; the
 * re-read at each `process()` is what bounds the copy's staleness to one invocation. Asserted over the real
 * inbound-port implementation the extension root delegates to, including an invocation whose cycle does
 * nothing (no membership) — the re-read must not depend on there being work.
 */
class ExtensionCredentialRereadTest {

    @Test
    fun every_invocation_rereads_the_credential() = worldTest {
        val w = World(this)
        var rereads = 0
        val entries = extensionEntries(ports = { w.uploadPorts }, cycle = { w.cycle }, rereadCredential = { rereads++ })

        entries.process()
        assertEquals(1, rereads, "an invocation with nothing to do still re-reads")

        w.provision("E")
        w.addOwnAsset("A")
        entries.process()
        entries.process()
        assertEquals(3, rereads, "once per invocation — never cached across them")
    }
}
