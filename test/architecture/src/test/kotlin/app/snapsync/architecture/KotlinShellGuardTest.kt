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
    private val shellSourceRoots =
        listOf("app/ios/src", "app/ios/extension/src", "app/ios/forge/src", "test/rig/src/hook")

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
