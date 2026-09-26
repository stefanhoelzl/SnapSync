package app.snapsync.world

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The extension's in-memory device token is re-read at every OS invocation (capability `privacy-security`).
 *
 * The app renews into the Keychain item both processes share, and the extension's copy cannot see that; the
 * re-read at each `process()` is what bounds the copy's staleness to one invocation. Asserted over the real
 * handlers the extension root registers on its entry port, including an invocation whose cycle does
 * nothing (no membership) — the re-read must not depend on there being work.
 */
class ExtensionCredentialRereadTest {

    @Test
    fun every_invocation_rereads_the_credential() = worldTest {
        val w = World(this)
        var rereads = 0
        val extension = w.composeExtension(rereadCredential = { rereads++ })

        extension.process()
        assertEquals(1, rereads, "an invocation with nothing to do still re-reads")

        w.provision("E")
        w.addOwnAsset("A")
        extension.process()
        extension.process()
        assertEquals(3, rereads, "once per invocation — never cached across them")
    }
}
