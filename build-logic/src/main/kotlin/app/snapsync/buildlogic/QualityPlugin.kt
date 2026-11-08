package app.snapsync.buildlogic

import io.gitlab.arturbosch.detekt.Detekt
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import java.io.File

/**
 * `snapsync.quality`, applied once, by the root (`docs/architecture.md`, "Complexity ceilings"): every detekt
 * task the build runs — the shell PROOF (`detektAppShell`) and the complexity BUDGETS (one task per tier, the build
 * logic's own included) — the project's own rules on each, and their tests on `check`.
 *
 * The plugin's own `detekt` task is disabled: it inspects the ROOT project's Kotlin source set, which does not exist,
 * so it would pass while inspecting nothing — a vacuous pass one tab-completion away from the real ones. Every detekt
 * task in this build is one registered here on purpose.
 */
class QualityPlugin : Plugin<Project> {
    override fun apply(root: Project) {
        check(root == root.rootProject) { "snapsync.quality is the root's: it reads every subproject" }
        root.pluginManager.apply("io.gitlab.arturbosch.detekt")
        root.tasks.named("detekt").configure { enabled = false }

        // The project's own rules (build-logic's `:detekt-rules`, substituted by its coordinates) and the two
        // third-party rule sets, on every task registered here: the plugin hands the root's `detektPlugins` to each.
        // Which files a rule reads is its `includes` in `config/detekt/_base.yml`.
        val libs = root.extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
        root.dependencies.add("detektPlugins", RULES)
        // The rules' own tests: an included build's `check` is never reached from this one's.
        val rulesTest = root.gradle.includedBuild(BUILD_LOGIC).task(":detekt-rules:test")
        root.tasks.named("check").configure { dependsOn(rulesTest) }
        root.dependencies.add("detektPlugins", libs.findLibrary("detekt-formatting").get())
        root.dependencies.add("detektPlugins", libs.findLibrary("compose-rules-detekt").get())

        registerAppShell(root)
        registerTiers(root)
        registerIncludesMatch(root)
    }

    /**
     * Every rule's `includes` glob matches a file ([DetektIncludes]): a glob that matches nothing switches its rule off
     * silently, which is how a moved module would escape every rule scoped to its path.
     */
    private fun registerIncludesMatch(root: Project) {
        val configs = root.fileTree("config/detekt") { include("*.yml") }
        val rootDir = root.rootDir
        root.tasks.register("detektIncludesMatch") {
            val task = this
            task.description = "Fails when a detekt rule's `includes` glob matches no source file."
            task.group = "verification"
            task.inputs.files(configs)
            task.doLast {
                val files = DetektIncludes.sources(rootDir)
                val stale = configs.files.sortedBy { it.name }.flatMap { config ->
                    DetektIncludes.unmatched(
                        DetektIncludes.globs(config.readText()),
                        files,
                    ).map { "${config.name}: $it" }
                }
                check(stale.isEmpty()) {
                    "detekt `includes` globs that match no source file — a rule scoped to them reads nothing:\n  " +
                        stale.joinToString("\n  ")
                }
            }
        }
        root.tasks.named("check").configure { dependsOn("detektIncludesMatch") }
    }

    /**
     * The shell PROOF: threshold 2 asserts the shells hold no decisions, and its value comes precisely from the number
     * being 2. NOT one of the budgets below, and never to be merged with them.
     */
    private fun registerAppShell(root: Project) {
        root.tasks.register("detektAppShell", Detekt::class.java) {
            val task = this
            task.description = "Counts decisions in the app shells (`docs/architecture.md`)."
            task.group = "verification"
            task.setSource(root.files(*APP_SHELL_SOURCES.toTypedArray()))
            // Production only. A test may branch as much as it likes — the rule is about wiring, not about Kotlin.
            task.exclude(TEST_SOURCE_SET_DIRS.map { "**/$it/**" })
            task.exclude("**/build/**")
            task.config.setFrom(root.files("config/detekt/app-shell.yml"))
            task.buildUponDefaultConfig = false
            // GATING: a new conditional in a shell fails the canonical build. The only tolerated forms are the pinned
            // `@Suppress("CyclomaticComplexMethod")` sites, whose inventory `KotlinShellGuardTest` gates.
            task.ignoreFailures = false
            task.autoCorrect = root.providers.gradleProperty("snapsync.detektAutoCorrect").isPresent
            task.xmlOnly(root)
        }
        root.tasks.named("check").configure { dependsOn("detektAppShell") }
    }

