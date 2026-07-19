import org.gradle.api.artifacts.ProjectDependency

buildscript {
    // REQUIRED for `buildHealth` (capability `architecture-guards`). dependency-analysis 3.17.0 pins
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
    // canonical build (capability `architecture-guards`, "The shell gates").
    base
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.dependency.analysis)
}

// `./gradlew buildHealth` — declared-but-unused module dependencies (capability `architecture-guards`).
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

dependencyAnalysis {
    issues {
        all {
            onAny { severity("warn") }
        }
    }
}

val appShellSources = files(
    "app/ios/src",
    "app/ios/extension/src",
)

// The detekt plugin registers its own `detekt` task against the ROOT project's Kotlin source set —
// which does not exist, so it inspects nothing and passes. That is the vacuous pass the guards exist
// to prevent, sitting one tab-completion away from the real one: `./gradlew detekt` would report
// success and mean nothing. Disabled so there is exactly one detekt task in this build, and it is the
// shell measurement.
tasks.named("detekt") { enabled = false }

tasks.register<io.gitlab.arturbosch.detekt.Detekt>("detektAppShell") {
    description = "Counts decisions in the iOS app shells (capability `architecture-guards`)."
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

// ---- Derived architecture diagrams (capability `architecture-diagrams`) ------------------------
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
    sb.append("`project(...)` dependency declared by any configuration of any module, deduplicated.\n")
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
            p.configurations.forEach { c ->
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
    description = "Regenerate every derived diagram under architecture/ (spec `architecture-diagrams`)."
    dependsOn(architectureModulesDiagram, ":tools:diagrams:generate")
}
