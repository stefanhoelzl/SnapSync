package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **A rejected credential still reaches the trust feature** (`docs/architecture.md`; the
 * recovery loop `privacy-security` and `docs/architecture.md` both rest on).
 *
 * THE LOOP. When any gated call answers `401`, the shared HTTP client's interceptor tells the trust
 * feature its credential was rejected; the feature drops the token and attests afresh; obtaining a new
 * one emits on `tokenChanged`, and the push feature re-sends the registration the `401` had lost. That
 * loop is why `PUT /devices/<id>` may answer `401` for a device the backend holds no attestation for
 * without any client change at all — the device recreates its own row and the registration lands on the
 * retry.
 *
 * WHY A TEXT PIN AND NOT A TEST. Every part of the loop is tested; the JOIN is not, and cannot be here.
 * The composed core's ports are built over the HTTP client, and the client reads its credential — and
 * reports its rejection — from the core: a construction cycle, broken by two lazy bindings. `:domain` is
 * platform-free and cannot build the Darwin client itself, so something outside the composition must hand
 * the core's callback to the client, and that something is the shell by definition. Shell source is
 * wiring-only and untested by law, and the world harness composes `snapSyncApp` rather than the root — so
 * it cannot reach this either.
 *
 * (The other half of the loop, `tokenChanged` → re-register, is NOT pinned here: it moved into
 * `compose/` as `AppCore.installPushRegistration`, and the integration surface's
 * `a_new_credential_re_registers_the_push_token_with_no_new_delivery` drives it over the world for real.)
 *
 * WHAT THIS PROVES, exactly: that the route is still CONNECTED — not that it works. That is the failure
 * mode worth catching, because the call site is one argument whose purpose is not legible where it sits
 * (the core's `backendVerdicts`, which carries the rejection route and the version gate's writes), and
 * deleting it leaves every test in the repository green.
 */
class CredentialRejectionWiringTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: fail("could not locate the repository root")

    private val root = File(
        repoRoot,
        "app/ios/src/iosMain/kotlin/app/snapsync/ios/SnapSyncRoot.kt",
    )

    /** Comments are where this loop is EXPLAINED, so a match inside one would pin the prose, not the wiring. */
    private fun code(): String = root.readText()
        .lines()
        .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
        .joinToString("\n")

    @Test
    fun `the pinned shell source exists (non-vacuity floor)`() {
        // A guard that scans a renamed-away file passes by finding nothing. This fails first instead.
        assertTrue(
            root.isFile,
            "the pinned shell source ${root.path} is gone — this guard would otherwise pass vacuously",
        )
        assertTrue(code().isNotBlank(), "${root.name} contributed no code lines to scan")
    }

    @Test
    fun `the shared HTTP client is built with a rejection hook`() {
        assertTrue(
            Regex("""darwinHttpClient\(""").containsMatchIn(code()),
            "the root no longer builds the shared Darwin client — every backend call's 401 handling " +
                "hangs off this one construction",
        )
        assertTrue(
            Regex("""verdicts\s*=""").containsMatchIn(code()),
            "the shared HTTP client is built without the core's `verdicts`, so a 401 reaches nothing: " +
                "a rejected credential would be re-sent forever and no wake could heal it",
        )
    }

    @Test
    fun `the rejection hook reaches the core's rejection route`() {
        // The compare-and-clear and the refresh it triggers are the core's (`compose/CredentialComposition.kt`,
        // decision record `harden-seam-bug-classes`), reached through the verdicts object the core exposes
        // (`AppCore.backendVerdicts`), and the integration surface drives them over the world; what only this pin
        // can see is that the shell still hands the client THAT object.
        assertTrue(
            Regex("""verdicts\s*=\s*\{\s*app\.backendVerdicts\s*\}""").containsMatchIn(code()),
            "the shared HTTP client no longer reports to `app.backendVerdicts`, so a rejected-but-unexpired token is " +
                "never dropped — the expiry check cannot see a rejection, and the device would 401 forever behind a " +
                "screen reading \"Syncing\"",
        )
    }
}
