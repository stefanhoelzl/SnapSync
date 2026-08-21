package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The composition resolver, which is now one fact in and one tier out.
 *
 * This file used to be much larger, and most of what it held was **precedence**: forge over an event link
 * (the shipped forge×link bug, turned into a resolver test rather than a shell guard), forge over the
 * membership triggers, an unrecognized forge state falling through to the live stack, and the tier-force
 * flag overriding the OS. All of those inputs are gone — forge is its own binary target and the launch
 * triggers are the control channel's surface — so there is no precedence left to test. What remains is the
 * mapping itself, and the property that makes the mapping worth stating: **nothing else can influence it.**
 *
 * That property is not testable from inside this function, because it is a claim about the function's
 * *signature*. It is held instead by `RunbookSkillsTest`'s assertion that production Kotlin declares no
 * launch trigger at all — if a developer input ever returns, that guard fails before it could reach here.
 */
class CompositionModeTest {

    @Test
    fun `an OS with the background-upload API resolves to the OS-driven tier`() {
        assertEquals(UploadTier.PHOTOKIT, resolveComposition(backgroundUploadSupported = true))
    }

    @Test
    fun `an OS without it resolves to the app-driven tier`() {
        // iOS 18–26.0. This arm is NOT dev equipment and did not go with the tier-force flag: it is the
        // real tier for every device below 26.1, and deleting it with the flag would have broken them all.
        assertEquals(UploadTier.URL_SESSION, resolveComposition(backgroundUploadSupported = false))
    }

    @Test
    fun `the tier is a pure function of the one OS fact`() {
        // Same input, same answer, every time — no ambient state, no environment read, no clock. Stated as
        // a test because the previous resolver DID read ambient state (the parsed launch directives), and
        // the whole point of the reduction is that it no longer can.
        repeat(3) {
            assertEquals(UploadTier.PHOTOKIT, resolveComposition(backgroundUploadSupported = true))
            assertEquals(UploadTier.URL_SESSION, resolveComposition(backgroundUploadSupported = false))
        }
    }

    @Test
    fun `each tier names itself for a diagnostic dump`() {
        assertEquals("photokit", UploadTier.PHOTOKIT.diagnosticName)
        assertEquals("url_session", UploadTier.URL_SESSION.diagnosticName)
    }
}
