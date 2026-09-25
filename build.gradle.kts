import org.gradle.api.artifacts.ProjectDependency

buildscript {
    // REQUIRED for `buildHealth` (`docs/architecture.md`). dependency-analysis 3.17.0 pins
    // `kotlin-metadata-jvm` at a version whose reader rejects Kotlin 2.4 metadata outright:
    //
    //     Provided Metadata instance has version 2.4.0, while maximum supported version is 2.3.0.
    //
    // It is a hard failure of `explodeJar*`, not a degraded result — without this force, `buildHealth`
    // cannot run at all. Upstream: autonomousapps#1661 / #1662, both OPEN. (#1724 "Kotlin 2.4.0 support"
    // is closed, but as a duplicate of those — closed is not fixed.)
    //
    // The reader is the only blocker, so forcing the metadata library to the Kotlin version this repo
    // actually uses resolves it. Verified 2026-07-16 (arch-abandoned-v1): with the force, `buildHealth`
    // analyses every multiplatform module and independently reports the dead
    // `:domain:status -> :capability:membership` edge. Drop this force once the plugin's pin catches up.
    configurations.classpath {
        resolutionStrategy.force("org.jetbrains.kotlin:kotlin-metadata-jvm:${libs.versions.kotlin.get()}")
    }
}

// Plugins are declared here (apply false) so every subproject shares ONE classloader for the
// Kotlin Gradle Plugin. Without this, each subproject loads KGP on its own classloader; the
// Apple targets' global build services (e.g. SwiftPMLockTaskAggregationBuildService) then fail
// to cast across classloaders — a configuration error that only surfaces once iOS targets exist.
plugins {
    // `base` gives the ROOT project the lifecycle tasks (`check`, `build`) that `detektAppShell`
    // gates through — `./gradlew build` includes the root `check`, so the shell gate runs in the
    // canonical build (`docs/architecture.md`, "The shell gates").
    base
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.dependency.analysis)
    // Applied per module rather than here (`docs/architecture.md`): the set of instrumented
    // modules is part of the contract, so it is readable in each module's own build file and a new
    // module is never silently instrumented.
    alias(libs.plugins.kover) apply false
}

// `./gradlew buildHealth` — declared-but-unused module dependencies (`docs/architecture.md`).
//
// Gradle prevents *using* a dependency that is not declared; nothing prevents *declaring* one that is
// not used, and that gap is not academic here: `:domain:status` declared `:capability:membership` and
// imported nothing from it for months, contradicting the documented dependency spine while every check
// stayed green.
//
// The root plugin only AGGREGATES; each subproject must apply it or `buildHealth` reports nothing and
// still succeeds — a vacuous pass of exactly the kind the guards exist to prevent.
//
// NON-GATING on purpose (spec: "Dead-edge analysis is scoped honestly"): not wired into `check`, every
// issue `warn`, jvm/common edges only — the plugin has no iOS-target support (upstream #1672); iosMain-
// only edges are covered by the text gates instead.
subprojects {
    apply(plugin = "com.autonomousapps.dependency-analysis")
}

// ---- Coverage crediting edges (`docs/architecture.md`) ----------------------------------
//
// `:domain` may not name another module in its OWN build file: `ModuleSetTest` asserts
// domain/build.gradle.kts contains no module path at all, because that absence is the precondition
// for the platform-free compile error. A Kover crediting edge is not a compile edge - it merges
// coverage DATA and never puts classes on a classpath - but that guard is a text assertion by
// design, and its worth is that it is absolute. So the edge is declared here rather than loosening
// it.
//
// Without the edge `:domain` measures 56% instead of 91%: `docs/testing.md` ("Fake-driven
// feature tests live in the fake module") places its feature tests in a module `:domain` cannot
// depend on, since the reverse edge would be a project cycle.
//
// All three live here rather than in the consuming modules, for two reasons that are really one:
// `ModuleSetTest` forbids `:domain` naming any module in its own build file, and `Zones.kt` reads
// every build script under `adapter/`, `domain/` and `ui/` as TEXT — so a locally declared edge
// renders in `architecture/zones.md` pointing the wrong way. Centralising them also puts the whole
// coverage-crediting picture in one place.
// ⚠️ `:domain` is NOT a consumer here any more. `include(":domain:model")` makes Gradle create an
// empty container project at `:domain`, which applies no plugin and holds no class - so an edge from
// it is never wired and a report filtered to it measures NOTHING. That is not hypothetical: after the
// zone split this list still said `":domain"`, `:domain:model`'s filter still said `projects.add(":domain")`,
// and `:domain:model:koverVerify` passed with `minValue = 100` over an empty report. Name the leaf
// modules, never the container.
listOf(
    ":domain:model" to ":adapter:generic:fake",
    ":domain:ports" to ":adapter:generic:fake",
    // The storage services' SQLite and file behaviour is measured beside the JVM adapters (a `:domain:*` build
    // file names no module), their fake-driven tests beside the mocks, and their composition through the world.
    ":domain:services" to ":adapter:generic:app",
    ":domain:services" to ":adapter:generic:fake",
    ":domain:services" to ":test:world",
    ":domain:feature" to ":adapter:generic:fake",
    ":domain:flow" to ":adapter:generic:fake",
    ":ui:components" to ":ui:screens",
    ":domain:presentation" to ":ui:screens",
).forEach { (consumer, producer) ->
    project(consumer).plugins.withId("org.jetbrains.kotlinx.kover") {
        project(consumer).dependencies.add("kover", project(producer))
    }
}

