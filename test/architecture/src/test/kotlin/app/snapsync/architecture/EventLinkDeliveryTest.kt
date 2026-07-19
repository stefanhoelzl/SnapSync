package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **The Swift shell keeps the event link's delivery seam** (capability `architecture-guards`).
 *
 * The first guard over Swift, and it exists because of what happened without one. On 2026-07-16 the
 * app shipped receiving event links via SwiftUI's `.onOpenURL` — which **never fires for a Universal
 * Link** — so every invite silently did nothing while every automated check stayed green: the decoder
 * was tested on two targets, Apple's CDN had fetched and approved the AASA, the entitlement was verified
 * in the installed binary, and a guard held the link domain across four files. The one seam none of that
 * covers is `iosApp/`, which the project declares wiring-only and untested. `:test:architecture` read
 * Kotlin, entitlements, xcconfig, plists, even `backend/src/config.ts` — and no Swift at all. The one
 * file that broke was the one file no guard inspected.
 *
 * What iOS actually does (Apple, *Supporting universal links in your app*): a Universal Link arrives as
 * an `NSUserActivity`, and **because a SwiftUI `WindowGroup` is a scene**, it is delivered to the SCENE
 * delegate — `scene(_:willConnectTo:options:)` when the app was **not running**, `scene(_:continue:)`
 * when it was. Both halves are required. Cold is the half that matters: a recipient tapping an invite
 * for the first time never has the app running, and bootstrapping that recipient is the entire point of
 * the event link.
 *
 * This is a **regression guard, not a discovery guard**. It could not have caught the original — nobody
 * knew `willConnectTo` was the answer until it was measured on a device. It catches the realistic
 * future: someone sees a UIKit scene delegate in a SwiftUI app, concludes `.onOpenURL` supersedes it,
 * deletes it, and every invite dies **silently** with CI green. That is the same species as
 * [DataProtectionEntitlementTest] — a small edit that reads as an improvement and disables a whole
 * feature invisibly.
 *
 * Because the failure is invisible, the **messages below are the point**, more than the assertions: they
 * are the only thing standing between the next reader and re-introducing the bug.
 */
class EventLinkDeliveryTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: fail("could not locate the repository root")

    private val shellPath = "iosApp/iosApp/iOSApp.swift"

    private val rawShell: String by lazy {
        val file = File(repoRoot, shellPath)
        assertTrue(file.isFile, "guard is scanning nothing — $shellPath not found from $repoRoot")
        file.readText()
    }

    /**
     * The shell with **full-line comments stripped**, so the guard matches CODE and not prose.
     *
     * This is load-bearing, not fastidiousness. The delegate below is documented at length — the comment
     * naming `scene(_:willConnectTo:options:)` and `.onOpenURL` is deliberately the most detailed in the
     * file, because it is what a future "simplification" must get past. A guard matching raw text is
     * therefore satisfied **by that very comment**: delete the whole delegate and a naive
     * `contains("willConnectTo")` still passes, forever. That was not hypothetical — this guard was
     * written that way first, and the drift check caught it.
     *
     * Only whole-line comments are removed. Trailing `//` cannot be cut naively: the file contains URLs
     * (`https://…`), and splitting on `//` would truncate real code.
     */
    private val code: String by lazy {
        rawShell.lineSequence()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
    }

    /** The evidence every failure message carries: the alternatives are not alternatives. */
    private val whyItMatters = """
        |
        |Universal links are delivered as an NSUserActivity to the SCENE delegate, because a SwiftUI
        |WindowGroup is a scene (Apple: "Supporting universal links in your app"). Each of these was
        |measured on a device on 2026-07-16 and is NOT sufficient:
        |  * .onOpenURL                                  — the application(_:open:options:) path a custom
        |                                                  scheme uses. NEVER fires for a universal link.
        |                                                  THIS SHIPPED, and every invite silently died.
        |  * .onContinueUserActivity                     — WARM delivery only; on a cold launch the
        |                                                  activity arrives before the view attaches.
        |  * application(_:continue:restorationHandler:) — never called at all: a SwiftUI app gets only
        |                                                  didFinishLaunching + willTerminate.
        |The failure is SILENT and looks like success: iOS still matches the AASA and still foregrounds
        |the app, so the link "works" — it just drops the URL. On an unjoined device the create screen it
        |lands on is the correct resting state, so nothing looks wrong. No test can catch it downstream:
        |the decoder, AASA, and entitlement are all fine. Only this structure keeps it alive.
    """.trimMargin()

    @Test
    fun `the shell installs a scene delegate`() {
        assertTrue(
            code.contains("configurationForConnecting") && code.contains("delegateClass"),
            "$shellPath no longer installs a scene delegate via " +
                "application(_:configurationForConnecting:options:) setting `delegateClass`. Without " +
                "that, any scene delegate present is INERT and never receives anything.$whyItMatters",
        )
    }

    @Test
    fun `the scene delegate handles the COLD half`() {
        assertTrue(
            Regex("""func\s+scene\s*\([^)]*willConnectTo""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(code),
            "$shellPath no longer implements scene(_:willConnectTo:options:) — the COLD half, where the " +
                "link that LAUNCHED the app arrives in connectionOptions.userActivities. This is the " +
                "half that matters: a stranger tapping an invite never has the app running, so without " +
                "it the bootstrap this whole capability exists for is dead.$whyItMatters",
        )
    }

    @Test
    fun `the scene delegate handles the WARM half`() {
        assertTrue(
            Regex("""func\s+scene\s*\(\s*_\s+\w+\s*:\s*UIScene\s*,\s*continue\s""").containsMatchIn(code),
            "$shellPath no longer implements scene(_:continue:) — the WARM half, for a link opened " +
                "while the app is running or suspended.$whyItMatters",
        )
    }

    @Test
    fun `the scene delegate forwards to the Kotlin entry point`() {
        assertTrue(
            code.contains("UIWindowSceneDelegate"),
            "$shellPath declares no UIWindowSceneDelegate.$whyItMatters",
        )
        // The hooks must be wired to Kotlin, not merely present. Since migration step 12 Swift is a
        // TRANSCRIBER here: it forwards every delivered NSUserActivity WHOLE to
        // SnapSyncRoot.onUserActivity — the browsing-web filter and the raw-absoluteString read
        // (fragment included; the fragment IS the payload) are Kotlin's tested `model/` codec
        // (`eventLinkFromUserActivity`), routed on to `onOpenUrl` in Kotlin. A Swift-side field read
        // would be an unpinned decision (SwiftShellGuardTest).
        assertTrue(
            code.contains("SnapSyncRoot.shared.onUserActivity"),
            "$shellPath's scene delegate does not forward the delivered NSUserActivity whole to " +
                "SnapSyncRoot.shared.onUserActivity. Extracting fields in Swift is an untested " +
                "decision; trimming the URL drops the fragment — and the fragment IS the payload, " +
                "so the event id would vanish.$whyItMatters",
        )
    }

    /** Fail loudly rather than vacuously: if the shell moved, this guard is inspecting nothing. */
    @Test
    fun `the guard actually found the swift shell it claims to guard`() {
        assertTrue(code.isNotBlank(), "read an empty $shellPath — the guard proves nothing")
        assertTrue(
            code.contains("@main") && code.contains("UIApplicationDelegateAdaptor"),
            "$shellPath does not look like the SwiftUI app shell this guard expects (no @main / " +
                "@UIApplicationDelegateAdaptor). It may have moved — fix the guard rather than deleting " +
                "it, or the delivery seam goes unguarded again.",
        )
    }
}
