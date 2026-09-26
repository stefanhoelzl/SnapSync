import org.gradle.api.tasks.PathSensitivity

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kover)
}
// NOT INSTRUMENTED (`docs/architecture.md`, "Coverage is measured over unit tests only"):
// structural guards over the repository's own text. A guard passing is not evidence that the
// code it inspects is tested - measured, it adds zero incremental coverage anywhere.
//
// `disabledForAll` - note the spelling, not `disableForAll` - means this module is not instrumented,
// its coverage data is omitted from every report, and its test tasks are not triggered by report
// generation.
kover {
    currentProject {
        instrumentation {
            disabledForAll = true
        }
    }
}


// Test-only ARCHITECTURE GUARDS (`docs/architecture.md`): structural invariants the compiler
// cannot express, enforced as ordinary tests so they run under `./gradlew build` — the canonical check.
//
// JVM-only on purpose. The guards read the repository's SOURCE TEXT, which reaches `iosMain` — Kotlin/Native
// code that has no JVM bytecode and cannot even be compiled on Linux — from a plain JVM test. That is
// precisely why a linter could not do this job: detekt has no type resolution for Kotlin/Native source
// sets, so on the one source set where every `SecItem` call lives it degrades to import-checking, and a
// fully-qualified `platform.Security.SecItemAdd(…)` call would sail past it.
//
// This module deliberately depends on NO Kotlin-parsing library either. Five guards used to obtain their
// file list from Konsist while using nothing but `.path` and `.text` from it — a PSI parser doing the work
// of `File.walkTopDown()`, and not for free: Konsist 0.17.3 (December 2024) embeds a Kotlin 2.0.21 compiler
// while this project builds with 2.4.0. See `SourceScan`.
//
// This module deliberately depends on NO project modules: it reads the repository's source and
// entitlements files, so a guard can never be defeated by a dependency edge.
kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

dependencies {
    testImplementation(kotlin("test"))
    // ProducerExclusivityTest drives the REAL UploadTransitions and admission functions over fakes (capability
    // `docs/architecture.md`, "The upload transitions stop in-flight work only at a leave") — the one guard here
    // that executes domain code rather than reading source: the invariant is behavioral (no reachable sequence
    // orphans in-flight work), which no text scan can see.
    testImplementation(project(":domain:model"))
    testImplementation(project(":domain:feature"))
    // The transitions read the membership and the grant through their services, built over the ports' mocks.
    testImplementation(project(":domain:ports"))
    testImplementation(project(":domain:services"))
    testImplementation(project(":adapter:generic:fake"))
    // GatedPathPinTest drives the client's REAL ungated-path predicate against the backend's closed list.
    testImplementation(project(":adapter:generic:app"))
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
            // The contract-coverage gate's other subject: the committed recordings. Without them a
            // recording-only change — a block deleted, a grant-suffixed file added — left this task
            // UP-TO-DATE, so the gate reported coverage from a recording that no longer said so. Measured
            // while adding grant-keyed recordings: a stray `.LIMITED.rec` passed until `--rerun`.
            include("test/contracts/recordings/*.rec")
            // The event-link domain guard's subjects (capability `join-event`). Without these the task
            // reports UP-TO-DATE after a backend-only or xcconfig-only edit — and the domain drift it
            // exists to catch is exactly the kind of edit that touches nothing else. Verified: changing
            // `LINK_DOMAIN` alone left the task UP-TO-DATE until they were declared here.
            include("gradle.properties")
            // The platform-vocabulary pin's subject is Apple's declared enum set, which ships inside
            // the Kotlin/Native distribution — so it changes with the KOTLIN VERSION, declared here.
            // Without this, the one edit that can move that vocabulary (a Kotlin bump) leaves this task
            // UP-TO-DATE and the pin silently stops pinning, which is the failure mode it exists for.
            include("gradle/libs.versions.toml")
            include("api/src/config.ts")
            // GatedPathPinTest reads the backend gate's closed ungated list, so a route the backend opens re-runs it.
            include("api/src/app.ts")
            // `CLAUDE.md` is declared for `RunbookSkillsTest` (below), its ONLY reader here: the runbook
            // pointers depend on it. No guard reads anything under `openspec/` — `ModuleSetTest` and
            // `RuntimeIdentityTest` hold their enumerations in code.
            include("CLAUDE.md")
            // `ModuleSetTest`'s subjects: the include set it compares against its in-code groups, and
            // the core zones' build files whose project edges it pins. Without them an include-only or
            // edge-only edit leaves this task UP-TO-DATE.
            include("settings.gradle.kts")
            include("domain/*/build.gradle.kts")
            // `RunbookSkillsTest`'s subjects (`docs/architecture.md`): CLAUDE.md's runbook
            // pointers must resolve to these files. Without them declared, renaming or
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