    /**
     * The complexity BUDGETS: a ceiling for every Kotlin source in the repository, per scope, seeded at what the tree
     * measured, permitted to move in one direction only — down. One task per tier, because a detekt 1.x rule carries
     * exactly ONE threshold per config: path filters vary the scope a rule applies to, never its number.
     *
     * Coverage is derived, not listed: [TIER_OF] maps subproject paths, and each task reads its subprojects' `src`
     * directories out of the live project model, so a module added to `settings.gradle.kts` is scanned without
     * touching this file — or, if it is in no tier, `DetektTierCoverageTest` fails naming it.
     */
    private fun registerTiers(root: Project) {
        fun srcDirs(tier: String): List<File> = root.subprojects
            .filter { TIER_OF[it.path] == tier }
            .map { it.layout.projectDirectory.dir("src").asFile }
        val excludeTests = TEST_SOURCE_SET_DIRS.map { "**/$it/**" }

        root.registerTier("detektShellTier", "shell", srcDirs("shell")) { exclude(excludeTests) }
        root.registerTier("detektUiTier", "ui", srcDirs("ui")) { exclude(excludeTests) }
        root.registerTier("detektCoreTier", "core", srcDirs("core")) { exclude(excludeTests) }
        // The layers whose numbers are a CLAIM, not debt (`config/detekt/entrypoints.yml`, `scheduling.yml`,
        // `domain.yml`).
        root.registerTier("detektEntryPointsTier", "entrypoints", srcDirs("entrypoints")) { exclude(excludeTests) }
        root.registerTier("detektSchedulingTier", "scheduling", srcDirs("scheduling")) { exclude(excludeTests) }
        root.registerTier("detektDomainTier", "domain", srcDirs("domain")) { exclude(excludeTests) }
        root.registerTier("detektDataTier", "data", srcDirs("data")) { exclude(excludeTests) }
        root.registerTier("detektApplicationTier", "application", srcDirs("application")) { exclude(excludeTests) }
        root.registerTier("detektQueryTier", "query", srcDirs("query")) { exclude(excludeTests) }
        root.registerTier("detektPresentationTier", "presentation", srcDirs("presentation")) { exclude(excludeTests) }
        root.registerTier("detektCompositionTier", "composition", srcDirs("composition")) { exclude(excludeTests) }
        root.registerTier("detektHarnessTier", "harness", srcDirs("harness")) {
            exclude(excludeTests)
            // Shell source contributed into `:app:ios`, owned by `detektAppShell` at threshold 2.
            exclude("**/test/rig/src/hook/**", "**/test/rig/src/ext-hook/**")
        }
        val buildLogic = BUILD_LOGIC_SOURCES.map(root::file)
        // Every module's test source sets, plus the modules that are nothing but tests — and the build logic's tests.
        root.registerTier(
            "detektTestsTier",
            "tests",
            root.subprojects.map { it.layout.projectDirectory.dir("src").asFile } + buildLogic,
        ) { include(TEST_SOURCE_SET_DIRS.map { "**/$it/**" }) }
        // The build: every build script, and the whole build-logic build — the convention plugins and the project's
        // detekt rules — whose production sources are Kotlin carrying real logic.
        root.registerTier(
            "detektBuildScriptsTier",
            "buildscripts",
            listOf(root.buildFile, root.file("settings.gradle.kts")) + root.subprojects.map { it.buildFile } +
                BUILD_LOGIC_BUILD_FILES.map(root::file) + buildLogic,
        ) { exclude(excludeTests) }
    }

