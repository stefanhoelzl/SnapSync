import org.gradle.api.tasks.PathSensitivity

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// The MIGRATION BEACON (capability `architecture-guards`; decision record:
// `establish-target-architecture` D8, REVISED 2026-07-17): measures the distance between the
// current tree and the `module-architecture` target and FAILS while any distance remains — the
// `verify` job in `.github/workflows/architecture.yml` is RED BY DESIGN until the migration
// completes, then goes green. It is NOT a required check and gates no merge: `ios-release.yml`
// Guard 4 and `/ship`'s watcher both judge REQUIRED checks only, derived from branch protection —
// no name list, so any informational check is tolerated automatically.
//
// DETACHED FROM `check` — deliberately, and this is the load-bearing line of the file: anything
// `check` reaches runs under `./gradlew build`, the canonical gate, and a red-until-done test
// there would freeze every merge. Invoke explicitly: `./gradlew :test:architecture:migration:test`.
//
// Completion = every count in the burn-down reaches zero → the beacon goes GREEN. Then each
// measurement's law moves into `:test:architecture` as a permanent gate, and this module and
// the `verify` job are deleted.
kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.check {
    // Detach: `build` must never run the beacon (see header). The empty dependency list is asserted
    // by the beacon's own report so a refactor cannot silently re-attach it.
    setDependsOn(emptyList<Any>())
}

tasks.test {
    useJUnitPlatform()
    // Same input discipline as :test:architecture — the measurements read repository source, so
    // they must re-run when it changes; a stale beacon under-reports distance.
    inputs.files(
        fileTree(rootDir) {
            include("domain/**/src/**/*.kt")
            include("capability/**/src/**/*.kt")
            include("app/**/src/**/*.kt")
            include("test/**/src/**/*.kt")
            include("iosApp/**/*.swift")
            include("**/build.gradle.kts")
            include("settings.gradle.kts")
            include("gradle/libs.versions.toml")
            exclude("**/build/**")
        },
    ).withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("measuredSources")
    // The burn-down report is this task's product; CI appends it to $GITHUB_STEP_SUMMARY. The
    // report is written BEFORE the test fails, so a red run still carries its numbers.
    outputs.dir(layout.buildDirectory.dir("burn-down")).withPropertyName("burnDownReport")
}
