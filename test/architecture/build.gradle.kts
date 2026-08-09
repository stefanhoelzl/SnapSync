import org.gradle.api.tasks.PathSensitivity

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Test-only ARCHITECTURE GUARDS (capability `architecture-guards`): structural invariants the compiler
// cannot express, enforced as ordinary tests so they run under `./gradlew build` — the canonical check.
//
// JVM-only on purpose. Konsist parses Kotlin **source** (the compiler's PSI), so it inspects `iosMain`
// — Kotlin/Native code that has no JVM bytecode and cannot even be compiled on Linux — from a plain JVM
// test. That is precisely why a linter could not do this job: detekt has no type resolution for
// Kotlin/Native source sets, so on the one source set where every `SecItem` call lives it degrades to
// import-checking, and a fully-qualified `platform.Security.SecItemAdd(…)` call would sail past it.
//
// This module deliberately depends on NO project modules: it reads the repository's source and
// entitlements files, so a guard can never be defeated by a dependency edge.
kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(libs.konsist)
    // ProducerExclusivityTest drives the REAL UploadArm over fakes (capability `architecture-guards`,
    // "The upload producers are never both started") — the one guard here that executes domain code
    // rather than reading source: the invariant is behavioral (a start-order property), which no text
    // scan can see.
    testImplementation(project(":domain"))
    testImplementation(libs.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
    // The guards read the repository's SOURCE and entitlements, so they must re-run whenever those
    // change — a guard that goes stale is a guard that fails open.
    //
    // Declared as a file tree over `src/` and the entitlements specifically, NOT as whole top-level
    // directories: `domain/`, `capability/` and `app/` also contain each module's `build/` output, and
    // depending on those makes this task consume other tasks' outputs without a dependency edge (Gradle
    // rejects it, and rightly — the guard's result would depend on task ordering).
    inputs.files(
        fileTree(rootDir) {
            include("domain/**/src/**/*.kt")
            include("capability/**/src/**/*.kt")
            include("app/**/src/**/*.kt")
            include("test/**/src/**/*.kt")
            // The adapter tree. `PlatformEntryLoggingTest` derives the OS-callback population
            // structurally from it (`: NSObject(), …Protocol` conformances), so without this an
            // adapter-only edit — adding a delegate method, dropping an entry log — leaves this task
            // UP-TO-DATE and the guard silently stops guarding. A guard that goes stale fails open,
            // which is the one failure mode these tests may not have.
            include("adapter/**/src/**/*.kt")
            include("iosApp/**/*.entitlements")
            // The event-link domain guard's subjects (capability `event-link`). Without these the task
            // reports UP-TO-DATE after a backend-only or xcconfig-only edit — and the domain drift it
            // exists to catch is exactly the kind of edit that touches nothing else. Verified: changing
            // `LINK_DOMAIN` alone left the task UP-TO-DATE until they were declared here.
            include("gradle.properties")
            include("api/src/config.ts")
            // The laws-digest guard's subjects (capability `module-architecture`): CLAUDE.md's digest
            // must name exactly the spec's requirement set. Without these declared, editing either side
            // alone leaves the task UP-TO-DATE — the precise staleness the guard exists to catch.
            include("CLAUDE.md")
            include("openspec/specs/module-architecture/spec.md")
            // `RunbookSkillsTest`'s subjects (capability `architecture-guards`): CLAUDE.md's runbook
            // pointers must resolve to these files, and `ios-device`'s launch-trigger index must equal
            // the `SNAPSYNC_*` literals in production Kotlin. Without them declared, renaming or
            // deleting a skill leaves this task UP-TO-DATE — a dangling pointer is invisible by
            // construction, so a guard that stops re-running is the same as no guard at all.
            include(".claude/skills/*/SKILL.md")
            include("iosApp/Configuration/Config.xcconfig")
            include("iosApp/iosApp/Info.plist")
            // The Swift shell. It is wiring-only and UNTESTED by the project's hard rule — which is
            // exactly how it shipped an app that silently dropped every event link (2026-07-16): no
            // guard had ever read it. We do not test its behaviour here (only a device can); we pin the
            // STRUCTURE that behaviour depends on.
            include("iosApp/**/*.swift")
            // `**` also matches GENERATED sources under each module's `build/` directory (e.g.
            // `app/desktop/build/generated/.../src/…`), which are other tasks' outputs. Guards read
            // hand-written source only.
            exclude("**/build/**")
        },
    ).withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("guardedSources")
}
