package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **No background entry point can tear a membership down** (`docs/architecture.md`; laws:
 * `manage-membership`, `docs/architecture.md`).
 *
 * A membership is destroyed without user action only on a **confirmed absence** (capability
 * `manage-membership`): the backend reports the event definitively gone AND the device's own persisted
 * deadline has passed. That teardown is reachable from the **foreground** trigger and from nothing else,
 * and the reason is not stylistic:
 *
 * - a background wake can land **before the first unlock**, where the config file is unreadable and the
 *   adapters surface that as *absent* — acting on absence there would destroy a perfectly healthy
 *   membership, and `EventConfig` is the ONLY record of the join (the invite QR is derived from its
 *   `eventId`, so there is nothing in the app to surface either back);
 * - the silent push states the invariant in prose — *"nothing mints, clears, or
 *   leaves"* — and prose is not enforcement.
 *
 * The guard is therefore **textual and blunt on purpose**: a background entry point may not name the teardown
 * (`LeaveEvent`) nor the rule that performs it (`MembershipRefresh`), whatever the surrounding
 * conditions look like. A future edit that reaches either from a background wake is a red build, not a
 * review note.
 *
 * `OnForeground` is deliberately NOT listed: it re-reads the persisted membership from an unlocked device
 * before any consumer runs, which is the one context where acting on a confirmed absence is safe.
 */
class BackgroundTeardownTest {

    /** The entry points that run on a **background** wake, with no user and no guaranteed unlock. */
    private val backgroundEntryPoints = listOf(
        "OnSilentPush",
        "OnBackground",
        "OnScheduledWake",
        "OnUploadSessionEvents",
        "OnDownloadSessionEvents",
    )

    /** Symbols that can end a membership. Naming either from a background entry point is the violation. */
    private val teardownSymbols = listOf("LeaveEvent", "MembershipRefresh")

    @Test
    fun `background entry points never reference the membership teardown`() {
        val file =
            File(ZoneGates.domainSrc, "entrypoints/src/commonMain/kotlin/app/snapsync/entrypoints/AppEntryPoints.kt")
        assertTrue(file.isFile, "the app's entry points moved from $file — re-point this guard")
        val text = file.readText()
        val violations = backgroundEntryPoints.flatMap { name ->
            val start = text.indexOf("class $name(")
            assertTrue(start >= 0, "no background entry point `$name` in ${file.name} — has one been renamed?")
            val end = Regex("""\n(?:class |/\*\*|// ──)""").find(text, start + 1)?.range?.first ?: text.length
            text.substring(start, end).lines().flatMap { line ->
                val code = line.substringBefore("//")
                teardownSymbols.filter { code.contains(it) }.map { symbol ->
                    "$name references `$symbol` — a background wake may run pre-first-unlock, where an unreadable " +
                        "config reads as absent; the teardown belongs to the foreground only (capability " +
                        "`manage-membership`)"
                }
            }
        }
        ZoneGates.assertNoViolations("background-teardown", violations)
    }
}
