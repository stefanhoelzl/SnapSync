package app.snapsync.buildlogic

import io.gitlab.arturbosch.detekt.Detekt
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import java.io.File

/**
 * `snapsync.detekt-tiers`, applied once, by the root (`docs/architecture.md`, "Complexity ceilings"): every detekt
 * task the build runs — the shell PROOF (`detektAppShell`) and the complexity BUDGETS (one task per tier) — and the
 * project's own rules on each.
 *
 * The plugin's own `detekt` task is disabled: it inspects the ROOT project's Kotlin source set, which does not exist,
 * so it would pass while inspecting nothing — a vacuous pass one tab-completion away from the real ones. Every detekt
 * task in this build is one registered here on purpose.
 */
class DetektTiersPlugin : Plugin<Project> {
    override fun apply(root: Project) {
        check(root == root.rootProject) { "snapsync.detekt-tiers is the root's: it reads every subproject" }
        root.pluginManager.apply("io.gitlab.arturbosch.detekt")
        root.tasks.named("detekt").configure { enabled = false }

        // The project's own rules (`:tools:detekt-rules`) and the two third-party rule sets, on every task registered
        // here: the plugin hands the root's `detektPlugins` to each. Which files a rule reads is its `includes` in
        // `config/detekt/_base.yml`.
        val libs = root.extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
        root.dependencies.add("detektPlugins", root.project(":tools:detekt-rules"))
        root.dependencies.add("detektPlugins", libs.findLibrary("detekt-formatting").get())
        root.dependencies.add("detektPlugins", libs.findLibrary("compose-rules-detekt").get())

        registerAppShell(root)
        registerTiers(root)
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
        root.registerTier("detektCoreTier", "core", srcDirs("core")) {
            exclude(excludeTests)
            // `compose/` is carved out into its own tier below — a zone that carries a written decision-free law.
            exclude("**/app/snapsync/compose/**")
        }
        // The layers whose numbers are a CLAIM, not debt (`config/detekt/entrypoints.yml`, `scheduling.yml`,
        // `domain.yml`).
        root.registerTier("detektEntryPointsTier", "entrypoints", srcDirs("entrypoints")) { exclude(excludeTests) }
        root.registerTier("detektSchedulingTier", "scheduling", srcDirs("scheduling")) { exclude(excludeTests) }
        root.registerTier("detektDomainTier", "domain", srcDirs("domain")) { exclude(excludeTests) }
        root.registerTier("detektDataTier", "data", srcDirs("data")) { exclude(excludeTests) }
        root.registerTier("detektApplicationTier", "application", srcDirs("application")) { exclude(excludeTests) }
        root.registerTier("detektHarnessTier", "harness", srcDirs("harness")) {
            exclude(excludeTests)
            // Shell source contributed into `:app:ios`, owned by `detektAppShell` at threshold 2.
            exclude("**/test/rig/src/hook/**", "**/test/rig/src/ext-hook/**")
        }
        // A path sub-scope of the core, carved out of `core` ("One shared composition").
        root.registerTier("detektComposeTier", "compose", srcDirs("core")) {
            include("**/app/snapsync/compose/**")
            exclude(excludeTests)
        }
        // Every module's test source sets, plus the modules that are nothing but tests.
        root.registerTier(
            "detektTestsTier",
            "tests",
            root.subprojects.map { it.layout.projectDirectory.dir("src").asFile },
        ) { include(TEST_SOURCE_SET_DIRS.map { "**/$it/**" }) }
        // The build scripts: Kotlin carrying real logic. Of the convention plugins, the wire's route generator (build
        // logic, and real logic); the others predate the scan and are not in it yet — bringing them in is its own
        // change.
        root.registerTier(
            "detektBuildScriptsTier",
            "buildscripts",
            listOf(root.buildFile, root.file("settings.gradle.kts")) + root.subprojects.map { it.buildFile } +
                listOf(
                    root.file("build-logic/build.gradle.kts"),
                    root.file("build-logic/src/main/kotlin/app/snapsync/buildlogic/wire"),
                ),
        )
    }

    /**
     * Registers one gating tier task. The non-empty check is the anti-vacuity floor required of every source-scanning
     * guard: detekt reports SUCCESS on an empty source set, so a filter regression would otherwise pass by inspecting
     * nothing.
     */
    private fun Project.registerTier(name: String, tier: String, sources: List<File>, configure: Detekt.() -> Unit = {}) {
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
            // The shared host composition (`snapSyncHost`): wiring every root calls, holding no decision.
            "domain/host/src",
            // The shared composition (`snapSyncApp`, `snapSyncExtension`, `uploadCycle`): it constructs and connects.
            "domain/compose/src",
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
            ":domain:host" to "shell",
            ":app:jvm" to "shell",
            ":app:android" to "shell",

            // The tested core and its adapters. `:domain:presentation` belongs here and not in `ui`: it is
            // Compose-free, so none of Compose's structural inflation applies to it.
            ":domain:model" to "core",
            ":domain:inbound" to "core",
            ":domain:wire" to "core",
            ":domain:ports" to "core",
            ":domain:services" to "core",
            ":domain:feature" to "core",
            ":domain:compose" to "core",
            ":adapter:generic:app" to "core",
            ":adapter:generic:mock" to "core",
            ":adapter:generic:motionphoto" to "core",
            ":adapter:generic:sentry" to "core",
            ":adapter:ios:app-only" to "core",
            ":adapter:ios:ext-safe" to "core",
            ":adapter:ios:ui" to "core",
            ":adapter:android" to "core",
            ":domain:presentation" to "core",
            ":ui:state" to "core",

            // The layers whose ceilings are claims from their first line (`docs/patterns.md`): an entry point orders
            // steps and decides nothing; the runtime is small and stays so; a domain verdict is a `when` over a fact.
            ":domain:entrypoints" to "entrypoints",
            ":domain:scheduling" to "scheduling",
            ":domain:domain" to "domain",
            ":domain:data" to "data",
            ":domain:application" to "application",

            // Compose. Its own tier because Compose inflates cyclomatic complexity and function length
            // STRUCTURALLY — a screen that renders six states has six branches by construction.
            ":ui:components" to "ui",
            ":ui:screens" to "ui",

            // Test equipment: harnesses, the control channel, the diagram generators. Read once, never shipped.
            ":app:desktop" to "harness",
            ":test:harness-driver" to "harness",
            ":test:rig" to "harness",
            ":test:launch-adapters" to "harness",
            ":test:contracts" to "harness",
            ":test:edge" to "harness",
            ":test:control" to "harness",
            ":test:cli" to "harness",
            ":test:levers" to "harness",
            ":tools:diagrams" to "harness",
            ":tools:detekt-rules" to "harness",

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
