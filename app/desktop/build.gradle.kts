plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

// This module is BOTH the shared harness *library* and the full-stack world *application*:
//  - Library surface (consumed by the forge harness `:app:desktop:ui`): `PhoneFrame` + the `StatusPane`
//    composition glue (construct `StatusContainerHost` from injected seams → render the real
//    `StatusScreen` inside the frame).
//  - Application: the full-stack world harness `main()` (`app.snapsync.desktop.FullStackHarnessKt`) that
//    runs the REAL platform-agnostic stack over `:test:world` behind the phone frame, driven by a
//    right-pane world inspector. This is the run task change 1 reserved: `:app:desktop:run`.
// The full-stack entry file compiles to `FullStackHarnessKt`, distinct from the forge's
// `app.snapsync.desktop.MainKt` (which leaks transitively onto `:app:desktop:ui`'s classpath) — so the
// two entry points never collide.
dependencies {
    implementation(project(":domain:permission"))
    implementation(project(":domain:status"))
    implementation(project(":domain:presentation"))
    implementation(project(":domain:ui"))
    // `StatusPane` provides the design-system's test-only `LocalDarkThemeOverride` around the phone
    // pane, so the components module is a direct dependency rather than transitive through `:domain:ui`.
    implementation(project(":domain:ui:components"))
    implementation(project(":capability:config"))
    // `StatusPane` names the create-event seams (`CreationStatusSource`/`EventCreator`) in its
    // signature, so the edge is explicit here rather than transitive.
    implementation(project(":capability:event-creation-ui"))
    // The full-stack harness: the controllable world + fakes (brings :domain:engine/:gallery/:capability
    // upload transitively for the types the inspector names) and the real store-backed download status.
    implementation(project(":test:world"))
    implementation(project(":capability:download"))
    // The full-stack harness routes create + scan through the REAL join gate over the world, so it
    // names the `JoinEvent` use-case (details load + enroll/provision) to reach the JoiningEvent surface.
    implementation(project(":capability:join"))
    // `JoinEvent` names `DeviceIdentity` (the enroll device id) in its constructor — non-transitive.
    implementation(project(":capability:device-id"))
    // The engine-console footer taps Kermit directly (transitive only via impl deps, so name it here).
    implementation(libs.kermit)
    implementation(compose.runtime)
    implementation(compose.foundation)
    // The world inspector is deliberately raw Material 3, never App* (spec: full-stack-harness); the application
    // needs the desktop window/runtime.
    implementation(compose.material3)
    implementation(compose.desktop.currentOs)
}

// Compose Desktop's run task does NOT inherit kotlin { jvmToolchain(...) }; without an explicit
// javaHome it launches on the Gradle JVM -> UnsupportedClassVersionError. (Same setup as :app:desktop:ui.)
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
