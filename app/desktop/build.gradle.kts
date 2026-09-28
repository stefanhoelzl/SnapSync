plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

// The ONE desktop module: the full-stack world harness — `:app:desktop:run`
// (`app.snapsync.desktop.FullStackHarnessKt`): the app the JVM root (`:app:jvm`) composes over the mocks, its screen
// in a `PhoneFrame`, driven by a right-pane world inspector (`docs/testing.md`). It is also where every UI state is
// reviewed without a device: each one is reachable through the inspector's levers, none is forged.
dependencies {
    implementation(libs.ktor.client.core)
    api(project(":domain:model"))
    api(project(":domain:ports"))
    api(project(":domain:feature"))
    // The screen pane and the mirror take the screen's `CutoffFormatter`.
    implementation(project(":domain:presentation"))
    implementation(project(":ui:screens"))
    // `StatusPane` provides the design-system's test-only `LocalDarkThemeOverride` around the phone
    // pane, so the components module is a direct dependency rather than transitive through `:ui:screens`.
    implementation(project(":ui:components"))
    // The generic adapters (`HttpBackend`, the system clock) the JVM root composes the app over.
    implementation(project(":adapter:generic:app"))
    // The full-stack harness: the JVM root, whose app IS the shared `snapSyncHost` composition over the mocks.
    implementation(project(":app:jvm"))
    // The inspector's policy badge reads the library mock through the same gallery services the cycle composes.
    implementation(project(":domain:services"))
    // The mirror: the control channel's typed client, whose wire `UiState` it re-composes.
    implementation(project(":test:control"))
    // The engine-console footer taps Kermit directly (transitive only via impl deps, so name it here).
    implementation(libs.kermit)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    // The panels are deliberately raw Material 3, never App* (specs: full-stack-harness,
    // desktop-test-harness); the applications need the desktop window/runtime.
    implementation(libs.compose.material3)
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
        // The mirror (`docs/testing.md`, "The harness can mirror a remote host"):
        // `./gradlew :app:desktop:run -Psnapsync.attach=http://127.0.0.1:<port>` attaches to a control-channel host.
        (project.findProperty("snapsync.attach") as String?)?.let { jvmArgs += "-Dsnapsync.attach=$it" }
    }
}