dependencyAnalysis {
    issues {
        all {
            onAny { severity("warn") }
        }
    }
}

// The iOS shell roots, NAMED rather than derived from `:app:*` (`docs/architecture.md`,
// "The shell gates"). `:app:desktop` is an `:app:*` module this gate has never scanned and must not:
// it hosts two harness applications and is test equipment, measured as `harness` under capability
// `docs/architecture.md`. Listing the shells is what makes that distinction visible; a `:app:*` glob
// would either drag the harness in or need an exemption naming it anyway.
val appShellSources = files(
    "app/ios/src",
    "app/ios/extension/src",
    // The forge shell — `ForgeViewController.kt`, the iOS entry point built under
    // `-Psnapsync.forge=true`. It is a shell by the same definition as the two above (it constructs
    // and forwards, it decides nothing), and it was absent from this list and from
    // `KotlinShellGuardTest.shellSourceRoots` until the complexity-budgets change measured the tree
    // and found it. That is precisely the failure the comment below warns about, so it is recorded
    // here rather than quietly fixed: a hand-maintained list of roots stops being true the moment a
    // module is added, and nothing tells you.
    "app/ios/forge/src",
    // The shared host composition (`snapSyncHost`): wiring every root calls, holding no decision. A shell by the
    // same definition, and listed for the same reason as the forge above — added with the module.
    "domain/host/src",
    // Compiled INTO `:app:ios` under `-Psnapsync.rig=true`, so it is shell source for gate purposes
    // even though it lives in `:test:rig`'s tree (`docs/architecture.md`, "Source contributed
    // into a shell's source set is shell source for the gates"). Listed rather than exempted: the gates
    // select by PATH, so an unlisted contributed directory would make the shells' decision-free
    // guarantee true only of the part someone remembered — and a rule a reader must remember is the
    // failure mode these gates exist to remove. `KotlinShellGuardTest.shellSourceRoots` mirrors this
    // list and must move with it.
    "test/rig/src/hook",
    // The same, compiled INTO `:app:ios:extension` in place of its `src/entries` under the same property.
    "test/rig/src/ext-hook",
)

// The detekt plugin registers its own `detekt` task against the ROOT project's Kotlin source set —
// which does not exist, so it inspects nothing and passes. That is the vacuous pass the guards exist
// to prevent, sitting one tab-completion away from the real ones: `./gradlew detekt` would report
// success and mean nothing.
//
// Disabled, so that every detekt task in this build is one somebody registered on purpose. There are
// now nine: `detektAppShell` (the shell PROOF, `docs/architecture.md`) and the eight tier
// tasks below (the complexity BUDGETS, `docs/architecture.md`). The distinction matters more
// than the count — see the tier block's header.
tasks.named("detekt") { enabled = false }

