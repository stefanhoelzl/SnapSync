package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **Coverage is derived, never remembered** (spec `complexity-budgets`). Every subproject in the
 * build resolves to exactly one complexity tier, so a module added to `settings.gradle.kts` is
 * measured without anyone remembering to add it — or fails HERE, by name, until a tier is chosen.
 *
 * This guard exists because the alternative already failed. `detektAppShell`'s roots are a hand list
 * mirrored in [KotlinShellGuardTest]; the mirror was faithful and BOTH copies were wrong, missing
 * `:app:ios:forge` until the change that added these tiers measured the tree and found it. The tier
 * tasks therefore read each mapped subproject's own `src` directory out of the live Gradle project
 * model — this test guards the one thing the model cannot supply, which is the mapping itself.
 *
 * It does NOT check that a ceiling only ever falls. Nothing does: that is a ratchet carried by the
 * contract at the head of each tier config and by review, and the capability says so rather than
 * implying a guarantee it does not deliver. What is mechanical is coverage.
 *
 * THE SUBPROJECT ASSERTION IS LOAD-BEARING FOR A REASON THAT IS EASY TO MISS. A tier's own config file
 * is optional, and its absence MEANS the scope sits at the shared baseline (`config/detekt/_base.yml`).
 * That reading is only safe because [every subproject resolves to exactly one complexity tier] holds
 * independently: without it, "this scope has no config file because it is at the baseline" and "this
 * scope is in no tier and is measured by nothing" would look identical from the config directory, and
 * the quieter of the two is the dangerous one. Do not weaken that test to make this one pass.
 */
class DetektTierCoverageTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: fail("could not locate the repository root")

    private val rootBuildFile = File(repoRoot, "build.gradle.kts")

    /**
     * Not a tier's config. `_base.yml` is the shared baseline every tier layers beneath its own file;
     * `app-shell.yml` belongs to the shell PROOF (capability `architecture-guards`), which is a
     * different kind of gate and deliberately absent from the tier map.
     */
    private val nonTierConfigs = setOf("_base", "app-shell")

    /**
     * Tiers that scope by PATH rather than by module, so they appear in no module mapping:
     * `flow` and `compose` are sub-scopes of `:domain` carved out of `core`, and `buildscripts`
     * is every `*.gradle.kts` in the build. They still need a config and a registered task, which
     * is what the tests below assert about them.
     */
    private val pathScopedTiers = setOf("flow", "compose", "buildscripts")

    /** The `detektTierOf` block, isolated so a stray `"a" to "b"` elsewhere cannot feed the parse. */
    private fun tierMapBlock(): String {
        val text = rootBuildFile.readText()
        val start = text.indexOf("val detektTierOf: Map<String, String> = mapOf(")
        assertTrue(start >= 0, "build.gradle.kts no longer declares `detektTierOf` — this guard is scanning nothing")
        val end = text.indexOf("\n)", start)
        assertTrue(end > start, "could not find the end of the `detektTierOf` block")
        return text.substring(start, end)
    }

    /** `":app:ios" to "shell"` pairs, in source order — duplicates preserved, so a repeat is visible. */
    private fun tierPairs(): List<Pair<String, String>> =
        Regex("""^\s*"(:[^"]+)"\s+to\s+"([a-z]+)",""", RegexOption.MULTILINE)
            .findAll(tierMapBlock())
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()

    private fun includedModules(): List<String> =
        Regex("""include\("([^"]+)"\)""")
            .findAll(File(repoRoot, "settings.gradle.kts").readText())
            .map { it.groupValues[1] }
            .toList()

    @Test
    fun `every subproject resolves to exactly one complexity tier`() {
        val modules = includedModules()
        val pairs = tierPairs()
        assertTrue(modules.isNotEmpty(), "settings.gradle.kts parsed to zero includes — the scan is broken")
        assertTrue(pairs.isNotEmpty(), "`detektTierOf` parsed to zero entries — the scan is broken")

        val unmapped = modules.toSet() - pairs.map { it.first }.toSet()
        assertTrue(
            unmapped.isEmpty(),
            "these modules are in the build but in NO complexity tier, so nothing measures them: " +
                "${unmapped.sorted()}. Add each to `detektTierOf` in build.gradle.kts " +
                "(spec `complexity-budgets`, \"Coverage is derived, never remembered\").",
        )

        val stale = pairs.map { it.first }.toSet() - modules.toSet()
        assertTrue(
            stale.isEmpty(),
            "`detektTierOf` maps modules that are not in the build: ${stale.sorted()}",
        )
    }

    /**
     * A duplicated key in a Kotlin `mapOf` silently keeps the LAST one, so a module listed under two
     * tiers would compile, run, and be measured by whichever line came second. Only the source text
     * shows it.
     */
    @Test
    fun `no module is listed under two tiers`() {
        val pairs = tierPairs()
        val duplicated = pairs.groupBy { it.first }.filterValues { it.size > 1 }
        assertTrue(
            duplicated.isEmpty(),
            "these modules appear more than once in `detektTierOf`, and Kotlin's `mapOf` would " +
                "silently keep only the last: ${duplicated.keys.sorted()}",
        )
    }

    /**
     * The assertion runs ONE WAY ONLY: every config file must belong to a tier. The reverse — every
     * tier must have a config — was true while a tier's numbers were mandatory, and is now false by
     * design: a scope that deviates from the baseline on nothing has nothing to write down, and its
     * missing file is the record of that (capability `complexity-budgets`).
     *
     * What remains worth catching is a file that governs nothing. It measures no scope while sitting
     * in the directory looking like it does, so a reader counting files to see which scopes carry debt
     * would count it — which is exactly the signal the absent-file convention exists to give.
     */
    @Test
    fun `every config file belongs to a tier`() {
        val tiers = tierPairs().map { it.second }.toSet()
        val configDir = File(repoRoot, "config/detekt")
        assertTrue(configDir.isDirectory, "config/detekt is not a directory — this guard is scanning nothing")

        val configs = configDir.listFiles { f: File -> f.extension == "yml" }
            .orEmpty()
            .map { it.nameWithoutExtension }
            .toSet()
        assertTrue(configs.isNotEmpty(), "config/detekt holds no .yml files — the scan is broken")
        assertTrue(
            "_base" in configs,
            "config/detekt/_base.yml is missing — every tier layers it, so without it each tier falls " +
                "back to detekt's stock defaults and the repo-wide readings silently stop applying",
        )

        val orphans = configs - tiers - pathScopedTiers - nonTierConfigs
        assertTrue(
            orphans.isEmpty(),
            "config files that no tier uses — either wire them up or delete them: ${orphans.sorted()}. " +
                "A scope needs a file ONLY where it deviates from the baseline; a file naming no scope " +
                "measures nothing while appearing to.",
        )
    }

    /**
     * A tier task that is not wired into `check` runs only when someone types its name, which is the
     * same as not existing. `registerDetektTier` does the wiring, so this asserts every tier goes
     * through it rather than being registered by hand beside it.
     */
    @Test
    fun `every tier config is claimed by a registered tier task`() {
        val text = rootBuildFile.readText()
        val registered = Regex("""registerDetektTier\(\s*"(\w+)",\s*"([a-z-]+)"""")
            .findAll(text)
            .map { it.groupValues[2] }
            .toList()
        assertTrue(registered.isNotEmpty(), "no `registerDetektTier` calls found — this guard is scanning nothing")

        val expected = tierPairs().map { it.second }.toSet() + pathScopedTiers
        assertEquals(
            expected.sorted(),
            registered.sorted().distinct(),
            "the set of registered tier tasks does not match the set of tiers",
        )
        assertEquals(
            registered.size,
            registered.distinct().size,
            "a tier config is registered by more than one task: $registered",
        )
    }
}
