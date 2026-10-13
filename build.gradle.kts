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
    // The Android target of every multiplatform module (`snapsync.android`) and the Android app (`:app:android`).
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.detekt)
    // Every detekt task this build runs, and the project's own rules on each (`DetektTiersPlugin`).
    id("snapsync.detekt-tiers")
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
// Without the edges the zones measure a fraction of their coverage: `docs/testing.md` ("Feature tests compose real
// services over port mocks") places the feature tests that need a port's mock in `:test:feature`, and the flow and
// mock-driven service tests in `:adapter:generic:mock` — modules a `:domain:*` build file may not name.
//
// They all live here rather than in the consuming modules, for two reasons that are really one:
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
    ":domain:model" to ":adapter:generic:mock",
    ":domain:ports" to ":adapter:generic:mock",
    // The services' suites run beside the mocks; beside the JVM adapters runs the encrypted file format's cipher,
    // held to Tink's reference implementation there (a `:domain:*` build file names no module).
    ":domain:services" to ":adapter:generic:app",
    ":domain:services" to ":adapter:generic:mock",
    ":domain:feature" to ":adapter:generic:mock",
    // `:test:feature` holds the feature tests that compose real services over the ports' mocks (`docs/testing.md`):
    // they exercise the feature zone first, and the services, the model and the ports it reaches through them.
    ":domain:feature" to ":test:feature",
    ":domain:services" to ":test:feature",
    ":domain:model" to ":test:feature",
    ":domain:ports" to ":test:feature",
    // The WIRING modules, credited by the integration surface and by nothing else it runs (`docs/architecture.md`,
    // "Coverage"): "One shared composition" says the wiring graph is not unit-tested and IS smoke-tested by the
    // integration surface, so for these the integration tests are the tests written for the code. Every other
    // module stays credited by unit tests only — integration still runs model, feature, services, … and counts for none
    // of them.
    ":domain:compose" to ":test:integration",
    ":domain:host" to ":test:integration",
    // The entry points order each trigger's steps and decide nothing (`docs/patterns.md`, "Entry points"): the
    // integration surface delivers every trigger through the operating system's side, which is what they are for.
    ":domain:entrypoints" to ":test:integration",
    // The process's composition registers its metric reports' entry point; the mocks' suite drives it.
    ":domain:entrypoints" to ":adapter:generic:mock",
    ":app:jvm" to ":test:integration",
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

// Every detekt task — the shell proof (`detektAppShell`) and the complexity budgets, one per tier — is registered by
// the `snapsync.detekt-tiers` convention plugin (`build-logic`), which owns the shell roots and the tier map.

// The build logic's own tests (today the zero coverage gate's judgement, `CoverageZeroTest`): an included build's
// `check` is never reached from this one's, so without this edge they would run on nobody's command.
tasks.named("check") { dependsOn(gradle.includedBuild("build-logic").task(":test")) }

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
            // as `:domain -> :adapter:generic:mock` and `:ui:components -> :ui:screens` — every one
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

// ---- Test failure messages ---------------------------------------------------------------------------------------
//
// Every test task — JVM `Test`, the Kotlin/Native simulator's — prints a failure in Gradle's FULL format: the
// assertion's message and its stack down to the test. SHORT, Gradle's default, prints the exception's class and the
// first frame in the test class, which for a `rigTest { … }` is the `= rigTest {` line: CI then showed
// `java.lang.AssertionError at EventChecksIntegrationTest.kt:33` and no `expected:<3> but was:<5>`. The simulator
// runner is worse still — `AssertionError at null:-1` — and a red iOS-only test cannot be re-run from Linux.
// Set ONCE, here, so a new module's tests are readable the day it has any. A module's own `testLogging` adds to this
// (the simulator modules turn standard streams on) and does not restate it.
subprojects {
    tasks.withType<AbstractTestTask>().configureEach {
        testLogging { exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    }
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

// ---- Platform-bound tests (`docs/testing.md`, "Where each test runs") ----------------------------------------------
//
// The shared `commonTest` runs ONCE, on the JVM, under `build`. A platform runtime runs only what only it can answer:
// a module's `iosTest` (the iOS adapters and their contract bindings on the simulator's `test.kexe`, the native
// SQLite drivers) through `./gradlew iosPlatformTest` (macOS), and a module's `androidDeviceTest` through
// `./gradlew androidPlatformTest` (`snapsync.android` registers that one, beside the managed device it runs on).
// Derived from the source tree, never listed: a module that gains iOS tests is run the day it does.
subprojects {
    if (file("src/iosTest").isDirectory || file("src/iosSimulatorArm64Test").isDirectory) {
        tasks.register("iosPlatformTest") {
            group = "verification"
            description = "Runs this module's iOS tests on the simulator."
            dependsOn("iosSimulatorArm64Test")
        }
    }
}

// ---- iOS test compiles off a Mac -----------------------------------------------------------------------------------
//
// Off macOS no simulator runs an iOS test, yet `build` compiled every module's test klibs for both iOS targets (37
// Kotlin/Native compiles on Linux, linked and run never). `ios-build`'s `iosPlatformTest` compiles and runs the iOS
// tests on a Mac, so on any other host their compiles are switched off; the main iOS compiles stay, so iOS-only
// breakage in production code still fails `build` (the Linux-runnable proxy, CLAUDE.md).
if (!System.getProperty("os.name").startsWith("Mac")) {
    subprojects {
        tasks.matching { it.name.matches(Regex("""compileTestKotlinIos\w*|link\w*TestIos\w*""")) }
            .configureEach { enabled = false }
    }
}
