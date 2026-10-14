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
    // Every module's coverage, described in the `coverage { }` block below (`docs/architecture.md`, "Coverage").
    id("snapsync.coverage")
    alias(libs.plugins.dependency.analysis)
    // Loaded here so every module shares one Kover; `snapsync.coverage` applies it to the modules the `coverage { }`
    // block measures, and the modules whose tests also cover them apply it themselves.
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.kotlinx.rpc) apply false
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

// ---- Coverage (`docs/architecture.md`, "Coverage") -----------------------------------------------------------------
//
// Every module's coverage, described HERE and nowhere else (`snapsync.coverage`): which measurement holds it, which
// other modules' tests also cover it, and what is skipped, with the reason. Every JVM module outside `:test:*` and
// `:tools:*` has an entry, or `coverageEntries` fails naming it — so no module is measured, or left out, by nobody's
// decision.
//
// `unit(…)` is the ZERO gate: the module's report misses no instruction and no branch, outside compiler-generated glue.
// Its report is filtered to the module's OWN classes, so another module's tests count only when its entry names them in
// `alsoCoveredBy` — tests a placement rule put elsewhere:
//  - `:adapter:generic:mock` and `:test:feature` hold the zones' service- and mock-driven suites (`docs/testing.md`,
//    "Feature tests compose real services over port mocks"), which a `:domain:*` build file may not name;
//  - `:test:integration` covers the WIRING — `:domain:compose`, `:domain:host`, `:app:jvm` — and the entry points:
//    "One shared composition" says the wiring graph is not unit-tested and IS smoke-tested by the integration surface,
//    and the entry points order each trigger's steps and decide nothing (`docs/patterns.md`, "Entry points"), while the
//    integration surface delivers every trigger from the operating system's side. It covers no other module's unit
//    measurement: a thick integration suite cannot stand in for a thin unit suite.
//
// `integration(…)` is what `:test:integration` ALONE covers of the module, judged right after the suite by its
// `integrationCoverage` task; it credits no module. A target left out is `integrationGoal`; a lower one is an
// EXCEPTION — the module's lowest measurement when it was set, rounded down — which only rises (lowering one needs a
// reason stated in its PR) and fails the build once the module meets the goal. A measurement that moves between two
// runs of the same code is a flaky test to fix, never a margin.
//
// The block lives here, not in each module's build file: `ModuleSetTest` asserts a `:domain:*` build file names no
// module (the precondition for the platform-free compile error), and `Zones.kt` reads those files as TEXT, where an
// edge to the covering module would render backwards. Name LEAF modules: `:domain` is an empty container, and a report
// filtered to it measures nothing — `:domain:model:koverVerify` once passed at 100 over an empty report that way.
/** Why a module's integration measurement waits for phase 3g: the polling tests' race, not what the module lacks. */
val pollingRace = "the polling tests reach its SingleFlight yield/withdraw and expiry paths only in a process " +
    "death's race window, so its measurement moves with the machine's timing until phase 3g converts them"