tasks.register<io.gitlab.arturbosch.detekt.Detekt>("detektAppShell") {
    description = "Counts decisions in the iOS app shells (`docs/architecture.md`)."
    group = "verification"
    setSource(appShellSources)
    // Production only. A test may branch as much as it likes — the rule is about wiring, not about
    // Kotlin. Pointing `setSource` at `src` picks up `iosTest`/`commonTest` too, which is how the
    // first run of this task (arch-abandoned-v1) scanned `PhotoKitSmokeTest.kt`.
    exclude("**/commonTest/**", "**/iosTest/**", "**/jvmTest/**", "**/appleTest/**", "**/nativeTest/**")
    exclude("**/build/**")
    config.setFrom(files("config/detekt/app-shell.yml"))
    buildUponDefaultConfig = false

    // GATING (since the migration finale drained the shells to zero unpinned decisions): a new
    // conditional in `:app:*` Kotlin fails the canonical build. The only tolerated forms are the
    // explicitly pinned `@Suppress("CyclomaticComplexMethod")` sites, whose inventory — each with
    // its forcing proof — is itself gated by `KotlinShellGuardTest` (`:test:architecture`), so a
    // new suppression is as loud as a new branch.
    ignoreFailures = false
    reports {
        xml.required.set(true)
        html.required.set(false)
        txt.required.set(false)
        sarif.required.set(false)
        md.required.set(false)
    }
}

tasks.named("check") { dependsOn("detektAppShell") }

// ---- Complexity budgets (`docs/architecture.md`) --------------------------------------
//
// A CEILING on complexity for every Kotlin source in the repository, expressed per scope, seeded at
// what the tree measured, and permitted to move in one direction only: down.
//
// NOT the same kind of thing as `detektAppShell` above, and the two must never be merged. That gate
// is a PROOF — threshold 2 asserts the shells hold no decisions, and its value comes precisely from
// the number being 2. These are BUDGETS: they start where the code happens to be and fall as people
// improve it. Raising the shell's threshold to a number the wider tree passes would destroy the
// shell claim, which is why there are two gates and not one widened one.
//
// EIGHT tasks and eight configs because a detekt 1.x rule carries exactly ONE threshold per config:
// path filters vary the SCOPE a rule applies to, never its number (`detekt-api`'s `Rule`/`PathFilters`
// against `detekt-rules-complexity`'s single `threshold` delegate). Per-scope ceilings are therefore
// not a configuration feature — they are a task-per-scope.
//
// COVERAGE IS DERIVED, NOT LISTED. `tierOf` maps subproject PATHS, and the tasks read each mapped
// subproject's own `src` directory out of the live project model, so a module added to
// `settings.gradle.kts` is scanned without touching this file — or, if it is in no tier,
// `DetektTierCoverageTest` (`:test:architecture`) fails naming it. That is a deliberate departure
// from `appShellSources` above, which is a hand list mirrored in a test. The mirror was faithful and
// BOTH copies were wrong: `:app:ios:forge` was missing from each until this change measured the tree.
// A mechanism whose known failure has already occurred is not the one to reuse at eight times the
// scale. The shell gate keeps its list because one of its roots (`test/rig/src/hook`) is not a module
// and the project model cannot express it.

/** Kotlin source-set directory names that hold TESTS. Everything else under `src/` is production. */
val testSourceSetDirs = listOf(
    "commonTest", "iosTest", "iosSimulatorArm64Test", "jvmTest", "forgeTest", "appleTest",
    "nativeTest", "test",
)

/**
 * Subproject path → production tier. EVERY subproject is mapped, exactly once; `DetektTierCoverageTest`
 * fails the build naming any that is not, so a new module cannot be silently unmeasured.
 *
 * `tests` is the tier of a module whose only source is tests (`:test:architecture`,
 * `:test:integration`). Every OTHER module's test source sets are scanned by the `tests` task too —
 * that tier is a source-set dimension as well as a module bucket.
 */
