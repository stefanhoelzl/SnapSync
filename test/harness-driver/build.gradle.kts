plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

// Test-only dev infrastructure (no spec — rationale in `Driver.kt`).
//
// Serves the desktop world harness over HTTP to a headless caller (an agent). It composes the SHIPPED
// harness root — `WorldHarnessRoot()`, or its mirror of a remote host — into an OFFSCREEN Compose scene, so
// there is no window, no X server, and no Wayland screen-capture portal prompt. Clicks go through the
// real buttons; pixels come out of the real render.
//
// This module exists rather than a `main()` in the harness modules because the harnesses are specified
// as thin test equipment carrying no logic (`docs/testing.md` req. 8) — the HTTP surface, the
// command loop, and the ui-test dependency belong outside them.
kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

dependencies {
    // The harness roots this drives, in `:app:desktop`.
    implementation(project(":app:desktop"))
    implementation(libs.compose.runtime)
    // `runDesktopComposeUiTest` + `captureToImage` — an `implementation` dep (not `testImplementation`):
    // the driver is a `main()`, not a test. The offscreen Compose scene it renders into is a CPU raster
    // Skia surface, which is exactly what the ui-test artifact provides and nothing else does.
    implementation(libs.compose.ui.test.junit4)
    implementation(compose.desktop.currentOs)
}

// Compose Desktop's run tasks do NOT inherit kotlin { jvmToolchain(...) }; without an explicit
// launcher they start on the Gradle JVM -> UnsupportedClassVersionError. (Same as the harness modules.)
val toolchainLauncher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(libs.versions.jdk.get().toInt()))
}

// The port file is written under THIS module's build dir, i.e. inside this git worktree. That is what
// makes concurrent workspaces safe: each drives its own harness on its own OS-assigned port, and the
// discovery path is workspace-relative, so no two agents can collide on a fixed port.
val portFile = layout.buildDirectory.file("harness-driver.port")

fun registerDrive(taskName: String, harness: String, blurb: String) =
    tasks.register<JavaExec>(taskName) {
        group = "harness"
        description = blurb
        mainClass.set("app.snapsync.harness.DriverKt")
        classpath = sourceSets["main"].runtimeClasspath
        javaLauncher.set(toolchainLauncher)
        // Skiko loads native libs via a restricted method; future JDKs block it by default.
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        // Proves the point: the scene renders with no display at all.
        jvmArgs("-Djava.awt.headless=true")
        systemProperty("harness.name", harness)
        systemProperty("harness.portFile", portFile.get().asFile.absolutePath)
        (project.findProperty("snapsync.attach") as String?)?.let { systemProperty("snapsync.attach", it) }
        outputs.upToDateWhen { false }
    }

registerDrive("driveWorld", "world", "Serve the full-stack world harness (:app:desktop:run) headlessly over HTTP.")
registerDrive(
    "driveMirror",
    "mirror",
    "Serve the world harness mirroring a remote host (-Psnapsync.attach=<url>) headlessly over HTTP.",
)
