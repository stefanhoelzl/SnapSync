package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **The module set withholds; packages organize** (spec `module-architecture`; promoted from the
 * migration beacon's module-set row at the finale, per the beacon's own contract: each law at
 * zero becomes a permanent gate). The build's module set SHALL be exactly the target list — a
 * module exists only to withhold a third-party or platform dependency by compile error; anything
 * finer is a package with a derived text gate.
 *
 * This is one of the two lists the guards spec permits ("Gates fail closed on novelty": the
 * end-state module list is loud-when-stale — a new `include` fails HERE, visibly, until the list
 * is consciously amended *with* the withholding argument recorded in a spec delta to
 * `module-architecture`). Deleting a module fails too: the table must shrink in the same commit.
 */
class ModuleSetTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: fail("could not locate the repository root")

    /** The target module set (spec `module-architecture`, "The module set withholds"). */
    private val targetModules = setOf(
        ":domain",
        ":ui:presentation", ":ui:screens", ":ui:components",
        ":adapter:ios:ext-safe", ":adapter:ios:app-only", ":adapter:generic:app", ":adapter:generic:fake",
        ":app:ios", ":app:ios:extension", ":app:ios:forge", ":app:desktop",
        ":test:world", ":test:integration", ":test:architecture", ":test:harness-driver", ":test:rig",
        ":tools:diagrams",
    )

    @Test
    fun `the settings module set equals the target module set`() {
        val settings = File(repoRoot, "settings.gradle.kts")
        val includes = Regex("""include\("([^"]+)"\)""")
            .findAll(settings.readText())
            .map { it.groupValues[1] }
            .toSet()
        assertTrue(includes.isNotEmpty(), "settings.gradle.kts parsed to zero includes — the scan is broken")
        assertEquals(
            targetModules,
            includes,
            "the module set drifted from the module-architecture target. A NEW module must " +
                "withhold a third-party/platform dependency by compile error (anything finer is a " +
                "package with a gate) and is a spec delta to module-architecture; a DELETED module " +
                "shrinks this table in the same commit.",
        )
    }

    @Test
    fun `the core declares zero project dependencies`() {
        // The law's own words (`module-architecture`, "The module set withholds"): `:domain` has
        // ZERO project() dependencies — that absence is what makes the core platform-free by
        // compile error. Asserted on the build script text (a declaration always quotes a path,
        // so the prose mention of project() in comments does not match).
        val build = File(repoRoot, "domain/build.gradle.kts")
        assertTrue(build.isFile, "domain/build.gradle.kts is missing — the core moved")
        assertTrue(
            !Regex("project\\(\\s*\"").containsMatchIn(build.readText()),
            "domain/build.gradle.kts declares a project(\"...\") dependency — :domain must have ZERO " +
                "project dependencies (the withholding that keeps the core platform-free)",
        )
    }
}
