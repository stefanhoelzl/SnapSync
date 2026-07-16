package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **The event link's domain agrees everywhere it appears** (capability `architecture-guards`).
 *
 * The domain lives in four places that no compiler and no module boundary can hold together:
 *
 * 1. `gradle.properties` → `snapsync.domain`, which generates the app's `LINK_ORIGIN` (both halves of
 *    the codec are anchored to it),
 * 2. `Config.xcconfig` → `ASSOCIATED_DOMAIN`, which fills the app's `applinks:` entitlement,
 * 3. `iosApp.entitlements`, which must actually reference that setting rather than hard-code a host,
 * 4. `backend/src/config.ts` → `LINK_DOMAIN`, the host the AASA is served for.
 *
 * (1)–(3) are single-sourced from one Gradle property, so they cannot drift by construction. (4) is the
 * seam this guard exists for: `backend/` is a Deno tree deployed by a separate, path-scoped workflow that
 * ships **code only, never config** — bunny issues no scoped API key — so nothing in the Gradle build can
 * reach it, and generating it would couple two deliberately independent pipelines.
 *
 * Why a test rather than review: drift here **raises nothing**. A stale entitlement or a mismatched AASA
 * does not fail a build, log a warning, or throw at runtime — iOS simply declines to match the link, and
 * every invite quietly opens a browser instead of the app. From the outside that is indistinguishable
 * from a recipient who never installed SnapSync, which is exactly the failure this whole capability
 * exists to remove.
 */
class EventLinkDomainTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: fail("could not locate the repository root")

    private fun read(relative: String): String {
        val file = File(repoRoot, relative)
        assertTrue(file.isFile, "guard is scanning nothing — $relative not found from $repoRoot")
        return file.readText()
    }

    /** The one source: the Gradle property every app-side copy is generated from. */
    private val domain: String = Regex("""^snapsync\.domain=(.+)$""", RegexOption.MULTILINE)
        .find(read("gradle.properties"))?.groupValues?.get(1)?.trim()
        ?: fail("gradle.properties declares no `snapsync.domain` — the event link has no source of truth")

    @Test
    fun `the backend serves the AASA for the same domain the app claims`() {
        val backend = read("backend/src/config.ts")
        val backendDomain = Regex("""const LINK_DOMAIN = "([^"]+)"""").find(backend)?.groupValues?.get(1)
            ?: fail("backend/src/config.ts declares no `LINK_DOMAIN` — the AASA has no domain to serve for")
        assertEquals(
            domain,
            backendDomain,
            "the event-link domain has drifted: gradle.properties says `$domain` but " +
                "backend/src/config.ts says `$backendDomain`. Nothing will raise — iOS will simply stop " +
                "matching the link and every invite will open a browser instead of the app. Gradle " +
                "cannot reach backend/ (it ships code, never config), which is why this guard exists.",
        )
    }

    @Test
    fun `the associated-domain build setting names the same domain`() {
        val xcconfig = read("iosApp/Configuration/Config.xcconfig")
        val value = Regex("""^ASSOCIATED_DOMAIN = (.+)$""", RegexOption.MULTILINE)
            .find(xcconfig)?.groupValues?.get(1)?.trim()
            ?: fail("Config.xcconfig declares no `ASSOCIATED_DOMAIN` — the app claims no associated domain")
        assertEquals(
            "applinks:$domain",
            value,
            "Config.xcconfig's ASSOCIATED_DOMAIN does not match `snapsync.domain`. The app would " +
                "claim a domain it does not serve links for — silently.",
        )
    }

    @Test
    fun `the app entitlement takes its domain from the build setting, not a literal`() {
        val entitlements = read("iosApp/iosApp/iosApp.entitlements")
        assertTrue(
            entitlements.contains("com.apple.developer.associated-domains"),
            "iosApp.entitlements declares no associated-domains entitlement — the app cannot claim the " +
                "event link at all, and every invite opens a browser.",
        )
        assertTrue(
            entitlements.contains("\$(ASSOCIATED_DOMAIN)"),
            "iosApp.entitlements hard-codes its associated domain instead of taking " +
                "\$(ASSOCIATED_DOMAIN) from Config.xcconfig, re-opening the drift this guard closes.",
        )
    }

    @Test
    fun `the extension claims no associated domain`() {
        val extension = read("iosApp/BackgroundUploadExtension/BackgroundUploadExtension.entitlements")
        assertTrue(
            !extension.contains("com.apple.developer.associated-domains"),
            "the background-upload extension declares an associated domain. It never handles URLs; " +
                "claiming one only widens what must be provisioned and kept in agreement.",
        )
    }

    @Test
    fun `the custom URL scheme stays retired`() {
        val infoPlist = read("iosApp/iosApp/Info.plist")
        // Match the plist KEY, not the word: the file names `CFBundleURLTypes` in a comment explaining
        // why it is absent, and a bare substring check cannot tell prose from a declaration.
        assertTrue(
            !infoPlist.contains("<key>CFBundleURLTypes</key>"),
            "Info.plist re-registers a custom URL scheme. The `snapsync://` scheme is retired " +
                "(capability `event-link`): the one authoritative codec no longer accepts it, so a " +
                "scheme registered here would route links the app cannot decode.",
        )
    }

    /** Fail loudly rather than vacuously: if the files moved, this guard is inspecting nothing. */
    @Test
    fun `the guard actually found the files it inspects`() {
        assertTrue(domain.isNotBlank(), "resolved an empty event-link domain — the guard proves nothing")
        assertTrue(read("backend/src/config.ts").contains("LINK_DOMAIN"))
        assertTrue(read("iosApp/Configuration/Config.xcconfig").contains("ASSOCIATED_DOMAIN"))
        assertTrue(read("iosApp/iosApp/iosApp.entitlements").contains("application-groups"))
        assertTrue(read("iosApp/iosApp/Info.plist").contains("APNS_ENV"))
    }
}
