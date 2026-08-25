package app.snapsync.config

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The baked backend base (capability `ios-app-shell`).
 *
 * The host is compile-time because PhotoKit validates every upload job's destination against the
 * extension's baked value, so a user-configurable host is impossible by design. What is left to
 * decide is what an **absent** `uploadBase` becomes — and a test binary bundles no `Deployment.plist`,
 * which is what makes the branch reachable here.
 *
 * ⚠️ It is **no longer only** reachable here. While the value was an `Info.plist` substitution the
 * branch could not occur in a shipped build at all: the xcconfig `#include` resolved and the key was
 * present, or the build failed. Now the value rides in a generated resource that must be **copied into
 * each bundle**, and the app and the extension are separate bundles with separate resources phases — so
 * a shipped bundle missing the file reaches this branch, and reaches it for all four values at once:
 * uploads go nowhere, a Release build registers sandbox APNs, events are mislabelled, and no crash
 * reporting is left to say so. That is not defended here. It is defended by `ios.yml`'s archive check,
 * which reads the file back out of **both** bundles and fails the run — the only gate that can see it.
 *
 * Blank is still the answer, and it is still load-bearing: `buildUploadConfig` treats a blank host
 * exactly as it treats an absent one, so a misconfigured build uploads **nowhere**. The alternatives are
 * both worse. A `null` would push the decision out to the two wiring-only composition roots, which are
 * gated to hold no decisions; and any non-blank placeholder — a default host, a `"?"` — would make a
 * misconfigured build attempt real requests against a host nobody chose.
 */
class UploadBaseTest {

    @Test
    fun `an absent deployment value reads as blank rather than as a host nobody chose`() {
        assertEquals(
            "",
            bakedUploadBase(),
            "blank is what makes a misconfigured build upload nowhere instead of somewhere unintended",
        )
    }
}
