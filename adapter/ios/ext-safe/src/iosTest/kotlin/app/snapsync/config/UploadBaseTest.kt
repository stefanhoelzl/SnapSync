package app.snapsync.config

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The baked backend base (capability `ios-app-shell`).
 *
 * The host is compile-time because PhotoKit validates every upload job's destination against the
 * extension's baked value, so a user-configurable host is impossible by design. What is left to
 * decide is what an **absent** `BackgroundUploadURLBase` becomes — and a test binary carries no such
 * key, which makes this the one place that branch is reachable.
 *
 * Blank is the answer, and it is load-bearing: `buildUploadConfig` treats a blank host exactly as it
 * treats an absent one, so a misconfigured build uploads **nowhere**. The alternatives are both
 * worse. A `null` would push the decision out to the two wiring-only composition roots, which are
 * gated to hold no decisions; and any non-blank placeholder — a default host, a `"?"` — would make a
 * misconfigured build attempt real requests against a host nobody chose.
 */
class UploadBaseTest {

    @Test
    fun `an absent Info key reads as blank rather than as a host nobody chose`() {
        assertEquals(
            "",
            bakedUploadBase(),
            "blank is what makes a misconfigured build upload nowhere instead of somewhere unintended",
        )
    }
}
