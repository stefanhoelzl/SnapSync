import org.gradle.api.tasks.PathSensitivity

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kover)
}
// NOT INSTRUMENTED (capability `coverage-bounds`, "Coverage is measured over unit tests only"):
// the freshness gate re-renders committed text and compares it, which is not evidence about
// product code.
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


// Derived architecture diagrams (capability `architecture-diagrams`): the source-scan generators
// (zones, ports, flows, DI) and the Mermaid renderer for the module graph. The module graph's
// MODEL comes from the root project's `architectureModulesDiagram` task (only Gradle can see the
// project model); everything here is a plain directory-walk over source text, so it runs as an
// ordinary JVM program and — critically — as an ordinary test.
//
// The freshness gate lives HERE, not in `:test:architecture`, on purpose: that module deliberately
// depends on no project modules (a guard must not be defeatable by a dependency edge), and this
// gate needs the generator library. `check` stays attached, so `./gradlew build` — the canonical
// check — fails on stale diagrams.
kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.register<JavaExec>("generate") {
    description = "Regenerate the source-scan diagrams under architecture/ (zones, ports, flows, DI)."
    mainClass = "app.snapsync.tools.diagrams.MainKt"
    classpath = sourceSets.main.get().runtimeClasspath
    args(rootDir.absolutePath)
    // Never UP-TO-DATE: the scan scope is the whole repository, and an UP-TO-DATE generator is a
    // stale-diagram generator. The scan costs seconds; declaring the tree as inputs buys nothing.
    outputs.upToDateWhen { false }
}

tasks.test {
    useJUnitPlatform()
    // The freshness test reads the committed diagrams AND every generator subject, so it must
    // re-run whenever either changes (spec `architecture-diagrams`: staleness cannot hide behind
    // build caching). Same input discipline as :test:architecture: a file tree over `src/`
    // specifically, never whole top-level directories (those contain other tasks' `build/` output).
    inputs.files(
        fileTree(rootDir) {
            include("architecture/**")
            include("settings.gradle.kts")
            include("build.gradle.kts")
            include("domain/**/build.gradle.kts")
            include("capability/**/build.gradle.kts")
            include("app/**/build.gradle.kts")
            include("test/**/build.gradle.kts")
            include("tools/**/build.gradle.kts")
            include("domain/**/src/**/*.kt")
            include("capability/**/src/**/*.kt")
            include("app/**/src/**/*.kt")
            include("test/**/src/**/*.kt")
            exclude("**/build/**")
        },
    ).withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("diagramSubjects")
}
