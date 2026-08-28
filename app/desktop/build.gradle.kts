plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

// The ONE desktop module (migration step 10: `:app:desktop:ui` folded in), hosting BOTH harnesses:
//  - Shared pane: `PhoneFrame` + the `StatusPane` composition glue (construct `StatusContainerHost`
//    from injected seams → render the real `StatusScreen` inside the frame).
//  - **Full-stack world harness** — `:app:desktop:run` (`app.snapsync.desktop.FullStackHarnessKt`):
//    the REAL app graph composed by `snapSyncApp` over `:test:world`'s fakes behind the phone frame,
//    driven by a right-pane world inspector (capability `full-stack-harness`).
//  - **Forge harness** — `:app:desktop:runForge` (`app.snapsync.desktop.MainKt`): the same phone
//    frame over forge cells + a control panel that forges any UI state (capability
//    `desktop-test-harness`). Registered as a plain JavaExec below because the Compose Desktop
//    plugin models exactly one `application {}` main class per module.
dependencies {
    implementation(libs.ktor.client.core)
    api(project(":domain:model"))
    api(project(":domain:ports"))
    api(project(":domain:feature"))
    // The forge `PanelController` constructs its stand-in cells from `:ui:presentation`'s forge
    // seams (MutableAttestedSource, MutablePendingJoinSource); `StatusPane` names the host.
    implementation(project(":ui:presentation"))
    implementation(project(":ui:screens"))
    // `StatusPane` provides the design-system's test-only `LocalDarkThemeOverride` around the phone
    // pane, so the components module is a direct dependency rather than transitive through `:ui:screens`.
    implementation(project(":ui:components"))
    // The real Ktor clients the world composes (HttpEventCreation, HttpEventDirectory) moved
    // to the adapter layer at migration step 4.
    implementation(project(":adapter:generic:app"))
    // The full-stack harness: the controllable world (BackendStore + mini-edge + levers wrapping
    // `:adapter:generic:fake`) whose `World.core` IS the shared `snapSyncApp` composition.
    implementation(project(":test:world"))
    // The engine-console footer taps Kermit directly (transitive only via impl deps, so name it here).
    implementation(libs.kermit)
    implementation(compose.runtime)
    implementation(compose.foundation)
    // The panels are deliberately raw Material 3, never App* (specs: full-stack-harness,
    // desktop-test-harness); the applications need the desktop window/runtime.
    implementation(compose.material3)
    implementation(compose.desktop.currentOs)
}

// Compose Desktop's run task does NOT inherit kotlin { jvmToolchain(...) }; without an explicit
// javaHome it launches on the Gradle JVM -> UnsupportedClassVersionError.
val toolchainLauncher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(libs.versions.jdk.get().toInt()))
}

// HiDPI: on Linux the JVM often fails to auto-detect the display scale, so the harness renders at 1x and
// the compositor bitmap-upscales it (tiny + blurry). Force the render scale so the phone frame is crisp.
// Defaults to 2 (4K/Retina-class); tune without editing this file: `./gradlew :app:desktop:run -PuiScale=1`.
val uiScale = (project.findProperty("uiScale") as String?) ?: "2"

compose.desktop {
    application {
        mainClass = "app.snapsync.desktop.FullStackHarnessKt"
        javaHome = toolchainLauncher.get().metadata.installationPath.asFile.absolutePath
        // Skiko loads native libs via a restricted method; future JDKs block it by default.
        jvmArgs += "--enable-native-access=ALL-UNNAMED"
        jvmArgs += "-Dsun.java2d.uiScale=$uiScale"
    }
}

// The forge harness's window entry — the fold's replacement for the deleted `:app:desktop:ui:run`.
tasks.register<JavaExec>("runForge") {
    group = "compose desktop"
    description = "Run the forge harness (phone frame + control panel forging any UI state)."
    mainClass.set("app.snapsync.desktop.MainKt")
    classpath = sourceSets["main"].runtimeClasspath
    javaLauncher.set(toolchainLauncher)
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    jvmArgs("-Dsun.java2d.uiScale=$uiScale")
}
