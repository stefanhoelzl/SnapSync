package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **The Kotlin shells hold zero unpinned decisions — and the pins are inventoried, exactly**
 * (capability `architecture-guards`, "The shell gates"; law: `module-architecture` "Shells are
 * wiring only"). Armed permanently at the migration finale, when the shells reached zero.
 *
 * The gate itself is `detektAppShell` (root build): `CyclomaticComplexMethod` at threshold 2 over
 * the production `:app:*` iOS sources, `ignoreFailures = false`, wired into `check` — a new branch
 * fails the canonical build. detekt honors `@Suppress`, so a suppression is the pin mechanism —
 * and an ungated suppression would be a silent hole exactly as wide as the gate. This guard closes
 * it: the suppression inventory is pinned by COUNT, per file, exact in both directions (a new pin
 * fails until it is argued into this table with a forcing proof at the site; a removed one fails
 * until the table shrinks), and the non-vacuity floor pins the scanned source set itself, so the
 * gate can never pass by scanning nothing (the `appShellSources` list going stale after a module
 * rename is precisely how the flip would have passed vacuously).
 *
 * The pinned sites (each carries its forcing proof as a comment at the suppression):
 *  - `SnapSyncRoot.kt` ×1 — the background-`URLSession` callback routing. UIKit delivers ONE app
 *    delegate callback for every session identifier, and this app owns two OS-reattached sessions.
 *    Expiry: dies with the 18–26.0 tier.
 *  - `MainViewController.kt` ×1 — the one switch on the resolved `SceneMode`, which decides whether a
 *    Compose scene is composed at all (capability `ios-app-shell`). The DECIDING is `resolveScene`, pure
 *    and `commonTest`-covered; the sealed type exists so a third mode fails the compile. Expiry: dies
 *    with the deferral, when CMP-5978 is fixed upstream and the mitigation can be deleted.
 *
 * This table held **eight** entries until the launch-trigger retirement, and six of them were one thing:
 * dev equipment sitting in a production, wiring-only module, each justified as "inert in production". The
 * seeder (×3), the wiper (×2) and the policy probe (×1) now live in `:test:rig`, which a production build
 * does not contain — so the suppressions are gone because the gate no longer scans that code, not because
 * the branches went away. That distinction is worth keeping in view: the decisions still exist, they are
 * simply no longer in the shipped shell, and they are no longer tested by anything either (a cost recorded
 * deliberately in `test/rig/build.gradle.kts`).
 */
class KotlinShellGuardTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: fail("could not locate the repository root")

    /**
     * MUST mirror the root build's `appShellSources` — the detekt gate's scanned roots.
     *
     * `test/rig/src/hook` lives in `:test:rig`'s tree but is compiled INTO `:app:ios` under
     * `-Psnapsync.rig=true`, so it is shell source and is gated as such rather than exempted
     * (capability `architecture-guards`, "Source contributed into a shell's source set is shell
     * source for the gates").
     *
     * `app/ios/forge/src` is the forge shell, built under `-Psnapsync.forge=true`. It was missing
     * from BOTH this list and the build's — the mirror was faithful and both copies were wrong,
     * which is the argument recorded in `complexity-budgets` for deriving the wider gate's coverage
     * from the Gradle model instead of mirroring a list. This gate keeps the list because one of its
     * roots (`test/rig/src/hook`) is not a module and the project model cannot express it.
     */
    /**
     * DERIVED from `appShellSources` in the root build file — the same list the `detektAppShell` task
     * scans — rather than a second copy of it.
     *
     * It used to be a copy, and the duplication had already failed once: `build.gradle.kts` records that
     * `app/ios/forge/src` was absent from BOTH this list and `appShellSources` until the
     * complexity-budgets change measured the tree. Its own comment draws the conclusion — "a
     * hand-maintained list of roots stops being true the moment a module is added, and nothing tells
     * you" — so the fix is to stop maintaining a second one. `DetektTierCoverageTest` reads
     * `detektTierOf` out of this same file for the same reason.
     *
     * Parsed from the build script text because `:test:architecture` deliberately depends on no project
     * modules and so has no access to the Gradle model.
     */
    private val shellSourceRoots: List<String> = run {
        val build = File(repoRoot, "build.gradle.kts").readText()
        // Comments are stripped BEFORE the block is delimited. A `[^)]*` match truncated at the first
        // `)` inside a comment — "(it constructs and forwards, it decides nothing)" — silently dropping
        // every root declared after it, `app/ios/forge/src` included. The declaration ends at the first
        // line that is a lone `)`.
        val code = build.lines().joinToString("\n") { it.substringBefore("//") }
        val block = code.substringAfter("val appShellSources = files(", missingDelimiterValue = "")
            .substringBefore("\n)")
            .takeIf { it.isNotBlank() }
            ?: fail(
                "the root build file no longer declares `val appShellSources = files(...)` — the shell " +
                    "gate's scope has moved, and this guard is now deriving from nothing. Re-point it " +
                    "rather than restoring a hand-written copy.",
            )
        val roots = Regex(""""([^"]+)"""").findAll(block).map { it.groupValues[1] }.toList()
        assertTrue(
            roots.isNotEmpty(),
            "parsed zero roots out of `appShellSources` — the declaration's shape changed and this " +
                "guard would scan nothing",
        )
        roots
    }

    /** file (relative) → pinned `@Suppress("CyclomaticComplexMethod")` count. Exact, both directions. */
    private val pins: Map<String, Int> = mapOf(
        "app/ios/src/iosMain/kotlin/app/snapsync/ios/SnapSyncRoot.kt" to 1,
        "app/ios/src/iosMain/kotlin/app/snapsync/ios/MainViewController.kt" to 1,
    )

    private val suppression = Regex("""@Suppress\("CyclomaticComplexMethod"\)""")

    /** Production Kotlin under the shell roots (the same test-source excludes as the detekt task). */
    private fun shellSources(): List<File> = shellSourceRoots.flatMap { root ->
        File(repoRoot, root).walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { file ->
                val p = file.path.replace('\\', '/')
                "/build/" in p || listOf("commonTest", "iosTest", "jvmTest", "appleTest", "nativeTest")
                    .any { "/$it/" in p }
            }
            .toList()
    }

    /**
     * The floor deriving cannot provide: that `appShellSources` COVERS every iOS shell module.
     *
     * Deriving this guard's roots from the build file removes drift between the two lists, but it cannot
     * notice that the one remaining list is incomplete — if a shell module is missing from
     * `appShellSources`, the guard now agrees with it and both are silently wrong. That is precisely the
     * failure on record: `app/ios/forge/src` was absent from both until the tree was measured.
     *
     * So the expected set is derived from a THIRD place neither list controls — the build's own include
     * set. Every `:app:ios*` module SHALL have its source root scanned. `:app:desktop` is excluded
     * deliberately and by name: it is test equipment hosting two harness applications, measured as
     * harness under capability `complexity-budgets`, and has never been in this gate's scope.
     */
    @Test
    fun `every iOS shell module is scanned by the shell gate`() {
        val includes = Regex("""include\("(:[^"]+)"\)""")
            .findAll(File(repoRoot, "settings.gradle.kts").readText())
            .map { it.groupValues[1] }
            .toList()
        assertTrue(includes.isNotEmpty(), "settings.gradle.kts parsed to zero includes — the scan is broken")

        val shellModules = includes.filter { it == ":app:ios" || it.startsWith(":app:ios:") }
        assertTrue(shellModules.isNotEmpty(), "no :app:ios* modules found — the shells have moved")

        val unscanned = shellModules.filterNot { module ->
            val path = module.removePrefix(":").replace(':', '/') + "/src"
            shellSourceRoots.any { it == path }
        }
        assertTrue(
            unscanned.isEmpty(),
            "iOS shell modules the shell gate does not scan: $unscanned. Add each one's `src` to " +
                "`appShellSources` in the root build file. A shell absent from that list is never " +
                "measured for decisions, and nothing else would tell you \u2014 which is exactly how " +
                "`:app:ios:forge` went unscanned until the tree was measured.",
        )
    }

    @Test
    fun `the shell gate's source roots exist and are non-empty (non-vacuity floor)`() {
        // The detekt gate silently passes on an empty source set; a stale `appShellSources` list
        // after a module rename is exactly how the flip would go vacuous. This floor fails first.
        shellSourceRoots.forEach { root ->
            assertTrue(File(repoRoot, root).isDirectory, "shell source root $root has moved — update appShellSources AND this guard")
        }
        assertTrue(
            shellSources().isNotEmpty(),
            "the shell gate scanned zero production Kotlin files — appShellSources is stale and detektAppShell passes vacuously",
        )
    }

    @Test
    fun `every complexity suppression in the shells is pinned, exactly`() {
        val found = shellSources().associate { file ->
            file.toRelativeString(repoRoot).replace('\\', '/') to suppression.findAll(file.readText()).count()
        }.filterValues { it > 0 }
        assertEquals(
            pins,
            found,
            "the shell pin inventory drifted. MORE suppressions than pinned: the decision moves to a " +
                "tested zone (feature/flow/compose/adapter) unless a platform contract forces it — " +
                "then pin it HERE with the forcing proof at the suppression site. FEWER: good — " +
                "shrink this table in the same commit so it never overstates the debt.",
        )
    }
}
