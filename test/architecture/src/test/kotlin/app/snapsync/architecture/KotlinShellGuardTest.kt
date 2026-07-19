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
 *  - `SnapSyncRoot.kt` ×2 — the background-`URLSession` callback routing (UIKit delivers ONE app
 *    delegate callback for every session identifier, and this app owns two OS-reattached sessions;
 *    expiry: dies with the 18–26.0 tier) and the dev policy probe (dev equipment driving the live
 *    graph from a launch-env trigger; inert in production).
 *  - `DevPhotoSeeder.kt` ×3 — dev equipment writing the real photo library from a launch-env
 *    trigger; operator-input validation plus platform-forced chunking.
 */
class KotlinShellGuardTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: fail("could not locate the repository root")

    /** MUST mirror the root build's `appShellSources` — the detekt gate's scanned roots. */
    private val shellSourceRoots = listOf("app/ios/src", "app/ios/extension/src")

    /** file (relative) → pinned `@Suppress("CyclomaticComplexMethod")` count. Exact, both directions. */
    private val pins: Map<String, Int> = mapOf(
        "app/ios/src/iosMain/kotlin/app/snapsync/ios/SnapSyncRoot.kt" to 2,
        "app/ios/src/iosMain/kotlin/app/snapsync/ios/DevPhotoSeeder.kt" to 3,
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