val detektTierOf: Map<String, String> = mapOf(
    // The iOS shells. Scanned here only for the rules the shell config does not carry (length,
    // parameter counts, naming); their DECISIONS are the shell proof's business, at threshold 2.
    ":app:ios" to "shell",
    ":app:ios:extension" to "shell",
    ":app:ios:forge" to "shell",
    ":domain:host" to "shell",

    // The tested core and its adapters. `:domain:presentation` belongs here and not in `ui`: it is
    // Compose-free (a core zone with no Compose dependency), so none of Compose's structural inflation
    // applies to it. The host, `:domain:host`, is a core zone too, but it is tiered as a shell above:
    // it is wiring, and detektAppShell's threshold-2 proof is what keeps it decision-free.
    ":domain:model" to "core",
    ":domain:ports" to "core",
    ":domain:services" to "core",
    ":domain:feature" to "core",
    ":domain:flow" to "core",
    ":domain:compose" to "core",
    ":adapter:generic:app" to "core",
    ":adapter:generic:fake" to "core",
    ":adapter:ios:app-only" to "core",
    ":adapter:ios:ext-safe" to "core",
    ":domain:presentation" to "core",

    // Compose. Its own tier because Compose inflates cyclomatic complexity and function length
    // STRUCTURALLY — a screen that renders six states has six branches by construction — so holding
    // it to `core`'s numbers would mean either a permanent exclusion list or a `core` ceiling loose
    // enough to enforce nothing.
    ":ui:components" to "ui",
    ":ui:screens" to "ui",

    // Test equipment: harnesses, the control channel, the world, the diagram generators. Read once,
    // never shipped. These ceilings exist to catch a regression, not to converge.
    ":app:desktop" to "harness",
    ":test:harness-driver" to "harness",
    ":test:rig" to "harness",
    ":test:world" to "harness",
    ":test:contracts" to "harness",
    ":test:edge" to "harness",
    ":test:control" to "harness",
    ":tools:diagrams" to "harness",

    // Modules with no production source at all.
    ":test:architecture" to "tests",
    ":test:integration" to "tests",
)

/** The `src` directory of every subproject in a tier, read from the live project model. */
fun tierSrcDirs(tier: String): List<File> = subprojects
    .filter { detektTierOf[it.path] == tier }
    .map { it.layout.projectDirectory.dir("src").asFile }

/**
 * Registers one gating tier task. `assertNonEmpty` is the anti-vacuity floor required of every
 * source-scanning guard: detekt reports SUCCESS on an empty source set, so a filter regression would
 * otherwise pass by inspecting nothing.
 */
fun registerDetektTier(
    name: String,
    configFile: String,
    sources: List<File>,
    configure: io.gitlab.arturbosch.detekt.Detekt.() -> Unit = {},
) {
    tasks.register<io.gitlab.arturbosch.detekt.Detekt>(name) {
        description = "Complexity ceiling for the `$configFile` scope (`docs/architecture.md`)."
        group = "verification"
        setSource(files(sources))
        exclude("**/build/**")
        // The shared baseline first, then this tier's own file overriding it (detekt layers configs in
        // order). The tier file is OPTIONAL: its absence means the scope sits at the baseline, which is
        // what makes the set of files under `config/detekt/` the list of scopes still carrying debt
        // (`docs/architecture.md`). `DetektTierCoverageTest` asserts every file present belongs
        // to a tier — the reverse of what it asserted while every tier was required to have one.
        val tierConfig = file("config/detekt/$configFile.yml")
        config.setFrom(files("config/detekt/_base.yml") + if (tierConfig.exists()) files(tierConfig) else files())
        buildUponDefaultConfig = true
        ignoreFailures = false
        reports {
            xml.required.set(true)
            // Named per task. The plugin's default is `detekt.xml` for every task, so nine tasks would
            // overwrite one file and only the last to finish would be readable.
            xml.outputLocation.set(layout.buildDirectory.file("reports/detekt/$name.xml"))
            html.required.set(false)
            txt.required.set(false)
            sarif.required.set(false)
            md.required.set(false)
        }
        configure()
        doFirst {
            check(!source.isEmpty) {
                "detekt tier `$name` scanned ZERO files — its scope resolved empty, so it would have " +
                    "passed while inspecting nothing (`docs/architecture.md`, " +
                    "\"Coverage is derived, never remembered\")."
            }
        }
    }
    tasks.named("check") { dependsOn(name) }
}

// The production tiers. Each excludes test source sets: the `tests` tier owns those, on its own
// numbers, because test code measured structurally SIMPLER than production (max cyclomatic 6) and far
// wider (89 functions in one class). One budget over both would be loose in one dimension and
// meaningless in the other.
val excludeTests = testSourceSetDirs.map { "**/$it/**" }.toTypedArray()

registerDetektTier("detektShellTier", "shell", tierSrcDirs("shell")) { exclude(*excludeTests) }
registerDetektTier("detektUiTier", "ui", tierSrcDirs("ui")) { exclude(*excludeTests) }

registerDetektTier("detektCoreTier", "core", tierSrcDirs("core")) {
    exclude(*excludeTests)
    // `flow/` and `compose/` are carved out into their own tiers below — the two zones that carry a
    // written decision-free law and are close enough to it to be gated on it.
    exclude("**/app/snapsync/flow/**", "**/app/snapsync/compose/**")
}

