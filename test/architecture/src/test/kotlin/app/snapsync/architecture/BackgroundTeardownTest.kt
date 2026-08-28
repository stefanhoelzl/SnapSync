package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **No background trigger flow can tear a membership down** (capability `architecture-guards`; laws:
 * `leave-event`, `module-architecture`).
 *
 * A membership is destroyed without user action only on a **confirmed absence** (capability
 * `leave-event`): the backend reports the event definitively gone AND the device's own persisted
 * deadline has passed. That teardown is reachable from the **foreground** trigger and from nothing else,
 * and the reason is not stylistic:
 *
 * - a background wake can land **before the first unlock**, where the config file is unreadable and the
 *   adapters surface that as *absent* — acting on absence there would destroy a perfectly healthy
 *   membership, and `EventConfig` is the ONLY record of the join (the invite QR is derived from its
 *   `eventId`, so there is nothing in the app to surface either back);
 * - `SilentPush` and `DownloadBackstop` both state the invariant in prose — *"nothing mints, clears, or
 *   leaves"* — and prose is not enforcement.
 *
 * The guard is therefore **textual and blunt on purpose**: a background flow may not name the teardown
 * (`LeaveEvent`) nor the rule that performs it (`MembershipRefresh`), whatever the surrounding
 * conditions look like. A future edit that reaches either from a background wake is a red build, not a
 * review note.
 *
 * `Foreground` is deliberately NOT listed: it re-reads the persisted membership from an unlocked device
 * before any consumer runs, which is the one context where acting on a confirmed absence is safe.
 */
class BackgroundTeardownTest {

    /** The OS-callback trigger flows that run on a **background** wake, with no user and no guaranteed unlock. */
    private val backgroundFlows = listOf("SilentPush.kt", "DownloadBackstop.kt", "Background.kt")

    /** Symbols that can end a membership. Naming either from a background flow is the violation. */
    private val teardownSymbols = listOf("LeaveEvent", "MembershipRefresh")

    @Test
    fun `background trigger flows never reference the membership teardown`() {
        val flowDir = File(ZoneGates.domainSrc, "flow/src/commonMain/kotlin/app/snapsync/flow")
        if (!flowDir.isDirectory) return // zone not present — the zone gates own that case
        val present = backgroundFlows.mapNotNull { name ->
            File(flowDir, name).takeIf { it.isFile }
        }
        assertTrue(present.isNotEmpty(), "no background trigger flow found under $flowDir — has one been renamed?")

        val violations = present.flatMap { file ->
            file.readLines().withIndex().flatMap { (i, line) ->
                val code = line.substringBefore("//")
                teardownSymbols.filter { code.contains(it) }.map { symbol ->
                    "${file.name}:${i + 1} references `$symbol` — a background wake may run pre-first-unlock, " +
                        "where an unreadable config reads as absent; the teardown belongs to the foreground only " +
                        "(capability `leave-event`)"
                }
            }
        }
        ZoneGates.assertNoViolations("background-teardown", violations)
    }
}