    /**
     * Registers one gating tier task. The non-empty check is the anti-vacuity floor required of every source-scanning
     * guard: detekt reports SUCCESS on an empty source set, so a filter regression would otherwise pass by inspecting
     * nothing.
     */
    private fun Project.registerTier(
        name: String,
        tier: String,
        sources: List<File>,
        configure: Detekt.() -> Unit = {},
    ) {
        tasks.register(name, Detekt::class.java) {
            val task = this
            task.description = "Complexity ceiling for the `$tier` scope (`docs/architecture.md`)."
            task.group = "verification"
            task.setSource(files(sources))
            task.exclude("**/build/**")
            // The shared baseline first, then this tier's own file overriding it. The tier file is OPTIONAL: its
            // absence means the scope sits at the baseline, so the set of files under `config/detekt/` is the list
            // of scopes still carrying debt — or, for `entrypoints` and `scheduling`, stating a claim.
            val tierConfig = file("config/detekt/$tier.yml")
            task.config.setFrom(
                files("config/detekt/_base.yml") + if (tierConfig.exists()) files(tierConfig) else files(),
            )
            task.buildUponDefaultConfig = true
            task.ignoreFailures = false
            // `-Psnapsync.detektAutoCorrect` lets the formatting rules rewrite what they flag, instead of failing.
            task.autoCorrect = providers.gradleProperty("snapsync.detektAutoCorrect").isPresent
            task.xmlOnly(this@registerTier, name)
            task.configure()
            task.doFirst {
                check(!task.source.isEmpty) {
                    "detekt tier `$name` scanned ZERO files — its scope resolved empty, so it would have passed " +
                        "while inspecting nothing (`docs/architecture.md`, \"Coverage is derived, never remembered\")."
                }
            }
        }
        tasks.named("check").configure { dependsOn(name) }
    }

    /** One XML report per task, named for it: the plugin's default `detekt.xml` would be overwritten by every task. */
    private fun Detekt.xmlOnly(root: Project, name: String = this.name) {
        reports {
            xml.required.set(true)
            xml.outputLocation.set(root.layout.buildDirectory.file("reports/detekt/$name.xml"))
            html.required.set(false)
            txt.required.set(false)
            sarif.required.set(false)
            md.required.set(false)
        }
    }