registerDetektTier("detektHarnessTier", "harness", tierSrcDirs("harness")) {
    exclude(*excludeTests)
    // Shell source contributed into `:app:ios`, owned by `detektAppShell` at threshold 2. Scanning it
    // here too would subject it to a ceiling 20× looser than the one it already passes.
    exclude("**/test/rig/src/hook/**", "**/test/rig/src/ext-hook/**")
}

// The two zones whose decision-free law is written but was never enforced (capability
// `docs/architecture.md`: "flows coordinate, never decide"; "One shared composition"). Path sub-scopes
// of `:domain`, carved out of `core`.
registerDetektTier("detektFlowTier", "flow", tierSrcDirs("core")) {
    include("**/app/snapsync/flow/**")
    exclude(*excludeTests)
}
registerDetektTier("detektComposeTier", "compose", tierSrcDirs("core")) {
    include("**/app/snapsync/compose/**")
    exclude(*excludeTests)
}

// Every module's test source sets, plus the two modules that are nothing but tests.
registerDetektTier("detektTestsTier", "tests", subprojects.map { it.layout.projectDirectory.dir("src").asFile }) {
    include(*testSourceSetDirs.map { "**/$it/**" }.toTypedArray())
}

// The build scripts. Kotlin, carrying real logic (this file renders a diagram whose byte-identical
// twin lives in `:tools:diagrams`), and until now scanned by nothing. Read from the project model for
// the same reason as everything above.
registerDetektTier(
    "detektBuildScriptsTier",
    "buildscripts",
    listOf(project.buildFile, file("settings.gradle.kts")) + subprojects.map { it.buildFile },
)

// ---- Derived architecture diagrams (`docs/architecture.md`) ------------------------
//
// `./gradlew architectureDiagrams` regenerates everything under `architecture/`. The module graph
// is the one diagram that needs the live Gradle project model, so it is generated HERE; every
// source-scan diagram (zones, ports, flows, DI) is `:tools:diagrams:generate`'s.
//
// The freshness gate (`:tools:diagrams:test`, attached to `check`) cannot re-run Gradle from
// inside a test, so this task ALSO dumps its model into a committed sidecar
// (`architecture/.modules-inputs.txt`); the test re-renders `modules.md` from the sidecar and
// compares. The renderer below therefore has a byte-identical twin in
// `tools/diagrams/src/main/kotlin/app/snapsync/tools/diagrams/Modules.kt` — change BOTH, or the
// freshness test fails (loudly, by design: two renderers disagreeing is drift).
//
// Determinism (spec requirement): code-point sort (`compareBy { it }` on strings), hardcoded
// "\n", explicit UTF-8, no timestamps, no absolute paths.

fun mermaidId(path: String): String = path.trimStart(':').replace(':', '_').replace('-', '_')

fun renderModulesMarkdown(modules: List<String>, edges: List<Pair<String, String>>): String {
    val sb = StringBuilder()
    sb.append("# Module dependency graph\n")
    sb.append("\n")
    sb.append("Generated by `./gradlew architectureDiagrams` from the Gradle project model: every\n")
    sb.append("`project(...)` dependency declared by any BUILD configuration of any module,\n")
    sb.append("deduplicated. Report-aggregation configurations (`kover*`) are excluded: they merge\n")
    sb.append("coverage data rather than putting classes on a classpath, so an edge through one is not\n")
    sb.append("an architectural dependency and would render backwards here.\n")
    sb.append("Do not edit — the `:tools:diagrams` freshness test fails on drift; regenerate instead.\n")
    sb.append("\n")
    sb.append("```mermaid\n")
    sb.append("flowchart LR\n")
    for (m in modules) sb.append("  ").append(mermaidId(m)).append("[\"").append(m).append("\"]\n")
    for ((from, to) in edges) {
        sb.append("  ").append(mermaidId(from)).append(" --> ").append(mermaidId(to)).append("\n")
    }
    sb.append("```\n")
    return sb.toString()
}

