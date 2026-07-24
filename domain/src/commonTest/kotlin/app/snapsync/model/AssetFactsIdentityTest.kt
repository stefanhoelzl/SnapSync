package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The **id form** the two set-matching selection rules depend on (capability `photo-selection-policy`).
 *
 * This is the one invariant that made *both* id-matching rules silently inert on device.
 * [SelectionRule.NotEcho] matches the download importer's stored `createdLocalId` and
 * [SelectionRule.NotInDenylistedAlbum] matches the album manager's listing; both of those normalize
 * (`normalizeAssetId`), while the PhotoKit facts reader passed a raw `localIdentifier`. A raw id is in
 * neither set, so both rules admitted everything — the device re-uploaded photos it had **downloaded from
 * another event**, and the WhatsApp/Telegram denylist did nothing. Nothing raised, nothing logged, and
 * the status screen read "In sync" throughout.
 *
 * Normalizing inside [AssetFacts] is what makes the mismatch unrepresentable rather than documented, and
 * these run on **both** JVM and the simulator — the reader that got it wrong is `iosMain`, whose own test
 * only runs on macOS.
 */
class AssetFactsIdentityTest {

    private val date = CaptureDate("2026-07-21T07:46:58Z")

    @Test
    fun `a raw PhotoKit localIdentifier is normalized on the way in`() {
        assertEquals(NORMALIZED, AssetFacts(RAW, date).assetId)
    }

    @Test
    fun `an already-normalized id is unchanged`() {
        // Idempotence is what lets this sit in the constructor without any producer having to know.
        assertEquals(NORMALIZED, AssetFacts(NORMALIZED, date).assetId)
    }

    @Test
    fun `the echo rule suppresses a walked asset against the importer ids`() {
        // The device failure, at the seam where the two forms meet: the suppression set is what the
        // importer wrote, the facts are what the walk produced.
        assertFalse(
            SelectionRule.NotEcho(setOf(NORMALIZED)).admits(AssetFacts(RAW, date)),
            "a photo this device downloaded must never be re-uploaded",
        )
    }

    @Test
    fun `the album denylist excludes a walked asset against the album manager ids`() {
        assertFalse(SelectionRule.NotInDenylistedAlbum(setOf(NORMALIZED)).admits(AssetFacts(RAW, date)))
    }

    private companion object {
        /** The exact shape PhotoKit hands back. */
        const val RAW = "5C33E0C1-4E39-4EE0-891F-BAFB943BC168/L0/001"
        const val NORMALIZED = "5C33E0C1-4E39-4EE0-891F-BAFB943BC168_L0_001"
    }
}