    companion object {
        /** The included build holding the convention plugins and the project's detekt rules. */
        const val BUILD_LOGIC = "build-logic"

        /** The project's own rules, by the coordinates the root substitutes to build-logic's `:detekt-rules`. */
        const val RULES = "app.snapsync.buildlogic:detekt-rules"

        /** The build-logic build's source roots, production and tests (the tiers split them by source-set name). */
        val BUILD_LOGIC_SOURCES = listOf("build-logic/src", "build-logic/detekt-rules/src")

        /** The build-logic build's own build scripts. */
        val BUILD_LOGIC_BUILD_FILES = listOf(
            "build-logic/settings.gradle.kts",
            "build-logic/build.gradle.kts",
            "build-logic/detekt-rules/build.gradle.kts",
        )

        /** Kotlin source-set directory names that hold TESTS. Everything else under `src/` is production. */
        val TEST_SOURCE_SET_DIRS = listOf(
            "commonTest",
            "iosTest",
            "iosSimulatorArm64Test",
            "jvmTest",
            "appleTest",
            "nativeTest",
            "androidDeviceTest",
            "test",
        )

        /**
         * The shell roots, NAMED rather than derived from `:app:*` (`docs/architecture.md`, "The shell gates").
         * `:app:desktop` is an `:app:*` module this gate must not scan — it hosts the harness applications and is
         * measured as `harness`. `KotlinShellGuardTest.shellSourceRoots` mirrors this list and must move with it.
         */
        val APP_SHELL_SOURCES = listOf(
            "app/ios/src",
            "app/ios/extension/src",
            // The JVM root (`JvmApp`): the same composition as the iOS root, for the JVM test equipment.
            "app/jvm/src",
            // Compiled INTO `:app:ios` under `-Psnapsync.rig=true`, so it is shell source for gate purposes even
            // though it lives in `:test:rig`'s tree ("Source contributed into a shell's source set is shell source").
            "test/rig/src/hook",
            // The same, compiled INTO `:app:ios:extension` in place of its `src/entries` under the same property.
            "test/rig/src/ext-hook",
            // The Android root (`SnapSyncRoot`): the same composition, in the Android app's one process.
            "app/android/src",
            // The same, compiled INTO `:app:android` in place of its `src/prod` under the same property.
            "test/rig/src/android-hook",
        )

        /**
         * Subproject path → production tier. EVERY subproject is mapped, exactly once; `DetektTierCoverageTest`
         * fails the build naming any that is not, so a new module cannot be silently unmeasured.
         *
         * `tests` is the tier of a module whose only source is tests. Every OTHER module's test source sets are
         * scanned by the `tests` task too — that tier is a source-set dimension as well as a module bucket.
         */
        val TIER_OF: Map<String, String> = mapOf(
            // The shells. Scanned here only for the rules the shell config does not carry (length, parameter counts,
            // naming); their DECISIONS are the shell proof's business, at threshold 2.
            ":app:ios" to "shell",
            ":app:ios:extension" to "shell",
            ":app:jvm" to "shell",
            ":app:android" to "shell",

            // The tested core and its adapters.
            ":core:inbound" to "core",
            ":core:wire" to "core",
            ":core:format" to "core",
            ":core:ports" to "core",
            ":adapter:generic:app" to "core",
            ":adapter:generic:mock" to "core",
            ":adapter:generic:motionphoto" to "core",
            ":adapter:generic:sentry" to "core",
            ":adapter:ios:app-only" to "core",
            ":adapter:ios:ext-safe" to "core",
            ":adapter:ios:ui" to "core",
            ":adapter:android" to "core",
            ":ui:state" to "core",

            // The layers whose ceilings are claims from their first line (`docs/patterns.md`): an entry point orders
            // steps and decides nothing; the runtime is small and stays so; a domain verdict is a `when` over a fact.
            ":core:entrypoints" to "entrypoints",
            ":core:scheduling" to "scheduling",
            ":core:domain" to "domain",
            ":core:data" to "data",
            ":core:application" to "application",
            ":core:query" to "query",
            ":core:presentation" to "presentation",
            // The composition layer: wiring only, so its decision measures are a claim too
            // (`config/detekt/composition.yml`).
            ":core:composition:process" to "composition",
            ":core:composition:extension" to "composition",
            ":core:composition:app" to "composition",

            // Compose. Its own tier because Compose inflates cyclomatic complexity and function length
            // STRUCTURALLY — a screen that renders six states has six branches by construction.
            ":ui:components" to "ui",
            ":ui:screens" to "ui",

            // Test equipment: harnesses and the control channel. Read once, never shipped.
            ":app:desktop" to "harness",
            ":test:harness-driver" to "harness",
            ":test:rig" to "harness",
            ":test:launch-adapters" to "harness",
            ":test:contracts" to "harness",
            ":test:edge" to "harness",
            ":test:client" to "harness",
            ":test:control" to "harness",
            ":test:verifies" to "harness",
            ":test:cli" to "harness",
            ":test:journeys" to "harness",
            ":test:levers" to "harness",

            // Modules with no production source at all.
            ":test:architecture" to "tests",
            ":test:integration" to "tests",
            ":test:feature" to "tests",
            ":test:data" to "tests",
            ":test:partial-grant" to "tests",
            ":test:no-grant" to "tests",
        )
    }
}