val architectureModulesDiagram = tasks.register("architectureModulesDiagram") {
    description = "Regenerate architecture/modules.md (+ its model sidecar) from the Gradle project model."
    // Reads every subproject's configurations at execution time — the whole point of the task —
    // which the configuration cache forbids. This repo does not enable it; declare honestly anyway.
    notCompatibleWithConfigurationCache("reads the project model of every subproject at execution time")
    doLast {
        // Container paths (`:app`, `:domain`, …) exist in the model only because their children do;
        // they have no build script, no configurations, and no place in the graph.
        val real = subprojects.filter { it.buildFile.exists() }
        val modules = real.map { it.path }.sortedWith(compareBy { it })
        val edges = mutableSetOf<Pair<String, String>>()
        real.forEach { p ->
            // Report-aggregation configurations are NOT architectural dependencies (capability
            // `docs/architecture.md`, "The module graph counts architectural dependencies only").
            // Kover's `kover` configuration merges another module's coverage DATA; it puts nothing on
            // a classpath. Left in, the coverage crediting edges required by `docs/architecture.md` render
            // as `:domain -> :adapter:generic:fake` and `:ui:components -> :ui:screens` — every one
            // pointing the opposite way to the real dependency, in the diagram that IS the record.
            p.configurations.filter { !it.name.startsWith("kover") }.forEach { c ->
                c.dependencies.filterIsInstance<ProjectDependency>().forEach { d ->
                    if (d.path != p.path) edges += p.path to d.path
                }
            }
        }
        val sortedEdges = edges.sortedWith(compareBy({ it.first }, { it.second }))
        val dir = layout.projectDirectory.dir("architecture").asFile
        dir.mkdirs()
        val sidecar = buildString {
            append("# Gradle module-graph model: `module <path>` + `edge <from> <to>`, code-point sorted.\n")
            append("# Generated by `./gradlew architectureDiagrams`; the `:tools:diagrams` freshness test\n")
            append("# renders modules.md from this dump (a test cannot see the Gradle model). Do not edit.\n")
            modules.forEach { append("module $it\n") }
            sortedEdges.forEach { (from, to) -> append("edge $from $to\n") }
        }
        File(dir, ".modules-inputs.txt").writeText(sidecar, Charsets.UTF_8)
        File(dir, "modules.md").writeText(renderModulesMarkdown(modules, sortedEdges), Charsets.UTF_8)
    }
}

tasks.register("architectureDiagrams") {
    description = "Regenerate every derived diagram under architecture/ (`docs/architecture.md`)."
    dependsOn(architectureModulesDiagram, ":tools:diagrams:generate")
}

// ---- Compiler warnings are errors -------------------------------------------------------------
//
// Every Kotlin compilation in every module fails on a warning, so a new one is fixed where it is
// introduced instead of joining a backlog nobody reads (147 had accumulated in CI logs by 2026-09).
// A warning that is deliberate is silenced AT ITS SITE (`@Suppress`, `@OptIn`) with the reason next
// to it, never here. Build scripts are held to the same rule by
// `org.gradle.kotlin.dsl.allWarningsAsErrors` in gradle.properties.
//
// The shared-source-set METADATA compilations (`compile*KotlinMetadata`) are the one exemption, and it
// costs no coverage: every source set they compile is also compiled by a platform compilation (jvm,
// iosArm64, iosSimulatorArm64), which is strict, so a warning in `commonMain`/`iosMain` code still fails
// the build there. What only the metadata compilation reports is the Kotlin/Native KLIB loader's
// "same unique_name found in more than one library" — Compose Multiplatform puts both its
// `org.jetbrains.compose.*` and Google's `androidx.compose.*` commonMain klibs on the iosMain metadata
// classpath, which no source change here can fix.
subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>()
        .matching { !it.name.endsWith("KotlinMetadata") }
        .configureEach { compilerOptions.allWarningsAsErrors.set(true) }
}

// ---- Kotlin/Native backend threads -----------------------------------------------------------
//
// KGP runs every Kotlin/Native link with 4 backend threads by default, and on a host with fewer cores
// the compiler warns on every link (`The number of threads 4 is more than the number of processors 3`
// — GitHub's macos-26 runner has 3). So the count is min(cores, 4): the default's cap, never more
// threads than the host has. KGP reads `kotlin.native.parallelThreads` from each project's extra
// properties; an explicit `-Pkotlin.native.parallelThreads=` still wins.
val nativeParallelThreads = minOf(Runtime.getRuntime().availableProcessors(), 4)
if (!providers.gradleProperty("kotlin.native.parallelThreads").isPresent) {
    allprojects { extra["kotlin.native.parallelThreads"] = nativeParallelThreads.toString() }
}