coverage {
    integrationGoal(instructions = 95, branches = 85)
    ":domain:model" {
        unit(alsoCoveredBy = listOf(":adapter:generic:mock", ":test:feature"))
        // 72 → 71 in phase 4.1 (trust): the trust vocabulary the suite covered left for ports and data, so what stays
        // is the same code measured without it — no test was lost. 71/40 → 76/46 when the JVM host began playing
        // process-metric reports, which run the rule that reads them (`ProcessMetricsKt`).
        integration(instructions = 76, branches = 46)
    }
    // Declarations only: what it can count is the event ports' handler bundles, measured by the entry mocks' dispatch
    // tests (`EntryDispatchTest`). A member with a default body is logic in a declaration zone and moves out.
    ":domain:ports" {
        unit(alsoCoveredBy = listOf(":adapter:generic:mock", ":test:feature"))
        integration(instructions = 88, branches = 85) // no branches: any branch target is met
    }
    ":domain:services" {
        skipped(
            "SQLDelight's generated code: nobody writes or reviews it, and `DatabasesContract`'s schema clauses run " +
                "every statement on each platform's own SQLite",
            packages = listOf("app.snapsync.services.ledger.db", "app.snapsync.services.downloads.db"),
        )
        // Beside the JVM adapters runs the encrypted file format's cipher, held to Tink's reference implementation.
        unit(alsoCoveredBy = listOf(":adapter:generic:app", ":adapter:generic:mock", ":test:feature"))
        // 81/59 → 82/60 when the JVM host began playing process-metric reports (`ProcessAccount`, `FootprintSampler`).
        integration(instructions = 82, branches = 60)
    }
    ":domain:wire" {
        skipped(
            "generated from api/openapi.yaml (`snapsync.wire`): the routes, replies and limits, and the DTOs; the " +
                "Backend contract runs the real gateway over them",
            packages = listOf("app.snapsync.wire.api", "app.snapsync.wire.models"),
        )
        unit()
        integration(instructions = 82, branches = 71)
    }
    ":domain:feature" {
        unit(alsoCoveredBy = listOf(":adapter:generic:mock", ":test:feature"))
        integration(instructions = 90, branches = 73)
    }
    // The data layer's suites, and the application layer's use cases over it, run over the ports' mocks in `:test:data`
    // (`docs/testing.md`, "The data layer's suites"), a module a `:domain:*` build file may not name.
    ":domain:data" {
        unit(alsoCoveredBy = listOf(":test:data"))
        // The protocol's every reply and every store failure are the data suites'; the suite reaches the paths a
        // member's day takes.
        integration(instructions = 79, branches = 53)
    }
    ":domain:application" {
        unit(alsoCoveredBy = listOf(":test:data"))
        integration()
    }
    ":domain:domain" {
        unit()
        // Branches 64 → 62 in phase 4.1 (trust): the trust verdicts arrived, and their rare answers — a stale
        // challenge, each refusal reason — are their unit suites', not the integration surface's.
        integration(instructions = 87, branches = 75)
    }
    // Integration-skipped until phase 3g: what the polling tests reach of these two moves with the machine's timing.
    // Their SingleFlight yield/withdraw and expiry paths run only when a process dies in the few dispatches between a
    // pass yielding and its unwind — a window a test that waits for the app to settle never opens — and so do the
    // triggers those joins name (CI measured scheduling at 96.64 %; this tree 91.95–94.22 % instructions and
    // 69.35–74.19 % branches, inbound 65.01–77.38 % / 25–50 %, over runs of the same code). Once nothing polls, 3g
    // judges both at the goal.
    ":domain:inbound" {
        unit()
        integration { skipped(pollingRace) }
    }
    ":domain:scheduling" {
        unit()
        integration { skipped(pollingRace) }
    }
    // The process's composition registers its metric reports' entry point; the mocks' suite drives it.
    ":domain:entrypoints" {
        unit(alsoCoveredBy = listOf(":test:integration", ":adapter:generic:mock"))
        integration(instructions = 100, branches = 100)
    }
    ":domain:compose" {
        unit(alsoCoveredBy = listOf(":test:integration"))
        integration(instructions = 100, branches = 100)
    }
    ":domain:host" {
        unit(alsoCoveredBy = listOf(":test:integration"))
        integration(instructions = 100, branches = 100)
    }
    ":domain:presentation" {
        unit(alsoCoveredBy = listOf(":ui:screens"))
        integration(instructions = 87, branches = 76)
    }
    ":app:jvm" {
        unit(alsoCoveredBy = listOf(":test:integration"))
        integration(instructions = 100, branches = 100)
    }
    ":ui:state" {
        unit()
        integration(instructions = 90, branches = 62)
    }
    // `:ui:screens`' Compose tests render the real screens, and with them these components.
    ":ui:components" {
        skipped(
            "Compose Resources' generated `Res` accessors, one per string: measuring them would hold the module to " +
                "which strings a test reads",
            packages = listOf("app.snapsync.ui.components.resources"),
        )
        unit(alsoCoveredBy = listOf(":ui:screens"))
        integration { skipped("Compose: the suite stays at the intent level; the screens' own tests cover these") }
    }
    ":ui:screens" {
        skipped(
            "Compose Resources' generated `Res` accessors, one per string: measuring them would hold the module to " +
                "which strings a test reads",
            packages = listOf("app.snapsync.ui.resources"),
        )
        unit()
        integration { skipped("Compose: the suite stays at the intent level; its own Compose tests cover it") }
    }
    ":adapter:generic:app" {
        unit()
        integration { skipped("an adapter: the suite runs over mocks, and the port contracts prove adapters") }
    }
    ":adapter:generic:motionphoto" {
        unit()
        integration { skipped("an adapters' codec: the suite runs over mocks; its own round-trip tests prove it") }
    }
    ":adapter:generic:mock" {
        skipped("test equipment: the mocks are proved against the port contracts, not measured")
    }
    ":app:desktop" {
        skipped("test equipment: the world harness, where a person reviews every UI state")
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
// Kotlin/Native compiles on Linux, linked and run never). `ios`'s `iosPlatformTest` compiles and runs the iOS
// tests on a Mac, so on any other host their compiles are switched off; the main iOS compiles stay, so iOS-only
// breakage in production code still fails `build` (the Linux-runnable proxy, CLAUDE.md).
if (!System.getProperty("os.name").startsWith("Mac")) {
    subprojects {
        tasks.matching { it.name.matches(Regex("""compileTestKotlinIos\w*|link\w*TestIos\w*""")) }
            .configureEach { enabled = false }
    }
}
